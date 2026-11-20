# Redis RateLimiter Module Deep Dive

> This document reflects FluxGate **0.4**. For what changed from 0.3.x see the
> [0.4 migration guide](../operations/migration-0.4.md); for a walk-through of every layer in a single
> document see [Architecture Deep Dive (한국어)](../../ARCHITECTURE_DEEP_DIVE.ko.md).

This document explains the `fluxgate-redis-ratelimiter` module in detail, with the **actual source
code**.

[< Back to Architecture Overview](README.md) | [한국어 (deep dive)](../../ko/architecture/deep-dive/redis-ratelimiter.ko.md)

---

## Table of Contents

1. [Module Structure](#1-module-structure)
2. [RedisRateLimiter](#2-redisratelimiter)
3. [Bucket Key Layout](#3-bucket-key-layout)
4. [Redis Connection Layer](#4-redis-connection-layer)
5. [RedisRateLimiterConfig](#5-redisratelimiterconfig)
6. [Usage Examples](#6-usage-examples)

---

## 1. Module Structure

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/
├── RedisRateLimiter.java              # RateLimiter interface implementation
├── config/
│   └── RedisRateLimiterConfig.java    # configuration and initialization
├── connection/
│   ├── RedisConnectionProvider.java   # connection abstraction interface
│   ├── StandaloneRedisConnection.java # Standalone mode implementation
│   ├── ClusterRedisConnection.java    # Cluster mode implementation
│   ├── RedisConnectionFactory.java    # connection factory
│   ├── RedisUriUtils.java             # URI secret masking
│   └── RedisConnectionException.java  # exception class
├── store/
│   ├── RedisTokenBucketStore.java     # token bucket store
│   ├── BucketState.java               # bucket state object
│   ├── RedisRuleSetStore.java         # RuleSet store (@Deprecated 0.4.0)
│   └── RuleSetData.java               # RuleSet data object
├── script/
│   ├── LuaScriptRegistry.java         # per-store script body + SHA
│   ├── LuaScriptLoader.java           # @Deprecated 0.4.0 (compatibility)
│   └── LuaScripts.java                # @Deprecated 0.4.0 (compatibility)
└── health/
    └── RedisHealthCheckerImpl.java    # health check implementation

fluxgate-redis-ratelimiter/src/main/resources/lua/
└── token_bucket_consume.lua           # multi-band token bucket script
```

### What disappeared in 0.4: the process-global script SHA

`LuaScripts` and `LuaScriptLoader` held the script body and SHA in `static volatile` slots. With two
stores in one JVM pointing at different Redis deployments, they **overwrote each other's SHAs.**

```java
// LuaScripts.java - actual code
/**
 * @deprecated Use {@link LuaScriptRegistry} instead. The static slots here are process-wide, so two
 *     {@code RedisTokenBucketStore} instances pointing at different Redis deployments overwrite
 *     each other's SHA. Nothing inside FluxGate reads this class any more; it is kept only so that
 *     existing callers keep compiling and will be removed in a future release.
 */
@Deprecated(since = "0.4.0", forRemoval = true)
public final class LuaScripts {
```

Neither class is **read anywhere inside FluxGate any more.** They remain only so existing callers
keep compiling; the replacement carrying instance state is `LuaScriptRegistry`.

### Dependency graph

```
+-------------------+
|  RedisRateLimiter |  ← RateLimiter interface implementation
+-------------------+
         |
         | uses (one call per rule)
         v
+------------------------+       +---------------------+
|  RedisTokenBucketStore |-owns->|  LuaScriptRegistry  |  ← independent SHA per store
+------------------------+       +---------------------+
         |
         | uses
         v
+-------------------------+
| RedisConnectionProvider |  ← Standalone/Cluster abstraction
+-------------------------+
         |
    +----+----+
    |         |
    v         v
+----------+ +----------+
|Standalone| | Cluster  |
|Connection| |Connection|
+----------+ +----------+
```

```java
// RedisTokenBucketStore.java - actual code comment
/**
 * <p>Each store owns its own {@link LuaScriptRegistry}, so two stores pointing at different Redis
 * deployments in one JVM keep independent script SHAs.
 */
```

---

## 2. RedisRateLimiter

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/
└── RedisRateLimiter.java
```

The Redis-backed implementation of the `RateLimiter` interface.

```java
// RedisRateLimiter.java - actual code
/**
 * Redis-backed distributed rate limiter implementation.
 *
 * <p>Properties of this implementation:
 *
 * <ul>
 *   <li>Token buckets live in Redis hashes, one per (rule set, rule, key, band)
 *   <li>The Lua script uses Redis TIME, so all nodes share one clock
 *   <li>Buckets expire through Redis TTL, derived from the band's window. TOKEN_BUCKET and
 *       SLIDING_WINDOW TTLs are capped by {@code fluxgate.redis.max-bucket-ttl}; a FIXED_WINDOW
 *       counter is exempt and expires exactly at the end of its window
 *   <li>Supports multi-band rules (e.g. 10/sec AND 100/min AND 1000/hour)
 * </ul>
 *
 * <p><strong>Atomicity, honestly.</strong> Every band of <em>one</em> rule is evaluated in a single
 * Lua call, so within a rule the decision is atomic and all-or-nothing: a request rejected by the
 * per-minute band does not drain the per-second band. Across <em>rules</em> a rejected request
 * costs nothing either, by one of two strategies:
 *
 * <ol>
 *   <li><strong>One call.</strong> When every key of every matching rule may go into one script
 *       call - always on a standalone Redis, and in a cluster when all keys hash to one slot - all
 *       rules are evaluated together, all-or-nothing, exactly like the bands of one rule.
 *   <li><strong>Compensation.</strong> Otherwise (a cluster, with rules whose hash tags land in
 *       different slots) the rules are charged one by one; when one rejects, or Redis fails, the
 *       rules already charged are refunded by {@link RedisTokenBucketStore#refund}.
 * </ol>
 *
 * <p>Compensation is not atomic, and does not pretend to be. Between a rule's charge and its refund
 * - one round trip per rule - concurrent requests see that rule a permit lower and may be rejected
 * by it; that errs on the strict side and heals itself with the refund. If the refund itself cannot
 * run (the process dies, or Redis fails between charge and refund) the permit stays spent, which is
 * the pre-0.4 behaviour. A refund also only returns what is still there: a fixed window that rolled
 * over, or a sliding sub-bucket that left the window, between charge and refund has nothing left to
 * give back.
 *
 * <p><strong>Cost of a compensated rejection.</strong> When rule {@code k} of {@code n} rejects,
 * the request costs {@code k} consume calls, {@code k - 1} refund calls and up to 8 check-only
 * calls: the rules after the rejecting one are not charged, but are checked so that the reported
 * Retry-After is the longest wait of all rules, not the first one found. Each call is one round
 * trip, possibly to another cluster node. The checks are capped so that a rule set with many
 * cross-slot rules cannot turn one rejected request into an unbounded number of round trips; rules
 * beyond the cap are not consulted, and their wait - if longer - is only discovered when the client
 * retries.
 *
 * <p>Thread-safe and suitable for distributed environments with multiple API gateway nodes.
 *
 * @see RedisTokenBucketStore
 */
public class RedisRateLimiter implements RateLimiter, AutoCloseable {

  /** Prefix shared by every token bucket key. Disjoint from {@code fluxgate:ruleset:}. */
  public static final String BUCKET_KEY_PREFIX = "fluxgate:bucket:";

  private final RedisTokenBucketStore tokenBucketStore;

  /** Rule ids already warned about a window that the bucket TTL cap shortens. */
  private final Set<String> ttlClampWarnedRules = ConcurrentHashMap.newKeySet();

  /**
   * Path pattern matcher used to filter applicable rules via {@link
   * RateLimitRuleSet#getMatchingRules}.
   */
  private final PathPatternMatcher pathMatcher;

  @Override
  public RateLimitResult tryConsume(
      RequestContext context, RateLimitRuleSet ruleSet, long permits) {
    return tryConsume(context, ruleSet, permits, pathMatcher);
  }

  @Override
  public RateLimitResult tryConsume(
      RequestContext context, RateLimitRuleSet ruleSet, long permits, PathPatternMatcher matcher) {

    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(ruleSet, "ruleSet must not be null");
    Objects.requireNonNull(matcher, "matcher must not be null");

    if (permits <= 0) {
      throw new IllegalArgumentException("permits must be > 0");
    }

    List<RateLimitRule> rules = ruleSet.getMatchingRules(context, matcher);
    if (rules.isEmpty()) {
      log.debug("No matching rules in ruleSet {}, nothing to enforce", ruleSet.getId());
      return record(context, ruleSet, RateLimitResult.allowedWithoutRule());
    }

    // ========================================================================
    // Multi-Rule Rate Limiting with Per-Rule Key Resolution
    // ========================================================================
    // Each rule can have a different LimitScope (PER_IP, PER_USER, PER_API_KEY, ...), so the
    // KeyResolver is asked once per rule - for every rule before anything is charged, so a key
    // that cannot be resolved rejects the request without having drained an earlier rule.
    // ========================================================================

    List<RuleCall> calls = new ArrayList<>(rules.size());
    for (RateLimitRule rule : rules) {
      // rules from getMatchingRules are already enabled and match the request path/method
      List<RateLimitBand> bands = rule.getBands();
      if (bands == null || bands.isEmpty()) {
        continue;
      }

      RateLimitKey logicalKey;
      try {
        logicalKey = ruleSet.getKeyResolver().resolve(context, rule);
      } catch (MissingRateLimitKeyException e) {
        return record(context, ruleSet, missingKeyResult(e, rule));
      }
      Objects.requireNonNull(
          logicalKey, "resolved RateLimitKey must not be null for rule: " + rule.getId());

      warnOnceIfBucketTtlClampsWindow(rule, bands);

      List<String> bucketKeys = new ArrayList<>(bands.size());
      for (RateLimitBand band : bands) {
        bucketKeys.add(buildBucketKey(ruleSet.getId(), rule.getId(), logicalKey, band));
      }
      calls.add(new RuleCall(rule, logicalKey, bands, bucketKeys));
    }

    if (calls.isEmpty()) {
      // All matching rules have empty band lists: nothing to enforce.
      log.debug("No rule with bands in ruleSet {}, nothing to enforce", ruleSet.getId());
      return record(context, ruleSet, RateLimitResult.allowedWithoutRule());
    }

    if (calls.size() > 1) {
      List<String> allKeys = new ArrayList<>();
      for (RuleCall call : calls) {
        allKeys.addAll(call.bucketKeys);
      }
      if (new HashSet<>(allKeys).size() == allKeys.size()
          && tokenBucketStore.canEvaluateAtomically(allKeys)) {
        return record(context, ruleSet, consumeInOneCall(calls, allKeys, permits));
      }
    }
    return record(context, ruleSet, consumeWithCompensation(calls, permits));
  }

  /**
   * Evaluates every rule in a single script call: the bands of all rules are concatenated, so the
   * script's two passes make the whole decision all-or-nothing.
   *
   * <p>The script reports the rejecting band with the longest wait, or on allow the band with the
   * fewest tokens left, in the concatenated order - the same band rule-by-rule evaluation reports,
   * since that checks the rules after a rejecting one for a longer wait as well.
   */
  private RateLimitResult consumeInOneCall(
      List<RuleCall> calls, List<String> allKeys, long permits) {
    List<RateLimitBand> allBands = new ArrayList<>(allKeys.size());
    for (RuleCall call : calls) {
      allBands.addAll(call.bands);
    }

    BucketState state = tokenBucketStore.tryConsume(allKeys, allBands, permits);

    RuleCall call = calls.get(0);
    int localIndex = state.bandIndex();
    for (RuleCall candidate : calls) {
      call = candidate;
      if (localIndex < candidate.bands.size()) {
        break;
      }
      localIndex -= candidate.bands.size();
    }
    RateLimitBand band = bandAt(call.bands, localIndex);

    if (!state.consumed()) {
      Binding rejection = new Binding(call.key, call.rule, band, state);
      logRejected(rejection);
      return rejectedResult(call.key, call.rule, band, state);
    }
    Binding binding = new Binding(call.key, call.rule, band, state);
    logAllowed(binding);
    return allowedResult(binding);
  }

  /**
   * Charges the rules one by one and, when one rejects or Redis fails, refunds the rules already
   * charged. See the class Javadoc for the window in which this is not atomic.
   */
  private RateLimitResult consumeWithCompensation(List<RuleCall> calls, long permits) {
    List<RuleCall> charged = new ArrayList<>(calls.size());
    List<BucketState> chargedStates = new ArrayList<>(calls.size());
    Binding binding = null;

    for (int index = 0; index < calls.size(); index++) {
      RuleCall call = calls.get(index);
      BucketState state;
      try {
        state = tokenBucketStore.tryConsume(call.bucketKeys, call.bands, permits);
      } catch (RuntimeException e) {
        refund(charged, chargedStates, permits);
        throw e;
      }
      RateLimitBand bindingBand = bandAt(call.bands, state.bandIndex());

      if (!state.consumed()) {
        // No further rule is charged, and the rules already charged get their permits back.
        refund(charged, chargedStates, permits);
        Binding rejection =
            longestWait(
                new Binding(call.key, call.rule, bindingBand, state),
                calls.subList(index + 1, calls.size()),
                permits);
        logRejected(rejection);
        return rejectedResult(rejection.key, rejection.rule, rejection.band, rejection.state);
      }

      charged.add(call);
      chargedStates.add(state);
      if (binding == null || state.remainingTokens() < binding.state.remainingTokens()) {
        binding = new Binding(call.key, call.rule, bindingBand, state);
      }
    }

    logAllowed(binding);
    return allowedResult(binding);
  }

  // ... longestWait(), refund(), the result builders and the key helpers follow
}
```

`tryConsume` works in two phases:

1. **Collect.** `ruleSet.getMatchingRules(context, matcher)` returns only the enabled rules whose
   `RuleMatcher` matches the request (the 3-argument overload uses the limiter's own matcher,
   `SimpleAntPathMatcher.INSTANCE` by default). For each rule with bands the key is resolved and the
   bucket keys are built into a `RuleCall`. Nothing is charged yet, so a `MissingRateLimitKeyException`
   in a later rule rejects the request without having drained an earlier one.
2. **Charge.** With more than one `RuleCall`, distinct bucket keys and
   `tokenBucketStore.canEvaluateAtomically(allKeys)`, every rule goes into one script call
   (`consumeInOneCall`). Otherwise - a single rule, or rules spread over cluster slots - the rules
   are charged one by one (`consumeWithCompensation`); for a single rule that is exactly one call.

### What changed in 0.4: one round trip per band → one per rule

The 0.3.x loop called `tokenBucketStore.tryConsume(bucketKey, band, permits)` **per band.** That
produced two problems.

**Problem 1: multi-band rules enforced less than their nominal limit.** In a "10/sec AND 100/min"
rule, the moment the per-minute band rejected, the per-second band before it had already charged
tokens. Rejected requests kept draining the bands that would have allowed them, so the effective
limit landed below the configured one.

**Problem 2: round trips.** A three-band rule meant three Redis round trips.

0.4 passes **every band key of one rule in a single Lua call.** The script processes them in two
passes, so the outcome is all-or-nothing.

```java
// RedisRateLimiter.java - actual code (collect phase, then consumeWithCompensation)
List<String> bucketKeys = new ArrayList<>(bands.size());
for (RateLimitBand band : bands) {
  bucketKeys.add(buildBucketKey(ruleSet.getId(), rule.getId(), logicalKey, band));
}
calls.add(new RuleCall(rule, logicalKey, bands, bucketKeys));

// ...

state = tokenBucketStore.tryConsume(call.bucketKeys, call.bands, permits);
```

### Across rules: one call, or compensation

That is why the class Javadoc's headline is **"Atomicity, honestly."** Each rule resolves a
different key, and in a cluster different keys may live in different hash slots, so they cannot
always go into one Lua call. 0.4 uses two strategies. Either way **the keys of all rules are
resolved first**, so a request rejected for a missing key in a later rule charges no earlier rule.

**1. One call (all-or-nothing).** When every key of every rule may go into one script call
(always on a standalone Redis; in a cluster when all keys hash to one slot -
`RedisTokenBucketStore.canEvaluateAtomically`) and no bucket key appears twice, the bands of all
rules are concatenated and evaluated by a single `token_bucket_consume.lua` call
(`consumeInOneCall`). The script's two passes become atomicity across rules. The band index in the
result points into the concatenated list and is mapped back to its rule.

**2. Compensation.** Otherwise the rules are charged one by one, and on a rejection (or a Redis
failure) the rules already charged are refunded by `token_bucket_refund.lua`.

```
A three-rule rule set in different slots where the third rule rejects:

Rule 1 (PER_IP)      → allowed, 1 token charged   → refunded
Rule 2 (PER_USER)    → allowed, 1 token charged   → refunded
Rule 3 (GLOBAL)      → rejected → earlier rules refunded, then return

The request gets a 429, and the remaining tokens of Rules 1 and 2 are what they were before it.
```

A refund only returns what is still there: TOKEN_BUCKET adds back up to capacity, SLIDING_WINDOW
decrements the sub-bucket of the charge time (the Redis TIME the consume script returns,
`BucketState.redisTimeMicros()`) but not below zero, and FIXED_WINDOW decrements only while the
counter still counts the window that was charged.

**What compensation still does not give you (honestly):**

1. Between charge and refund (one round trip per rule) concurrent requests see the earlier rule a
   permit lower. That errs on the strict side and heals itself with the refund.
2. If the refund cannot run (process death, Redis failure in between) the permit stays spent - the
   0.3.x outcome.
3. If a fixed window rolls over, or a sliding sub-bucket leaves the window, between charge and
   refund, there is nothing left to give back.
4. Finding the longest wait costs round trips: when rule `k` of `n` rejects, the request makes `k`
   consume calls, `k - 1` refunds and up to **8** check-only calls (`RedisTokenBucketStore.check`)
   for the rules after it, so the Retry-After is the longest wait of all rules. Rules beyond those 8
   are not consulted; a longer wait among them only shows up when the client retries.

If you need strict atomicity on a cluster, use **one rule with several bands**.

### Choosing the binding band

On the allowed path the result with the **fewest remaining tokens** is reported. In one call the
script itself returns that band; under compensation every rule is charged and compared:

```java
// RedisRateLimiter.java - actual code (consumeWithCompensation)
if (binding == null || state.remainingTokens() < binding.state.remainingTokens()) {
  binding = new Binding(call.key, call.rule, bindingBand, state);
}
```

A `Binding` is the (rule, key, band, state) tuple that is most restrictive at that moment.

```java
// RedisRateLimiter.java - actual code
/** The (rule, key, band, state) tuple currently considered the most restrictive. */
private static final class Binding {
  private final RateLimitKey key;
  private final RateLimitRule rule;
  private final RateLimitBand band;
  private final BucketState state;
}
```

The `X-RateLimit-Remaining` that goes out in the HTTP headers is this value. Reporting the most
generous band would make clients believe in a far larger quota than they have.

### Warning when the bucket TTL cap is shorter than the window

```java
// RedisRateLimiter.java - actual code
/**
 * Warns once per rule when the bucket TTL cap is shorter than one of its windows.
 *
 * <p>The Lua script clamps TOKEN_BUCKET and SLIDING_WINDOW TTLs to {@code
 * fluxgate.redis.max-bucket-ttl}, which means a bucket of a longer window can expire - and be
 * re-initialised full - before its window is over. That is the deliberate trade against letting
 * forgeable identity keys occupy Redis for weeks, but it changes what the rule enforces, so an
 * operator must be told rather than left to discover it. FIXED_WINDOW counters are exempt (they
 * expire at their window end), so they never trigger the warning.
 */
private void warnOnceIfBucketTtlClampsWindow(RateLimitRule rule, List<RateLimitBand> bands) {
  long capSeconds = tokenBucketStore.getMaxBucketTtl().getSeconds();

  for (RateLimitBand band : bands) {
    if (!ttlCapShortensWindow(band, capSeconds)) {
      continue;
    }
    long ttlSeconds = bucketTtlSeconds(band);

    String ruleId = rule.getId() != null ? rule.getId() : "unknown";
    if (ttlClampWarnedRules.add(ruleId)) {
      log.warn(
          "Rule '{}' band '{}' has a {}s window, whose bucket would need a {}s TTL, but "
              + "fluxgate.redis.max-bucket-ttl is {}s: the bucket expires early and the window is "
              + "effectively shortened. Raise max-bucket-ttl for this deployment, or use a scope "
              + "with bounded cardinality for long windows.",
          ruleId,
          band.getKeyLabel(),
          band.getWindow().getSeconds(),
          ttlSeconds,
          capSeconds);
    }
    return;
  }
}

/**
 * Tells whether {@code max-bucket-ttl} cuts the bucket of this band short. Only TOKEN_BUCKET and
 * SLIDING_WINDOW buckets are capped; a FIXED_WINDOW counter expires at its window end whatever
 * the cap.
 */
static boolean ttlCapShortensWindow(RateLimitBand band, long capSeconds) {
  return band.getAlgorithm() != RateLimitAlgorithm.FIXED_WINDOW
      && bucketTtlSeconds(band) > capSeconds;
}

/** The TTL the Lua script would give this band's bucket without the cap: window + 10%. */
private static long bucketTtlSeconds(RateLimitBand band) {
  return (long) Math.ceil(band.getWindow().getSeconds() * 1.1);
}
```

Run a 30-day-window rule with the default cap (7 days) and the bucket expires every 7 days; an
expired bucket is **re-created full** on the next request. What the rule actually enforces is
therefore not a 30-day limit — which is why this logs one WARN per rule instead of passing silently.

### Keys never appear in plaintext in logs

```java
// RedisRateLimiter.java - actual code
/** Masks a resolved key for logging: the first few characters, then {@code ***}. */
private static String mask(RateLimitKey key) {
  String value = key.value();
  if (value.length() <= MASK_VISIBLE_CHARS) {
    return "***";
  }
  return value.substring(0, MASK_VISIBLE_CHARS) + "***";
}
```

### close() closes nothing

```java
// RedisRateLimiter.java - actual code
/**
 * No-op, kept so that the limiter can be used in try-with-resources and as a Spring bean without
 * surprises.
 *
 * <p>This limiter owns neither the {@link RedisTokenBucketStore} nor the Redis connection behind
 * it: both are supplied by the caller and are closed by whoever created them (typically {@code
 * RedisRateLimiterConfig} or the Spring context). Closing them here would shut down a connection
 * other beans still use.
 */
@Override
public void close() {
  log.debug("RedisRateLimiter closed; the token bucket store and connection are not owned by it");
}
```

Closing resources you do not own is the more dangerous failure. If a limiter registered as a
`@Bean` tore down the Lettuce connection other beans still use at context shutdown, exceptions
would pour out depending on destruction order.

---

## 3. Bucket Key Layout

```java
// RedisRateLimiter.java - actual code
/**
 * Build the Redis key for a token bucket.
 *
 * <p>Format: {@code fluxgate:bucket:&#123;ruleSetId:ruleId:keyValue&#125;:bandKeyLabel}, plus
 * {@value #FIXED_WINDOW_KEY_SUFFIX} for a {@link RateLimitAlgorithm#FIXED_WINDOW} band, whose
 * counter is a hash that records its window. The suffix keeps those counters apart from the plain
 * string counters earlier 0.4 builds wrote under the bare name, which then age out on their own
 * expiry instead of failing with {@code WRONGTYPE}.
 *
 * <p>Example: {@code fluxgate:bucket:{api-limits:per-ip-rule:ip:192.168.1.100}:100-per-60s}
 *
 * <p>The braces are a Redis Cluster hash tag: only what is inside them is hashed, so every band
 * of one rule and key lands in the same slot and the multi-band Lua script can be atomic. The
 * band segment uses {@link RateLimitBand#getKeyLabel()}, which is derived from the band
 * configuration when no label was set - two unlabelled bands of one rule can no longer collide.
 *
 * @param ruleSetId ID of the rule set
 * @param ruleId ID of the rule
 * @param key Rate limit key (e.g. IP address, API key), already sanitised by the core
 * @param band Rate limit band
 * @return Redis key string
 */
private static String buildBucketKey(
    String ruleSetId, String ruleId, RateLimitKey key, RateLimitBand band) {

  // ruleSetId and ruleId are escaped so that Redis hash-tag delimiters (:, {, }) and SCAN glob
  // metacharacters (*, ?, [, ], \) embedded in operator-controlled strings cannot alter the key
  // namespace or the bucket key pattern - reversibly, so two ids can never share a bucket. The
  // band label is escaped too: a label "x:fw" must not land on the FIXED_WINDOW counter of a band
  // labelled "x". key.value() is already sanitised by the core.
  return BUCKET_KEY_PREFIX
      + "{"
      + sanitizeSegment(ruleSetId)
      + ":"
      + sanitizeSegment(ruleId)
      + ":"
      + key.value()
      + "}:"
      + escapeBandLabel(band.getKeyLabel())
      + (band.getAlgorithm() == RateLimitAlgorithm.FIXED_WINDOW ? FIXED_WINDOW_KEY_SUFFIX : "");
}
```

### Key shape

```
fluxgate:bucket:{api-limits:per-ip-rule:ip:192.168.1.100}:100-per-60s
└──── prefix ───┘└──────────── hash tag ────────────────┘└─ band label ─┘
```

Each of the three parts exists for a reason.

#### (1) The `fluxgate:bucket:` prefix

The bucket key space **does not overlap** with rule set definitions (`fluxgate:ruleset:*`,
`fluxgate:rulesets`).

```java
// RedisTokenBucketStore.java - actual code comment
/**
 * Deletes all token buckets (full reset).
 *
 * <p>This is used when a full reload is triggered to reset all rate limit state. The pattern is
 * {@code fluxgate:bucket:*}, which is disjoint from {@code fluxgate:ruleset:*} and {@code
 * fluxgate:rulesets} - a full reset can no longer destroy rule set definitions stored in Redis.
 */
```

The 0.3.x full-reset pattern was `fluxgate:*`, which **also deleted rule set definitions stored in
Redis.**

#### (2) The `{...}` hash tag

Redis Cluster hashes only what is inside the braces. Tagging `ruleSetId:ruleId:keyValue` puts
**every band of one rule + key into the same slot** — the precondition for a multi-key Lua script
to work in a cluster.

```java
// RedisTokenBucketStore.java - actual code comment
/**
 * <p>In cluster mode all keys must live in the same hash slot; {@link RedisRateLimiter} pins them
 * with a hash tag over {@code ruleSetId:ruleId:keyValue}.
 */
```

#### (3) The `band.getKeyLabel()` segment

```java
// RateLimitBand.java - actual code
public String getKeyLabel() {
  if (label != null && !label.trim().isEmpty()) {
    return label;
  }
  int nanoOfSecond = window.getNano();
  if (nanoOfSecond == 0) {
    return capacity + "-per-" + window.getSeconds() + "s";
  }
  if (nanoOfSecond % 1_000_000 == 0) {
    return capacity + "-per-" + window.toMillis() + "ms";
  }
  return capacity + "-per-" + window.toNanos() + "ns";
}
```

Without a label it is **derived from the configuration.** 0.3.x used `"default"` for unlabelled
bands, so two unlabelled bands of one rule **shared the same bucket** — meaning a 10/sec and a
100/min band overwrote each other in a single bucket.

### Segment sanitisation

`ruleSetId` and `ruleId` are operator-chosen strings; a `:` or `{` inside them would change the key
structure.

```java
// RedisRateLimiter.java - actual code
/**
 * Percent-escapes the characters that are unsafe in a bucket-key segment.
 *
 * <p>Unsafe characters are: {@code :}, {@code {}, {@code }}, {@code *}, {@code ?}, {@code [},
 * {@code ]}, {@code \}, any whitespace character, and {@code %} itself; each is replaced by the
 * {@code %XX} escapes of its UTF-8 bytes. Escaping {@code %} keeps the mapping reversible, so two
 * different ids can never share a bucket the way {@code a:b} and {@code a_b} did when unsafe
 * characters were replaced by an underscore. Ids without any of these characters - the usual case
 * - keep exactly the segment they had before.
 */
static String sanitizeSegment(String value) {
  if (value == null) {
    return "";
  }
  StringBuilder sb = null;
  for (int i = 0; i < value.length(); i++) {
    char c = value.charAt(i);
    if (isUnsafeSegmentChar(c)) {
      if (sb == null) {
        sb = new StringBuilder(value.length() + 8).append(value, 0, i);
      }
      appendEscaped(sb, c);
    } else if (sb != null) {
      sb.append(c);
    }
  }
  return sb == null ? value : sb.toString();
}

/**
 * Escapes the band label segment: {@code :} and {@code %} only, so that a label cannot imitate
 * the {@value #FIXED_WINDOW_KEY_SUFFIX} suffix of a FIXED_WINDOW counter while every other label
 * - including all derived ones such as {@code 100-per-60s} - keeps its key.
 */
static String escapeBandLabel(String label) {
  if (label.indexOf(':') < 0 && label.indexOf('%') < 0) {
    return label;
  }
  StringBuilder sb = new StringBuilder(label.length() + 8);
  for (int i = 0; i < label.length(); i++) {
    char c = label.charAt(i);
    if (c == ':' || c == '%') {
      appendEscaped(sb, c);
    } else {
      sb.append(c);
    }
  }
  return sb.toString();
}
```

**Reversible escaping rather than rejecting or replacing** is deliberate. The earlier scheme
replaced unsafe characters with `_`, so `a:b` and `a_b` shared one bucket. Percent-escaping (`%XX` of
the UTF-8 bytes, `%` included) is injective, so two different ids can never collide, while ids
without unsafe characters keep exactly the segment they had before.

The band label gets a narrower escape (`escapeBandLabel`: only `:` and `%`) so a label such as `x:fw`
cannot imitate the `:fw` suffix that a `FIXED_WINDOW` band appends to its key. Fixed-window counters
are hashes (they record their window), and the suffix keeps them apart from the plain string counters
earlier 0.4 builds wrote under the bare name.

### The SCAN pattern

```java
// RedisRateLimiter.java - actual code
/**
 * Returns the {@code SCAN MATCH} pattern that selects every token bucket of one rule set.
 *
 * <p>{@code ruleSetId} is first escaped exactly as in the bucket keys, so that embedded glob
 * metacharacters and hash-tag delimiters cannot corrupt the pattern, then any residual
 * metacharacters are backslash-escaped as a second line of defense. The pattern is anchored on
 * {@link #BUCKET_KEY_PREFIX} and can therefore never match {@code fluxgate:ruleset:*} or {@code
 * fluxgate:rulesets}.
 *
 * @return a glob pattern such as {@code fluxgate:bucket:&#123;api-limits:*}
 */
public static String bucketKeyPattern(String ruleSetId) {
  Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
  return BUCKET_KEY_PREFIX + "{" + escapeGlob(sanitizeSegment(ruleSetId)) + ":*";
}
```

Escape the id, then **escape the glob again** — defense in depth. Because the pattern is anchored on
`BUCKET_KEY_PREFIX`, it can never overlap the rule set definition keys.

---

## 4. Redis Connection Layer

### RedisConnectionProvider (interface)

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/connection/
└── RedisConnectionProvider.java
```

The abstraction layer unifying Standalone and Cluster modes.

```java
// RedisConnectionProvider.java - method list (comments condensed)
public interface RedisConnectionProvider extends AutoCloseable {

    RedisMode getMode();
    boolean isConnected();

    /** Load a Lua script (cluster: Lettuce broadcasts to every master node) */
    String scriptLoad(String script);

    /** Run a script with EVALSHA (efficient, uses the cached script) */
    <T> T evalsha(String sha, String[] keys, String[] args);

    /** Run a script with EVAL (for the NOSCRIPT fallback) */
    <T> T eval(String script, String[] keys, String[] args);

    // Hash commands
    boolean hset(String key, String field, String value);
    long hset(String key, Map<String, String> map);
    Map<String, String> hgetall(String key);

    long del(String... keys);

    /** UNLINK (Redis 4+). The default implementation delegates to del() */
    default long unlink(String... keys) {
        return del(keys);
    }

    // Set commands
    long sadd(String key, String... members);
    Set<String> smembers(String key);
    long srem(String key, String... members);

    boolean exists(String key);
    long ttl(String key);

    /** KEYS. Dangerous on a large keyspace */
    java.util.List<String> keys(String pattern);

    /** SCAN. The default implementation delegates to keys() */
    default java.util.List<String> scanKeys(String pattern, long count) {
        return keys(pattern);
    }

    /** SCAN, page by page; the default delivers scanKeys(pattern, count) as one page */
    default void scanKeys(String pattern, long count, Consumer<List<String>> pageConsumer) { ... }

    String flushdb();
    String ping();

    /** Cluster only */
    List<String> clusterNodes();

    @Override
    void close();

    enum RedisMode {
        STANDALONE,
        CLUSTER
    }
}
```

### Two methods added in 0.4: scanKeys and unlink

The bucket-reset path used to use `KEYS` and `DEL`, which was the problem.

```java
// RedisConnectionProvider.java - actual code Javadoc
/**
 * Incrementally scans keys matching the given pattern.
 *
 * <p>Unlike {@link #keys(String)}, this method is safe for production-sized keyspaces because it
 * uses Redis SCAN semantics instead of blocking the server for a full keyspace scan.
 */
default java.util.List<String> scanKeys(String pattern, long count) {
  return keys(pattern);
}
```

```java
// RedisConnectionProvider.java - actual code Javadoc
/**
 * Deletes one or more keys, reclaiming the memory in a background thread where the server
 * supports it.
 *
 * <p>{@code UNLINK} keeps a bulk delete off the Redis event loop, which matters when a rule
 * reload drops a large number of buckets at once. The default implementation delegates to {@link
 * #del(String...)} so that providers talking to a server older than Redis 4 keep working.
 */
default long unlink(String... keys) {
  return del(keys);
}
```

The bucket reset path uses the paged `scanKeys(pattern, count, pageConsumer)` overload and `UNLINK`s
each page as it arrives, so the keys of a large rule set are never held in memory at once.

| Command | Problem | Replacement |
|---------|---------|-------------|
| `KEYS pattern` | **blocks** single-threaded Redis for a full keyspace scan | `SCAN` (cursor-based, in batches) |
| `DEL k1..kn` | bulk delete reclaims memory **on the event loop** | `UNLINK` (background reclaim) |

Both are `default` methods, so a custom provider that overrides neither — or one talking to a server
older than Redis 4 — keeps working.

### StandaloneRedisConnection

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/connection/
└── StandaloneRedisConnection.java
```

Handles the connection to a single Redis node. Lettuce-based, using synchronous commands.

```java
// StandaloneRedisConnection.java - actual code (gist)
public class StandaloneRedisConnection implements RedisConnectionProvider {

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;

    @Override
    public RedisMode getMode() {
        return RedisMode.STANDALONE;
    }

    @Override
    public String scriptLoad(String script) {
        return commands.scriptLoad(script);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T evalsha(String sha, String[] keys, String[] args) {
        return (T) commands.evalsha(sha, ScriptOutputType.MULTI, keys, args);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T eval(String script, String[] keys, String[] args) {
        return (T) commands.eval(script, ScriptOutputType.MULTI, keys, args);
    }
}
```

`ScriptOutputType.MULTI` because the Lua script returns an array. The 0.4 consume script returns
eight integers (the eighth is the Redis `TIME` of the decision), the refund script one per band.

### ClusterRedisConnection

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/connection/
└── ClusterRedisConnection.java
```

```java
// ClusterRedisConnection.java - actual code (gist)
public class ClusterRedisConnection implements RedisConnectionProvider {

    private final RedisClusterClient clusterClient;
    private final StatefulRedisClusterConnection<String, String> connection;
    private final RedisAdvancedClusterCommands<String, String> commands;

    @Override
    public RedisMode getMode() {
        return RedisMode.CLUSTER;
    }

    @Override
    public String scriptLoad(String script) {
        // In cluster mode, scriptLoad broadcasts to all nodes automatically
        // Lettuce handles this via the cluster connection
        String sha = commands.scriptLoad(script);
        log.debug("Lua script loaded to cluster, SHA: {}", sha);
        return sha;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T evalsha(String sha, String[] keys, String[] args) {
        // Lettuce cluster client automatically routes EVALSHA to the correct node
        // based on the key's slot
        return (T) commands.evalsha(sha, ScriptOutputType.MULTI, keys, args);
    }
}
```

For a multi-key `EVALSHA` to hold in cluster mode, every key must live in the same slot. The hash
tag above is what guarantees that.

### RedisConnectionFactory

```java
// RedisConnectionFactory.java - actual code (excerpt)
public final class RedisConnectionFactory {

  /**
   * Creates a Redis connection based on the provided URI with custom timeout.
   *
   * @param uri single URI or comma-separated URIs for cluster
   * @param timeout connection timeout
   * @return the appropriate Redis connection provider
   */
  public static RedisConnectionProvider create(String uri, Duration timeout) {
    Objects.requireNonNull(uri, "uri must not be null");

    // Check if it's a cluster configuration (comma-separated nodes)
    if (RedisUriUtils.detectMode(uri) == RedisConnectionProvider.RedisMode.CLUSTER) {
      List<String> nodes = RedisUriUtils.splitNodes(uri);
      log.info("Detected cluster mode with {} nodes", nodes.size());
      return new ClusterRedisConnection(nodes, timeout);
    }

    log.info("Using standalone mode");
    return new StandaloneRedisConnection(uri, timeout);
  }

  /**
   * Creates a Redis connection with explicit mode selection.
   *
   * @param mode the desired Redis mode
   * @param uris the Redis URIs (single for standalone, multiple for cluster)
   * @param timeout connection timeout
   * @return the appropriate Redis connection provider
   */
  public static RedisConnectionProvider create(
      RedisConnectionProvider.RedisMode mode, List<String> uris, Duration timeout) { ... }
}
```

The mode is not decided by "does the URI contain a comma". `RedisUriUtils` treats a URI as a
cluster only when it carries more than one URI scheme or a comma outside the credentials, so
`redis://:pa,ss@host:6379` - a password with a comma - stays standalone, and a comma inside a password never
tears a node in two when the nodes are split.

```java
// RedisUriUtils.java - actual code
public static RedisMode detectMode(String uri) {
  return RedisUris.isCluster(uri) ? RedisMode.CLUSTER : RedisMode.STANDALONE;
}

public static List<String> splitNodes(String uri) {
  return RedisUris.splitNodes(uri);
}
```

### RedisUriUtils: URI masking

Connection failure messages and initialization logs otherwise carry the URI verbatim. A dedicated
utility keeps the credentials of `redis://user:secret@host` from leaking into logs.

```java
// RedisRateLimiterConfig.java - actual code
public RedisRateLimiterConfig(String redisUri, Duration timeout, Duration maxBucketTtl) {
  this(
      () ->
          RedisConnectionFactory.create(
              Objects.requireNonNull(redisUri, "redisUri must not be null"),
              Objects.requireNonNull(timeout, "timeout must not be null")),
      maxBucketTtl,
      redisUri == null ? null : RedisUriUtils.mask(redisUri));
}
```

`LazyRedisRateLimiter` addresses the same concern.

```java
// LazyRedisRateLimiter.java - actual code
/**
 * Returns the most specific message available for a connection failure, with credentials removed.
 *
 * <p>Driver failures happily echo the URI they were given, which may carry a password. The
 * message ends up in the health endpoint and in every {@link RedisUnavailableException}, so it is
 * masked here rather than at each reader.
 */
private static String rootMessage(Throwable t) { ... }

/** Replaces URI credentials and {@code password=} values with a placeholder. */
private static String mask(String message) {
  String masked = URI_CREDENTIALS.matcher(message).replaceAll("://***@");
  return SECRET_PARAMETER.matcher(masked).replaceAll("$1=***");
}
```

### Standalone vs Cluster comparison

| Aspect | Standalone | Cluster |
|--------|-----------|---------|
| Nodes | 1 | 3+ (6 recommended) |
| Data distribution | none | hash slots (16384) |
| High availability | manual failover | automatic failover |
| Script load | a single node | every master node (Lettuce broadcast) |
| EVALSHA routing | n/a | automatic, by the key's hash slot |
| Multi-key scripts | unconstrained | all keys in one slot → hash tag required |
| URI form | `redis://host:6379` | `redis://node1:6379,redis://node2:6379,...` |

---

## 5. RedisRateLimiterConfig

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/config/
└── RedisRateLimiterConfig.java
```

```java
// RedisRateLimiterConfig.java - actual code
/**
 * Configuration entry point for the Redis-based rate limiter.
 *
 * <p>This class handles:
 *
 * <ul>
 *   <li>Redis connection setup (both Standalone and Cluster modes)
 *   <li>Loading the Lua scripts into that Redis
 *   <li>TokenBucketStore initialization
 * </ul>
 *
 * <p><strong>Ownership.</strong> A connection this class created is closed by {@link #close()}. A
 * connection handed in through {@link #RedisRateLimiterConfig(RedisConnectionProvider)} belongs to
 * the caller and is left open, so closing this config never shuts down a Lettuce client other parts
 * of the application still use.
 */
@SuppressWarnings("deprecation") // still exposes the deprecated RedisRuleSetStore
public final class RedisRateLimiterConfig implements AutoCloseable {

  private final RedisConnectionProvider connectionProvider;
  private final RedisTokenBucketStore tokenBucketStore;
  private final RedisRuleSetStore ruleSetStore;
  private final boolean ownsConnectionProvider;

  public RedisRateLimiterConfig(String redisUri, Duration timeout, Duration maxBucketTtl) {
    this(
        () ->
            RedisConnectionFactory.create(
                Objects.requireNonNull(redisUri, "redisUri must not be null"),
                Objects.requireNonNull(timeout, "timeout must not be null")),
        maxBucketTtl,
        redisUri == null ? null : RedisUriUtils.mask(redisUri));
  }

  /**
   * Creates a config that owns the connection the given connector opens.
   *
   * <p>{@code maxBucketTtl} is validated before the connector runs, and the connection is closed
   * again when the stores cannot be built, so a failed construction never leaves a Lettuce client
   * (and its event loop threads) behind.
   */
  RedisRateLimiterConfig(
      Supplier<RedisConnectionProvider> connector, Duration maxBucketTtl, String endpoint) {
    validateMaxBucketTtl(maxBucketTtl);

    RedisConnectionProvider provider = connector.get();
    try {
      this.tokenBucketStore = new RedisTokenBucketStore(provider, maxBucketTtl);
      this.ruleSetStore = new RedisRuleSetStore(provider);
    } catch (RuntimeException | Error e) {
      try {
        provider.close();
      } catch (RuntimeException closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }
    this.connectionProvider = provider;
    this.ownsConnectionProvider = true;

    logInitialized(endpoint);
  }

  /**
   * Create a new RedisRateLimiterConfig with an existing connection provider and a TTL cap.
   *
   * <p>The provider is <em>not</em> closed by {@link #close()}.
   */
  public RedisRateLimiterConfig(RedisConnectionProvider connectionProvider, Duration maxBucketTtl) {
    this.connectionProvider =
        Objects.requireNonNull(connectionProvider, "connectionProvider must not be null");
    this.ownsConnectionProvider = false;
    this.tokenBucketStore = new RedisTokenBucketStore(connectionProvider, maxBucketTtl);
    this.ruleSetStore = new RedisRuleSetStore(connectionProvider);

    logInitialized("externally managed connection");
  }

  private void logInitialized(String endpoint) {
    log.info(
        "FluxGate Redis rate limiter initialized ({} mode, {})",
        connectionProvider.getMode(),
        endpoint);
  }

  public RedisTokenBucketStore getTokenBucketStore() {
    return tokenBucketStore;
  }

  /**
   * Whether {@link #close()} closes the connection provider.
   *
   * @return true when this config created the connection itself
   */
  public boolean ownsConnectionProvider() {
    return ownsConnectionProvider;
  }
}
```

### The initialization flow changed in 0.4

0.3.x required **explicitly calling** `LuaScriptLoader.loadScripts(connectionProvider)` before
creating the `RedisTokenBucketStore`, and the store constructor checked "were the scripts loaded?"
and threw `IllegalStateException`. Get the order wrong and it blew up at runtime.

In 0.4 the store loads its own scripts.

```java
// RedisTokenBucketStore.java - actual code (end of the constructor)
if (!scripts.isLoaded()) {
  scripts.loadInto(connectionProvider);
}
```

```
RedisRateLimiterConfig created (URI or mode + uris)
         |
         v
+--------------------------------------+
| (0) validate maxBucketTtl (>= 1s)    |
|     before any connection is opened  |
+--------------------------------------+
         |
         v
+--------------------------------------+
| (1) RedisConnectionFactory.create    |
|     - RedisUriUtils.detectMode /     |
|       splitNodes                     |
|     - Standalone or Cluster connect  |
+--------------------------------------+
         |
         v
+--------------------------------------+
| (2) new RedisTokenBucketStore(...)   |
|     - create LuaScriptRegistry       |
|       (reads .lua from the classpath)|
|     - validate maxBucketTtl (>= 1s)  |
|     - if isLoaded() is false,        |
|       loadInto() → SCRIPT LOAD → SHA |
+--------------------------------------+
         |
         v
+--------------------------------------+
| (3) new RedisRuleSetStore(...)       |
|     - @Deprecated 0.4.0              |
+--------------------------------------+

If (2) or (3) throws, the connection opened in (1) is closed again.
```

### ownsConnectionProvider: ownership made explicit

| Construction path | `ownsConnectionProvider` | `close()` behavior |
|-------------------|--------------------------|-------------------|
| created from a URI or mode+uris | `true` | also closes the connection |
| handed an existing `RedisConnectionProvider` | `false` | leaves the connection **open** |

Without this distinction, closing a config after handing it an externally managed Lettuce client
would destroy the connection other beans were still using.

### maxBucketTtl

```java
// RedisTokenBucketStore.java - actual code
/**
 * Default upper bound on a bucket TTL.
 *
 * <p>A bucket normally lives one window plus 10%. Without a cap, a rule with a 30 day window
 * keeps one Redis hash per distinct key alive for 33 days - and identity keys are cheap to forge,
 * so the keyspace an attacker can pin down is bounded only by the window. Seven days keeps long
 * windows working while bounding that; raise it deliberately if you rate limit over longer
 * periods with a scope whose cardinality you control.
 */
public static final Duration DEFAULT_MAX_BUCKET_TTL = Duration.ofDays(7);
```

Pass `null` and the 7-day default applies; anything under one second is rejected by the
constructor.

```java
Duration effectiveTtl = maxBucketTtl != null ? maxBucketTtl : DEFAULT_MAX_BUCKET_TTL;
if (effectiveTtl.getSeconds() < 1) {
  throw new IllegalArgumentException("maxBucketTtl must be at least 1 second");
}
```

### RedisRuleSetStore is on its way out

```java
// RedisRateLimiterConfig.java - actual code
/**
 * @deprecated the Redis rule set store cannot express the core rule model; see {@link
 *     RedisRuleSetStore}
 */
@Deprecated(since = "0.4.0")
public RedisRuleSetStore getRuleSetStore() {
  return ruleSetStore;
}
```

Manage rule sets with the MongoDB adapter (or a `RateLimitRuleSetProvider` you implement yourself).

---

## 6. Usage Examples

### Standalone mode

```java
// Connect to a single Redis server (timeout 5s, bucket TTL cap 7 days)
RedisRateLimiterConfig config =
    new RedisRateLimiterConfig("redis://localhost:6379", Duration.ofSeconds(5), null);

// Create the RateLimiter
RedisRateLimiter rateLimiter = new RedisRateLimiter(config.getTokenBucketStore());

// Rate limit
RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

if (result.isAllowed()) {
    // request allowed. result.getRemainingTokens() is the binding band's remaining tokens
} else {
    // request rejected. Retry after result.getNanosToWaitForRefill()
}

// Clean up on shutdown (this config owns the connection, so it is closed too)
config.close();
```

### Cluster mode

```java
// Comma-separated node URIs (Cluster mode auto-detected)
String clusterUri = "redis://node1:6379,redis://node2:6379,redis://node3:6379";
RedisRateLimiterConfig autoDetected =
    new RedisRateLimiterConfig(clusterUri, Duration.ofSeconds(5), null);

// Or explicit Cluster mode
RedisRateLimiterConfig explicit = new RedisRateLimiterConfig(
    RedisMode.CLUSTER,
    List.of("redis://node1:6379", "redis://node2:6379", "redis://node3:6379"),
    Duration.ofSeconds(5),
    null);

// Usage from here is identical
RedisRateLimiter rateLimiter = new RedisRateLimiter(explicit.getTokenBucketStore());
```

### Bucket reset (on rule changes)

```java
RedisTokenBucketStore store = config.getTokenBucketStore();

// Only one rule set's buckets (SCAN + UNLINK, pattern from RedisRateLimiter.bucketKeyPattern)
long deleted = store.deleteBucketsByRuleSetId("api-limits");

// Every bucket (pattern "fluxgate:bucket:*" — rule set definitions untouched)
long allDeleted = store.deleteAllBuckets();
```

### Spring Boot integration

```yaml
# application.yml
fluxgate:
  redis:
    enabled: true
    mode: auto             # auto | standalone | cluster (auto detects from the URI)
    uri: redis://localhost:6379  # Standalone
    # uri: redis://node1:6379,redis://node2:6379,redis://node3:6379  # Cluster
    timeout-ms: 5000
    fail-fast: false      # true fails the boot when Redis is unavailable
    max-bucket-ttl: 7d    # caps TOKEN_BUCKET / SLIDING_WINDOW bucket TTLs (not FIXED_WINDOW)
```

```java
// Spring Boot auto-configuration creates the beans
@Autowired
private RateLimiter rateLimiter;  // the ResilientRateLimiter (@Primary) is injected
```

The injected `RateLimiter` is **not the raw `RedisRateLimiter`.** The starter wires this chain:

```
ResilientRateLimiter        @Primary  — retry + circuit breaker + degradation
        v
LazyRedisRateLimiter                  — request threads never connect
        v
RedisRateLimiter → RedisTokenBucketStore → Lua
```

### Why fail-fast=false is the default

```java
// LazyRedisRateLimiter.java - actual code Javadoc
/**
 * <p>Rate limiting is an auxiliary concern, so a Redis outage during a rollout must not turn into
 * an application crash loop. One connection attempt is made when this limiter is created; if it
 * fails the failure is logged and a reconnect is scheduled (starting at the configured interval and
 * backing off to a cap), while calls fail with {@link RedisUnavailableException} so the configured
 * {@code fluxgate.ratelimit.failure-behavior} - or the in-memory fallback of {@link
 * ResilientRateLimiter} - decides what happens to the request. Set {@code
 * fluxgate.redis.fail-fast=true} to restore the eager connect that aborts the boot instead.
 *
 * <p><b>Request threads never connect.</b> Connecting is a blocking socket operation behind a
 * single lock, so letting {@link #tryConsume} do it turned one unreachable Redis into a thread pool
 * exhaustion: every worker queued on the lock for the connect timeout, and a retryable exception on
 * top multiplied that by the retry count. Only the constructor and the background reconnect task
 * ever call {@link #connect()}, which also means request traffic cannot advance the backoff. The
 * reconnect task re-arms itself after every failure and stops only on {@link #close()}.
 */
```

The key point is that request threads never attempt to connect. One unreachable Redis no longer
escalates into **thread pool exhaustion.**

```java
// LazyRedisRateLimiter.java - actual code
@Override
public RateLimitResult tryConsume(
    RequestContext context, RateLimitRuleSet ruleSet, long permits) {
  return connectedDelegate().tryConsume(context, ruleSet, permits);
}

// the 4-argument overload (with a PathPatternMatcher) delegates the same way

private RateLimiter connectedDelegate() {
  RateLimiter delegate = delegateRef.get();
  if (delegate == null) {
    // Deliberately does not connect: see the class Javadoc. The exception is non-retryable so the
    // retry executor hands it straight to the configured degradation.
    throw new RedisUnavailableException(
        "FluxGate Redis rate limiter is not connected yet: " + lastErrorMessage.get());
  }
  return delegate;
}
```

Connection state is exposed through `RedisConnectionState`, so the health endpoint can report the
limiter as DEGRADED.

### Health check and Redis permissions

`RedisHealthCheckerImpl` sends `PING` in every mode. In cluster mode it also runs **`CLUSTER NODES`**
(node, master and replica counts) and **`CLUSTER INFO`** (`cluster_state`, `cluster_slots_fail`),
and reports the cluster DOWN when either fails or returns nothing. With Redis ACLs, the health
check's user therefore needs `ping`, `cluster|nodes` and `cluster|info` on top of what the limiter
itself uses (the scripting commands, and the read/write commands and `TIME` its Lua scripts call -
ACLs are checked inside scripts too), for example:

```
ACL SETUSER fluxgate on >secret ~fluxgate:* +@read +@write +@scripting +time +ping \
    +cluster|nodes +cluster|info
```

Without the two cluster subcommands a healthy cluster is reported DOWN with `cluster_state unknown
(CLUSTER INFO failed or returned nothing)` or `no cluster nodes reported`.

---

## Related Documentation

- [Storage Layer](storage-layer.md) - RedisTokenBucketStore, the Lua script in detail
- [RateLimiter Layer](ratelimiter-layer.md) - algorithm and full Lua analysis
- [Handler Layer](handler-layer.md) - wiring of the resilience chain
- [Architecture Overview](README.md)
