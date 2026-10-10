# FluxGate Core

[![Java Version](https://img.shields.io/badge/Java-11%2B-orange.svg)](https://openjdk.java.net/)
[![Build](https://github.com/OpenFluxGate/fluxgate/actions/workflows/maven-ci.yml/badge.svg)](https://github.com/OpenFluxGate/fluxgate/actions)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](../LICENSE)
[![Bucket4j](https://img.shields.io/badge/Bucket4j-8.15.0-purple.svg)](https://github.com/bucket4j/bucket4j)

The framework-independent rate limiting model and in-process limiter of FluxGate. Its only runtime
dependencies are Bucket4j, Caffeine and the SLF4J API; it has no dependency on Spring, a servlet API
or a data store.

---

## What the module contains

- **Rule model**: `RateLimitRuleSet` groups `RateLimitRule`s; a rule has one or more
  `RateLimitBand`s (window + capacity), a `LimitScope`, an `OnLimitExceedPolicy`, a priority and a
  `RuleMatcher` (path, method and header matching).
- **`RateLimiter` interface** and its in-process implementation, `Bucket4jRateLimiter`.
- **Key resolution**: the `KeyResolver` strategy, the scope-based `LimitScopeKeyResolver` and
  `RateLimitKey`, which sanitises every key value.
- **`RateLimitMetricsRecorder`** hook (plus `CompositeMetricsRecorder` to fan out to several).
- **`RateLimitEngine`**: resolves a rule set by id through a `RateLimitRuleSetProvider`, applies the
  rule set's access control and delegates to a `RateLimiter`.
- **Hot-reload building blocks** (`org.fluxgate.core.reload`), **retry / circuit breaker**
  primitives (`org.fluxgate.core.resilience`) and the `FluxgateRateLimitHandler` /
  `RateLimitResponse` types used by the Spring Boot starters.

Other backends are separate modules of this repository:

| Need | Module |
|------|--------|
| Distributed limiting on Redis (standalone or Cluster, Lua scripts) | [`fluxgate-redis-ratelimiter`](../fluxgate-redis-ratelimiter/README.md) |
| Rule storage in MongoDB | [`fluxgate-mongo-adapter`](../fluxgate-mongo-adapter/README.md) |
| Spring Boot auto-configuration, servlet filter, Micrometer metrics | [`fluxgate-spring-boot3-starter`](../fluxgate-spring-boot3-starter/README.md) / [`fluxgate-spring-boot2-starter`](../fluxgate-spring-boot2-starter/README.md) |

Any other store (Hazelcast, a database, ...) needs your own `RateLimiter` implementation.

---

## Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-core</artifactId>
    <version>0.4.0</version>
</dependency>
```

`0.4.0` is the upcoming release (see `[Unreleased]` in [CHANGELOG.md](../CHANGELOG.md)); a build
from this branch produces `0.4.0-SNAPSHOT`.

### Basic Usage

```java
import java.time.Duration;
import java.util.List;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;

// 1. Define a rule: 100 requests per minute per client IP
RateLimitRule rule = RateLimitRule.builder("api-rate-limit")
    .name("API Rate Limit")
    .scope(LimitScope.PER_IP)
    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100)
        .label("100-per-minute")
        .build())
    .build();

// 2. Group rules into a rule set. LimitScopeKeyResolver turns the rule's scope into a key
//    ("ip:192.168.1.100" here); a request without a client IP uses the key "ip:unknown".
RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("my-api-limiter")
    .description("Limit API requests by IP")
    .rules(List.of(rule))
    .keyResolver(new LimitScopeKeyResolver())
    .build();

// 3. Create the in-process limiter (keep one instance; it owns the buckets)
RateLimiter rateLimiter = new Bucket4jRateLimiter();

// 4. Check a request
RequestContext context = RequestContext.builder()
    .clientIp("192.168.1.100")
    .endpoint("/api/data")
    .method("GET")
    .build();

RateLimitResult result = rateLimiter.tryConsume(context, ruleSet);

if (result.isAllowed()) {
    System.out.println("Request allowed. Remaining: " + result.getRemainingTokens());
} else {
    // getNanosToWaitForRefill() is -1 when unknown and 0 for a request rejected because no key
    // could be resolved. Round up and never advertise less than 1 second, as the starters'
    // Retry-After header does.
    long waitNanos = result.getNanosToWaitForRefill();
    long retryAfterSeconds = 1;
    if (waitNanos > 0) {
        long seconds = waitNanos / 1_000_000_000L + (waitNanos % 1_000_000_000L == 0 ? 0 : 1);
        retryAfterSeconds = Math.max(1, seconds);
    }
    System.out.println("Rate limit exceeded. Retry after: " + retryAfterSeconds + " seconds");
}
```

A request that matches no enabled rule is allowed with `getMatchedRule() == null` and
`getRemainingTokens() == -1`.

---

## Architecture

```
  Your code, or a FluxGate Spring Boot starter
        │  RequestContext + RateLimitRuleSet
        ▼
  RateLimiter (interface)
   ├─ Bucket4jRateLimiter   in-process, this module
   └─ RedisRateLimiter      fluxgate-redis-ratelimiter
        │  for every matching rule of the rule set:
        │   ├─ KeyResolver           RequestContext + rule -> RateLimitKey (bucket)
        │   └─ bands of the rule     checked and charged together
        │
        └─ RateLimitMetricsRecorder  called once per call with the final decision
                                     (Bucket4j: after the buckets' locks are released)
```

`RateLimitEngine` can sit in front of the limiter when rule sets are looked up by id. Its own
access-control and missing-rule-set decisions are returned without calling the limiter, so they do
not reach the recorder (see [Auditing Decisions](#auditing-decisions)).

### Component Overview

| Component | Purpose | Extensible |
|-----------|---------|------------|
| `RateLimiter` | Rate limiting interface | Yes (interface) |
| `Bucket4jRateLimiter` | In-process implementation | Class (not final); wrap it to add behaviour |
| `RateLimitRuleSet` | Rules + key resolver + optional metrics recorder and access control | No (final) |
| `RateLimitRule` | One rule: bands, scope, policy, priority, matcher | No (final) |
| `RateLimitBand` | Window + capacity (+ algorithm) | No (final) |
| `KeyResolver` | Maps a request and a rule to a `RateLimitKey` | Yes (interface) |
| `RateLimitMetricsRecorder` | Called with every decision the limiter returns (not with `RateLimitEngine`'s own access-control or missing-rule-set decisions) | Yes (interface) |
| `RequestContext` | Request metadata (IP, user, API key, endpoint, method, headers, attributes) | No (final) |
| `RateLimitResult` | Decision, remaining tokens, wait time, matched rule, band, policy | No (final) |

---

## Advanced Usage

### Several limits on one request

Put the limits into **one rule set** as separate rules. Every matching rule is evaluated and the
request is allowed only if all of them allow it. `Bucket4jRateLimiter` checks all buckets under
their locks before charging any, so a request rejected by one rule costs the others nothing.
`RedisRateLimiter` does the same in one Lua call on a standalone Redis or when all keys share a
cluster slot; across cluster slots it charges rule by rule and refunds on rejection (not atomically,
see its [README](../fluxgate-redis-ratelimiter/README.md)).

```java
// 100 requests/minute per IP AND 10,000 requests/10 minutes in total
RateLimitRule perIp = RateLimitRule.builder("per-ip")
    .scope(LimitScope.PER_IP)
    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).build())
    .build();

RateLimitRule serviceWide = RateLimitRule.builder("service-wide")
    .scope(LimitScope.GLOBAL)
    .addBand(RateLimitBand.builder(Duration.ofMinutes(10), 10_000).build())
    .build();

RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("ip-and-service")
    .rules(List.of(perIp, serviceWide))
    .keyResolver(new LimitScopeKeyResolver())
    .build();

RateLimitResult result = rateLimiter.tryConsume(context, ruleSet);
// when rejected, result.getMatchedRule() is the rejecting rule
```

Calling `tryConsume` once per rule set instead also works, but then each call charges its own
buckets: a request allowed by the first rule set and rejected by the second has still used a token
of the first. `MultiLevelRateLimitTest` shows that sequential style.

### Custom Key Resolution Strategy

A `RateLimitRuleSet` has one `KeyResolver` for all its rules. `LimitScopeKeyResolver` derives the
key from each rule's `LimitScope`; a custom resolver decides on its own and the rule's scope is then
only metadata unless your resolver reads it.

Prefix every component, as composite `CUSTOM` keys do (`custom:ip:10.0.0.1:user:u-1`), and use
`RateLimitKey.of(prefix, rawValue)` so the leading prefix survives sanitisation. `:` is kept by
sanitisation, so put the one free-form component last and keep the others `:`-free (or length-prefix
them, see [Key resolver](../docs/en/customization/key-resolver.md)). Check every component for
`null` before concatenating it: `region + ":user:" + null` is the text `...:user:null`, one bucket
shared by every request without a user. `RateLimitKey.of` also rejects a `null` value, so the
examples fall back to the client IP.

```java
// Example: Region + User ID composite key -> "region:eu:user:alice"
KeyResolver regionUserResolver = (ctx, rule) -> {
    String region = (String) ctx.getAttribute("region"); // set by your own code, no ':'
    String userId = ctx.getUserId();
    if (region == null || region.isEmpty() || userId == null || userId.isEmpty()) {
        String ip = ctx.getClientIp();
        return RateLimitKey.of("ip:", ip != null && !ip.isEmpty() ? ip : "unknown");
    }
    return RateLimitKey.of("region:", region + ":user:" + userId);
};

// Example: Tenant + Endpoint key -> "tenant:h:acme:endpoint:_api_orders:<16 hex>"
// ('/' is outside the key charset, so the value is encoded; the tenant: prefix stays)
KeyResolver tenantEndpointResolver = (ctx, rule) -> {
    String tenantId = (String) ctx.getAttribute("tenantId"); // set by your own code, no ':'
    String endpoint = ctx.getEndpoint();
    if (tenantId == null || tenantId.isEmpty() || endpoint == null) {
        String ip = ctx.getClientIp();
        return RateLimitKey.of("ip:", ip != null && !ip.isEmpty() ? ip : "unknown");
    }
    return RateLimitKey.of("tenant:", tenantId + ":endpoint:" + endpoint);
};
```

### Multiple Time Windows (Bands)

All bands of one rule share one Bucket4j bucket and are checked and charged together:

```java
RateLimitRule multiWindowRule = RateLimitRule.builder("multi-window")
        .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10).build())   // 10/sec
        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).build())  // 100/min
        .addBand(RateLimitBand.builder(Duration.ofHours(1), 1000).build())   // 1000/hour
        .addBand(RateLimitBand.builder(Duration.ofDays(1), 10000).build())   // 10k/day
        .build();
```

`Bucket4jRateLimiter` refills `TOKEN_BUCKET` bands (the default) continuously and approximates
`SLIDING_WINDOW` / `FIXED_WINDOW` bands with a full refill at the end of each window; it does not
apply calendar alignment (`QuotaPeriod`). Use the Redis limiter for those algorithms.

### Consume Multiple Permits

```java
// Heavy operation consumes 5 permits
RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 5);
```

`permits` must be positive and no larger than the capacity of every band of a matching rule;
otherwise `Bucket4jRateLimiter` throws (`IllegalArgumentException` / `InvalidRuleConfigException`).

---

## Configuration

### Limit Scopes

How `LimitScopeKeyResolver` maps each scope to a key. The default scope of
`RateLimitRule.builder(...)` is `PER_API_KEY`.

| Scope | Key | Use Case |
|-------|-----|----------|
| `GLOBAL` | `global` (one bucket per rule for all requests) | Global throttling |
| `PER_IP` | `ip:<client IP>` | Per-client-IP limits |
| `PER_USER` | `user:<user id>` | Per-user quotas |
| `PER_API_KEY` | `key:<API key>` | API key limits |
| `CUSTOM` | `custom:<request attribute named by the rule's keyStrategyId>` | Keys your own code puts into the context |

When the value a scope needs is missing, the resolver falls back to the client IP
(`MissingKeyBehavior.FALLBACK_TO_IP`, the default) or, with
`new LimitScopeKeyResolver(MissingKeyBehavior.REJECT)`, rejects the request.

### Policies

| Policy | Behavior |
|--------|----------|
| `REJECT_REQUEST` | Default. The rejection is returned to the caller. |
| `WAIT_FOR_REFILL` | Core only reports the policy on the `RateLimitResult`; the caller decides whether to wait. The Spring Boot starters' filter can wait and retry, see [WAIT_FOR_REFILL Policy](../fluxgate-spring-boot3-starter/README.md#wait_for_refill-policy). |

---

## Testing

```bash
# Unit tests of this module (no Docker needed)
./mvnw -pl fluxgate-core test

# A single test class
./mvnw -pl fluxgate-core test -Dtest=Bucket4jRateLimiterTest

# Feature walk-through
./mvnw -pl fluxgate-core test -Dtest=FeatureDemoTest

# Sequential multi-level checks
./mvnw -pl fluxgate-core test -Dtest=MultiLevelRateLimitTest
```

`FeatureDemoTest` covers IP, API key, user and global limits, multiple bands, metrics recording, a
custom key resolver, multiple permits and the wait-time information of a rejection.

---

## Use Cases

### 1. Public API Protection

```java
// 100 requests/minute per IP
RateLimitRule publicApiRule = RateLimitRule.builder("public-api")
        .scope(LimitScope.PER_IP)
        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).build())
        .build();
```

### 2. Tiered API Access

Buckets are kept per rule set and rule, so give each tier its own rule set and choose it from a tier your
own code has established (never from a value the client can set):

```java
// Free tier: 1000 requests/day, Pro tier: 100,000 requests/day, per API key
RateLimitRuleSet freeTier = RateLimitRuleSet.builder("free-tier")
        .rules(List.of(RateLimitRule.builder("free-daily")
                .scope(LimitScope.PER_API_KEY)
                .addBand(RateLimitBand.builder(Duration.ofDays(1), 1000).build())
                .build()))
        .keyResolver(new LimitScopeKeyResolver())
        .build();

RateLimitRuleSet proTier = RateLimitRuleSet.builder("pro-tier")
        .rules(List.of(RateLimitRule.builder("pro-daily")
                .scope(LimitScope.PER_API_KEY)
                .addBand(RateLimitBand.builder(Duration.ofDays(1), 100_000).build())
                .build()))
        .keyResolver(new LimitScopeKeyResolver())
        .build();

boolean pro = "pro".equals(context.getAttribute("tier")); // attribute set by your own code
RateLimitResult result = rateLimiter.tryConsume(context, pro ? proTier : freeTier);
```

A request without an API key falls back to its client IP (see Limit Scopes).

### 3. Multi-Tenant SaaS

```java
// Per-tenant rate limiting
KeyResolver tenantResolver = (ctx, rule) -> {
    String tenantId = (String) ctx.getAttribute("tenantId");
    if (tenantId == null || tenantId.isEmpty()) {
        // RateLimitKey.of rejects a null value: limit a request without a tenant by client IP
        String ip = ctx.getClientIp();
        return RateLimitKey.of("ip:", ip != null && !ip.isEmpty() ? ip : "unknown");
    }
    return RateLimitKey.of("tenant:", tenantId);
};

RateLimitRule tenantRule = RateLimitRule.builder("tenant-limit")
        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 1000).build())  // 1000/min per tenant
        .addBand(RateLimitBand.builder(Duration.ofHours(1), 50000).build())   // 50k/hour per tenant
        .build();
```

### 4. Global + Per-IP Limits

A `GLOBAL` rule and a `PER_IP` rule in one rule set, exactly as in
[Several limits on one request](#several-limits-on-one-request). An in-process limiter only
protects the application it runs in; it is not a substitute for network-level DDoS protection.

---

## Extending FluxGate

### Custom RateLimiter Implementations

Implement `org.fluxgate.core.ratelimiter.RateLimiter` for another store. Only
`tryConsume(RequestContext, RateLimitRuleSet, long)` is abstract; also override the overload that
takes a `PathPatternMatcher` if your implementation filters rules with
`RateLimitRuleSet.getMatchingRules(context, matcher)`. A Redis implementation already exists in
[`fluxgate-redis-ratelimiter`](../fluxgate-redis-ratelimiter/README.md).

To add behaviour around an existing limiter, wrap it:

```java
public class LoggingRateLimiter implements RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(LoggingRateLimiter.class);
    private final RateLimiter delegate;

    public LoggingRateLimiter(RateLimiter delegate) {
        this.delegate = delegate;
    }

    @Override
    public RateLimitResult tryConsume(RequestContext context, RateLimitRuleSet ruleSet, long permits) {
        return logRejection(delegate.tryConsume(context, ruleSet, permits));
    }

    @Override
    public RateLimitResult tryConsume(
            RequestContext context, RateLimitRuleSet ruleSet, long permits, PathPatternMatcher matcher) {
        return logRejection(delegate.tryConsume(context, ruleSet, permits, matcher));
    }

    private static RateLimitResult logRejection(RateLimitResult result) {
        if (!result.isAllowed()) {
            RateLimitRule rule = result.getMatchedRule();
            log.info("Rejected by rule {}", rule != null ? rule.getId() : "-");
        }
        return result;
    }
}
```

📖 **See also**: [Key Resolver](../docs/en/customization/key-resolver.md) and [Request Context](../docs/en/customization/request-context.md)

### Custom Metrics Recorder

The Spring Boot starters already register a Micrometer recorder (`fluxgate.requests`,
`fluxgate.requests.duration`, `fluxgate.tokens.remaining`; see
[Prometheus Metrics](../fluxgate-spring-boot3-starter/README.md#prometheus-metrics)). Without a
starter, attach your own recorder to the rule set. This example uses Micrometer
(`io.micrometer:micrometer-core`, which you add yourself; core does not depend on it) and tags only
by result and rule id, which come from your configuration, so the number of series stays bounded.
Do not tag with the raw endpoint or any other request value.

```java
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;

MeterRegistry registry = ...; // e.g. a PrometheusMeterRegistry

RateLimitMetricsRecorder micrometerRecorder = (ctx, result) -> {
    String rule = result.getMatchedRule() != null ? result.getMatchedRule().getId() : "none";
    registry.counter("app.ratelimit.decisions",
            "result", result.isAllowed() ? "allowed" : "rejected",
            "rule", rule)
        .increment();

    long waitNanos = result.getNanosToWaitForRefill(); // -1 when unknown
    if (!result.isAllowed() && waitNanos > 0) {
        registry.timer("app.ratelimit.wait", "rule", rule).record(waitNanos, TimeUnit.NANOSECONDS);
    }
};

RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("my-api-limiter")
    .rules(List.of(rule))
    .keyResolver(new LimitScopeKeyResolver())
    .metricsRecorder(micrometerRecorder)
    .build();
```

### Auditing Decisions

`Bucket4jRateLimiter` and `RedisRateLimiter` call the rule set's recorder once for every decision
they return, on the calling thread, so a recorder can also drive an audit log (keep it fast). A
call that ends in an exception is not recorded. An exception (not an `Error`) thrown by the
recorder itself - including a checked exception thrown sneakily - is caught and logged - at WARN at
most once a minute, at DEBUG otherwise - and `tryConsume` still returns the decision: the tokens were
already charged, so such a failure does not fail the call or make a caller retry it.
`CompositeMetricsRecorder` isolates its delegates the same way, so one failing recorder does not stop
the others from receiving the event. An `Error` still propagates. Monitor the log (or count failures
inside the recorder) if missing audit records matter.

The recorder only sees the limiter's decisions. When you go through `RateLimitEngine`, the
decisions the engine makes itself never reach the limiter and so are not recorded: an access-control
`DENY` (key `denied:...`), an access-control `ALLOW_BYPASS`, and the `DENY` (key
`missing-rule-set:<id>`) or `ALLOW` of `OnMissingRuleSetStrategy` for an unknown rule set id. To
audit those too, record the result of `engine.check(...)` in your own code instead of (or as well
as) using a recorder.

Keys can contain raw API keys and user ids (`PER_API_KEY` gives `key:<API key>`, `PER_USER` gives
`user:<user id>`), so do not log `result.getKey()` in clear. Log the rule id and a masked key, as
the limiters' own debug logs do (first 4 characters, then `***`):

```java
public class AuditMetricsRecorder implements RateLimitMetricsRecorder {
    private static final Logger auditLog = LoggerFactory.getLogger("ratelimit.audit");

    @Override
    public void record(RequestContext context, RateLimitResult result) {
        if (!result.isAllowed()) {
            RateLimitRule rule = result.getMatchedRule();
            auditLog.warn("Rate limit exceeded: rule={}, endpoint={}, key={}",
                          rule != null ? rule.getId() : "-",
                          context.getEndpoint(),
                          mask(result.getKey()));
        }
    }

    // Keys may hold API keys or user ids: never log them in clear.
    private static String mask(RateLimitKey key) {
        String value = key != null ? key.value() : null;
        return value == null || value.length() <= 4 ? "***" : value.substring(0, 4) + "***";
    }
}
```

---

## Behaviour of `Bucket4jRateLimiter`

- **State is per JVM.** Each instance keeps its own buckets; use the Redis limiter when several
  instances must share a limit.
- **Concurrency.** The bands of a rule live in one Bucket4j bucket. Across rules, the buckets a
  request needs are locked in a fixed global order, checked, and only then charged. A request
  contends only with requests that share one of its buckets (all callers of a `GLOBAL` rule share
  one).
- **Bounded memory.** Buckets live in a Caffeine cache of at most `DEFAULT_MAXIMUM_SIZE` = 100,000
  entries. A bucket is dropped after it has been idle for the longer of `expireAfterAccess`
  (default 1 hour) and the longest window among its bands, so expiry never resets a partially used
  quota. Both are set through `new Bucket4jRateLimiter(maximumSize, expireAfterAccess)`.
- **Eviction resets a limit.** A bucket evicted for size is recreated full. `getEvictionCount()`
  counts evictions (size and expiry); a rate far above your normal key churn means the cache is too
  small or someone is cycling identities.
- **Rule reloads.** `reset(ruleSetId)` and `resetAll()` drop buckets; buckets are also keyed by the
  band definitions, so changed bands never reuse an old bucket.

This README makes no throughput or latency claims; measure with the JMH benchmarks in [`fluxgate-benchmarks`](../fluxgate-benchmarks/)
(`StandaloneRateLimiterBenchmark` for this limiter, `RedisRateLimiterBenchmark` for Redis).

---

## Roadmap

This module follows the project-wide version (currently the **0.4.0** development line). The
authoritative history is [CHANGELOG.md](../CHANGELOG.md); release steps are in
[RELEASING.md](../RELEASING.md); planned work is in [ROADMAP.md](../ROADMAP.md).

---

## Contributing

Contributions are welcome; see [CONTRIBUTING.md](../CONTRIBUTING.md).

### Development Setup

```bash
# Clone repository
git clone https://github.com/OpenFluxGate/fluxgate.git
cd fluxgate

# This module alone: JDK 11 or newer, no Docker
./mvnw -pl fluxgate-core test

# Whole reactor: JDK 21 (the samples need it)
./mvnw test

# Full build with integration tests (JDK 21; Docker must be running for Testcontainers)
./mvnw verify

# Check code style
./mvnw spotless:check
```

---

## License

This project is licensed under the **MIT License** - see the [LICENSE](../LICENSE) file for details.

---

## Acknowledgments

- Built on top of [Bucket4j](https://github.com/bucket4j/bucket4j) - An excellent Java rate limiting library
- Inspired by Kong, Nginx rate limiting, and AWS API Gateway throttling

---

## Contact

- **Author**: Jaeseong Ro
- **GitHub**: [OpenFluxGate/fluxgate](https://github.com/OpenFluxGate/fluxgate)
- **Issues**: [GitHub Issues](https://github.com/OpenFluxGate/fluxgate/issues)

---

<div align="center">

**[Quick Start](#quick-start)** • **[Examples](src/test/java/org/fluxgate/core/FeatureDemoTest.java)** • **[Key Resolver Guide](../docs/en/customization/key-resolver.md)** • **[Contributing](#contributing)** • **[License](#license)**

</div>
