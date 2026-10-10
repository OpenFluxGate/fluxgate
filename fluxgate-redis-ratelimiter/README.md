# FluxGate Redis Rate Limiter

Redis-backed distributed rate limiter implementation for the FluxGate framework.

## Overview

This module provides a production-ready, distributed rate limiting engine using Redis as the backend storage. It implements the FluxGate `RateLimiter` interface with support for:

- **Distributed rate limiting** across multiple application instances
- **Multi-band rate limits** (e.g., 10/sec AND 100/min AND 1000/hour)
- **Atomic operations** using Lua scripts
- **Three algorithms**: `TOKEN_BUCKET` (continuous refill), `SLIDING_WINDOW` (sub-bucket counters) and `FIXED_WINDOW` (tumbling or calendar-aligned counter)
- **TTL-based bucket expiration** for memory efficiency
- **Zero dependencies on Spring** - works with any Java application

## Key Features

### Production-Safe Design

This implementation includes critical fixes for production environments:

| Feature | Description |
|---------|-------------|
| **Redis TIME** | Uses Redis server time instead of `System.nanoTime()` - eliminates clock drift across distributed nodes |
| **Microsecond time base** | Redis runs Lua 5.1, where every number is an IEEE-754 double with an exact integer range of 2^53 (≈ 9.0e15). Epoch microseconds (≈ 1.76e15) fit exactly; nanoseconds (≈ 1.76e18) do not, which is why the old `last_refill_nanos` field was serialised as `1.76e+18`. Every value written to a hash goes through `string.format('%.0f', v)` |
| **All-or-nothing per rule** | Every band of one rule is checked before any is written, so a rejected request never drains a band that would have allowed it |
| **Read-only on rejection** | A rejected request writes no token, counter or sub-bucket. Only expiries are touched: the TTLs of existing TOKEN_BUCKET / SLIDING_WINDOW keys are refreshed, and a FIXED_WINDOW counter found without an expiry gets its `PEXPIREAT` |
| **Carried remainder** | Refill credits whole tokens and advances the timestamp only by the time those tokens cost, so the sub-token remainder carries instead of being dropped (dropping it systematically under-allowed high-frequency bands) |
| **TTL safety margin** | 10% buffer, `max(1, ceil(window_seconds * 1.1))`, which prevents premature expiry under clock skew |
| **Configurable TTL cap** | TOKEN_BUCKET and SLIDING_WINDOW TTLs are capped at `max_bucket_ttl` (`fluxgate.redis.max-bucket-ttl`, `RedisTokenBucketStore.DEFAULT_MAX_BUCKET_TTL` = 7 days), so forgeable identity keys cannot occupy Redis for weeks. A band whose window needs a longer TTL expires early and is effectively shortened; `RedisRateLimiter` logs a warning once per rule when that happens. FIXED_WINDOW counters are exempt: they expire at their window end (`PEXPIREAT`) |
| **Cluster hash tag** | All bands of one rule and key share a `{...}` hash tag, so they live in one slot and the multi-key script is atomic on Redis Cluster |

> **Across rules**: on a standalone Redis, or when all keys of the matching rules hash to one cluster
> slot, every rule is evaluated in a single Lua call, all-or-nothing. Otherwise rules are charged one
> by one and a later rejection refunds the earlier ones (`token_bucket_refund.lua`). That
> compensation is **not** atomic: between charge and refund concurrent requests see the earlier rule a
> permit lower, and a refund that cannot run (process death, Redis failure) leaves the permit spent.
> Use one rule with several bands when you need strict atomicity on a cluster.

### Core Capabilities

- Atomic refill + consume - one Lua script execution per request (per rule only on a cluster
  whose matching rules hash to different slots)
- Multi-band support - every band of a rule in that one call, all-or-nothing
- Efficient memory usage - Automatic TTL expiration of idle buckets
- High performance - Lettuce Redis client, scripts run by SHA (`EVALSHA`)
- Production ready - Comprehensive error handling and logging

## Installation

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-redis-ratelimiter</artifactId>
    <version>${fluxgate.version}</version>
</dependency>
```

### Dependencies

This module depends on:
- `fluxgate-core` - Core interfaces and models
- `io.lettuce:lettuce-core` - Redis client (transitive)

## Quick Start

### 1. Create Configuration and Rate Limiter

```java
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.RedisRateLimiter;

// Create configuration (connects to Redis, loads Lua scripts)
RedisRateLimiterConfig config = new RedisRateLimiterConfig("redis://localhost:6379");

// Create rate limiter
RedisRateLimiter rateLimiter = new RedisRateLimiter(config.getTokenBucketStore());
```

### 2. Define a Rate Limit Rule

```java
import org.fluxgate.core.config.*;
import java.time.Duration;

// Create a band: 100 requests per minute
RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100)
    .label("100-per-minute")
    .build();

// Create a rule
RateLimitRule rule = RateLimitRule.builder("api-rate-limit")
    .name("API Rate Limit: 100/minute per IP")
    .enabled(true)
    .scope(LimitScope.PER_IP)
    .keyStrategyId("clientIp")
    .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
    .addBand(band)
    .ruleSetId("my-api-limits")
    .build();
```

### 3. Create a Rule Set

```java
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.RateLimitKey;

// Key resolver extracts the rate limit key from request context. RateLimitKey.of(prefix, value)
// keeps the scope prefix outside the sanitised value - the key shape the built-in resolvers use.
KeyResolver ipKeyResolver = (context, matchedRule) -> {
    String ip = context.getClientIp();
    return RateLimitKey.of("ip:", ip != null && !ip.isEmpty() ? ip : "unknown");
};

// Build rule set
RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("my-api-limits")
    .description("API rate limits")
    .rules(List.of(rule))
    .keyResolver(ipKeyResolver)
    .build();
```

### 4. Rate Limit a Request

```java
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.ratelimiter.RateLimitResult;

// Build request context
RequestContext context = RequestContext.builder()
    .clientIp("203.0.113.10")
    .endpoint("/api/users")
    .method("GET")
    .build();

// Try to consume 1 permit
RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

if (result.isAllowed()) {
    // Request allowed
    System.out.println("Allowed! Remaining: " + result.getRemainingTokens());
} else {
    // Request rejected
    long waitMs = result.getNanosToWaitForRefill() / 1_000_000;
    System.out.println("Rejected! Retry after: " + waitMs + " ms");
}
```

### 5. Clean Up

```java
// Closes only what it created. A RedisConnectionProvider you supplied yourself is left open -
// see RedisRateLimiterConfig.ownsConnectionProvider().
config.close();
```

`RedisRateLimiter` also implements `AutoCloseable`, but its `close()` is a documented **no-op**: the
limiter owns neither the store nor the connection. `RedisTokenBucketStore.close()` was removed for the
same reason.

## Redis Setup

### Docker (Quick Start)

```bash
docker run -d \
  --name fluxgate-redis \
  -p 6379:6379 \
  redis:7.2.5-alpine
```

### Docker Compose (with RedisInsight)

Create a `docker-compose.yml`:

```yaml
version: '3.8'

services:
  redis:
    image: redis:7.2.5-alpine
    container_name: fluxgate-redis
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    command: redis-server --appendonly yes
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  redisinsight:
    image: redis/redisinsight:2.58
    container_name: fluxgate-redisinsight
    ports:
      - "5540:5540"
    depends_on:
      - redis

volumes:
  redis-data:
```

Start with:

```bash
docker-compose up -d
```

Access RedisInsight at http://localhost:5540 to visualize your rate limit buckets.

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

## Rule Configuration

### Integration with MongoDB

This module works seamlessly with `fluxgate-mongo-adapter` for rule storage:

```java
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.adapter.mongo.rule.MongoRuleSetProvider;

// Setup MongoDB repository
MongoCollection<Document> collection = mongoDatabase.getCollection("rate_limit_rules");
MongoRateLimitRuleRepository ruleRepo = new MongoRateLimitRuleRepository(collection);

// Create provider with key resolver
KeyResolver ipKeyResolver = (ctx, rule) -> {
    String ip = ctx.getClientIp();
    return RateLimitKey.of("ip:", ip != null && !ip.isEmpty() ? ip : "unknown");
};
MongoRuleSetProvider provider = new MongoRuleSetProvider(ruleRepo, ipKeyResolver);

// Load rule set by ID
Optional<RateLimitRuleSet> ruleSet = provider.findById("my-api-limits");
```

### Example Rule Document (MongoDB)

```json
{
  "id": "per-ip-100-per-minute",
  "name": "Per-IP Rate Limit: 100/minute",
  "enabled": true,
  "scope": "PER_IP",
  "keyStrategyId": "clientIp",
  "onLimitExceedPolicy": "REJECT_REQUEST",
  "bands": [
    {
      "windowSeconds": 60,
      "capacity": 100,
      "label": "100-per-minute",
      "algorithm": "TOKEN_BUCKET"
    }
  ],
  "ruleSetId": "my-api-limits"
}
```

### Multi-Band Rate Limiting

Apply multiple rate limits simultaneously (e.g., burst protection + sustained limit + daily quota):

```java
RateLimitRule rule = RateLimitRule.builder("strict-limit")
    .name("Strict API Limit")
    .enabled(true)
    .scope(LimitScope.PER_IP)
    .keyStrategyId("clientIp")
    .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
    // Add multiple bands
    .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10)
            .label("10-per-second")
            .build())
    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100)
            .label("100-per-minute")
            .build())
    .addBand(RateLimitBand.builder(Duration.ofHours(1), 1000)
            .label("1000-per-hour")
            .build())
    .ruleSetId("strict-rules")
    .build();
```

**Behavior**: If ANY band rejects, the entire request is rejected.

### Redis Key Structure

Keys follow this pattern:

```
fluxgate:bucket:{<ruleSetId>:<ruleId>:<keyValue>}:<bandKeyLabel>
```

**Examples**:

```
fluxgate:bucket:{my-api-limits:per-ip-100:ip:203.0.113.10}:100-per-minute
fluxgate:bucket:{api-limits:multi-band:user:user-123}:10-per-second
fluxgate:bucket:{api-limits:multi-band:user:user-123}:100-per-minute
fluxgate:bucket:{api-limits:multi-band:user:user-123}:1000-per-hour
```

Three things to note:

- **The `{...}` hash tag.** Redis Cluster hashes only the text inside the braces, so all bands of one
  rule and key land in the same slot. That is what makes the multi-key Lua script atomic on a
  cluster. It also means one rule + one key is one slot, which is worth knowing when sizing a
  cluster: a very hot key is not spread across nodes.
- **The key value carries a scope prefix** — `ip:`, `user:`, `key:`, `custom:`, or the constant
  `global` — and the value after it is encoded injectively: a clean value of at most 256 characters
  from `[A-Za-z0-9._:@-]` is kept as is; any other value becomes `h:<value with _ for disallowed
  characters>:<16 hex of its SHA-256>` (`user:h:a_1:<16 hex>` for `a+1`), or `h:<64 hex>` when it is
  too long (`user:h:<64 hex>`). The prefix stays outside the hash. See
  [Key Resolver](../docs/en/customization/key-resolver.md#key-value-sanitisation).
- **The band segment is `RateLimitBand.getKeyLabel()`**: the explicit label if the band has one,
  otherwise `<capacity>-per-<windowSeconds>s` (for example `100-per-60s`). Two unlabelled bands of one
  rule therefore no longer collide on a single key — they used to, which meant the second band was
  never enforced. Renaming a label moves that band's bucket, resetting it once.

A TOKEN_BUCKET key is a Redis hash with the fields below (SLIDING_WINDOW and FIXED_WINDOW keys are
listed under [Hash fields](#hash-fields)):

| Field | Meaning |
|-------|---------|
| `tokens` | Current token count, written as plain digits via `string.format('%.0f', v)` |
| `last_refill_micros` | Redis `TIME` at the last refill, in **microseconds** |

A bucket written by FluxGate 0.3.x carries `last_refill_nanos` instead. That field is ignored, so the
bucket is treated as missing and re-initialised full — a one-off quota reset on upgrade.

### Building keys and patterns

Never assemble a key or a `SCAN` pattern by string concatenation. Use:

```java
RedisRateLimiter.BUCKET_KEY_PREFIX              // "fluxgate:bucket:"
RedisRateLimiter.bucketKeyPattern(ruleSetId)    // "fluxgate:bucket:{<escaped id>:*"
```

`bucketKeyPattern` escapes the glob metacharacters `* ? [ ] \`, so a rule set id of `*` produces a
pattern that matches only that rule set.

| Operation | Pattern used |
|-----------|--------------|
| `RedisTokenBucketStore.deleteBucketsByRuleSetId(id)` | `RedisRateLimiter.bucketKeyPattern(id)` |
| `RedisTokenBucketStore.deleteAllBuckets()` | `fluxgate:bucket:*` **only** |

Scoping the second pattern matters: the previous `fluxgate:*` also deleted the rule definitions stored
under `fluxgate:ruleset:*` and the `fluxgate:rulesets` index. Both operations use `SCAN` + `UNLINK`,
not `KEYS` + `DEL`, so a reset does not block a single-threaded Redis for O(N).

## Lua Script Contract

```
📁 src/main/resources/lua/token_bucket_consume.lua   consume (or check) every band, all-or-nothing
📁 src/main/resources/lua/token_bucket_refund.lua    give a charged rule its permits back
```

The scripts are loaded by a per-store `LuaScriptRegistry` (not from process-global static state) and
executed with `EVALSHA`, falling back to `EVAL` plus a reload on `NOSCRIPT`. The header comment of
`token_bucket_consume.lua` is the authoritative copy of this contract.

### Inputs

```
KEYS[1..n]          one bucket key per band, all in the same hash slot (one {...} hash tag per rule)

ARGV[1]             permits
ARGV[2]             max_bucket_ttl_seconds: cap on every TOKEN_BUCKET / SLIDING_WINDOW bucket TTL
                    (fluxgate.redis.max-bucket-ttl); FIXED_WINDOW counters use PEXPIREAT instead

per band i (1-based), base = 2 + 5 * (i - 1):
ARGV[base + 1]      capacity
ARGV[base + 2]      window_micros (at least 1000: windows below 1 ms are refused)
ARGV[base + 3]      algorithm code: 1 TOKEN_BUCKET, 2 SLIDING_WINDOW, 3 FIXED_WINDOW
ARGV[base + 4]      SLIDING_WINDOW sub-bucket count [2..60] (each sub-bucket at least 1 ms), else 0
ARGV[base + 5]      calendar FIXED_WINDOW window end in epoch micros, else 0 (derived from now)

optional, after the last band:
ARGV[3 + 5 * n]     "1" = check-only: the decision is taken as for a consumption, but nothing is
                    consumed (RedisTokenBucketStore.check)
```

`n` is `#KEYS`, and `#ARGV` must equal `2 + 5 * n`, or `3 + 5 * n` with the check-only flag.

Bucket state per algorithm:

| Algorithm | Redis value |
|-----------|-------------|
| `TOKEN_BUCKET` | hash `{tokens, last_refill_micros}`, TTL `min(max_bucket_ttl, max(1, ceil(window_s × 1.1)))` |
| `SLIDING_WINDOW` | hash `{"<sub-bucket index>@<sub-bucket duration micros>": count}`, same TTL; fields of another geometry or outside the window are ignored and deleted on the next admitted request |
| `FIXED_WINDOW` | hash `{count, window_end_micros}` under the `:fw` key suffix, `PEXPIREAT` at the window end (not capped) |

### Output

Always an array of **8 integers**:

| Index | Name | Meaning |
|-------|------|---------|
| 1 | `allowed` | `1` when every band allowed, `0` otherwise |
| 2 | `rejecting_band_index` | 1-based index of the rejecting band with the **longest** wait (the first of them on a tie), `0` when allowed |
| 3 | `min_remaining` | Allow: the binding band's remaining **after** consumption (before it in check-only mode). Reject: the rejecting band's remaining |
| 4 | `micros_to_wait` | Microseconds until the request can be served: the longest wait of all rejecting bands, so a retry after it is not refused by another band. `0` when allowed |
| 5 | `reset_time_millis` | Epoch millis when the binding band resets - TOKEN_BUCKET: full again (after consumption); SLIDING_WINDOW: everything counted now has left the window; FIXED_WINDOW: window end |
| 6 | `limit` | Capacity of the binding band |
| 7 | `binding_band_index` | 1-based index of the binding band; equals `rejecting_band_index` on a reject |
| 8 | `now_micros` | The Redis `TIME` the decision was taken at; the refund script uses it to find the sliding sub-bucket / fixed window it charged |

A rejecting SLIDING_WINDOW band waits until enough counted requests have left the window: its
sub-buckets are walked oldest to newest, and the first sub-bucket `k` after whose departure
`total - freed + permits <= capacity` sets `micros_to_wait = (k + buckets) × sub_duration - now`. A
burst inside one sub-bucket therefore waits almost a whole window, not one sub-bucket.

Java mapping in `RedisTokenBucketStore`: `micros_to_wait × 1000` → `BucketState.nanosToWaitForRefill()`,
`binding_band_index − 1` → `BucketState.bandIndex()` (0-based, `-1` unknown), and `now_micros` →
`BucketState.redisTimeMicros()`.

The refund script takes the same `KEYS` and the same five values per band, with `ARGV[2]` set to the
`now_micros` of the consumption instead of the TTL cap, and returns one integer per band: the permits
actually given back. It never gives back more than was taken and never creates a key.

### Errors

Returned as `redis.error_reply` and surfaced as `ScriptExecutionException` — never as a raw
`RedisCommandExecutionException`. Both scripts use the same messages for the same arguments:

```
'at least one bucket key is required'
'expected N arguments for M band(s)'
'permits must be positive'
'max bucket ttl must be >= 1 second'          (consume)
'consumed_at_micros must be positive'         (refund)
'capacity must be positive'
'window must be positive'
'window must be at least 1 ms'
'sliding window sub-bucket must be at least 1 ms'
'permits exceed capacity'                     (consume)
'buckets must be >= 2 for SLIDING_WINDOW'
'unknown algorithm code: N'
```

`permits exceed capacity` is also pre-validated in Java, which throws `InvalidRuleConfigException`
before touching Redis: a band whose capacity is below the requested permits can never serve the
request, so returning a wait time would send the caller into a retry loop.

### Execution

```
Pass 1  for every band: read its state (TOKEN_BUCKET refills), check - without writing
        ├─ any band rejects → keep checking the rest, write NO token / counter / sub-bucket,
        │                     EXPIRE the TOKEN_BUCKET / SLIDING_WINDOW keys (a FIXED_WINDOW counter
        │                     without an expiry gets its PEXPIREAT), return the rejecting band with
        │                     the longest wait
        ├─ check-only mode  → return the decision, write nothing
        └─ all bands OK     → continue

Pass 2  for every band: write its state (HMSET / HINCRBY / HSET) and its expiry
        → return an allow, with reset_time computed after consumption
```

TTL per TOKEN_BUCKET / SLIDING_WINDOW band is `min(max_bucket_ttl, max(1, ceil(window_seconds * 1.1)))`
(`fluxgate.redis.max-bucket-ttl`, 7 days by default); a FIXED_WINDOW counter gets `PEXPIREAT` at its
window end instead and is not capped.

### Hash fields

| Algorithm | Field | Written as |
|-----------|-------|-----------|
| TOKEN_BUCKET | `tokens`, `last_refill_micros` | `string.format('%.0f', v)` |
| SLIDING_WINDOW | `"<sub-bucket index>@<sub-bucket duration micros>"` → count | `HINCRBY`, index via `string.format('%.0f', v)` |
| FIXED_WINDOW | `count`, `window_end_micros` | `string.format('%.0f', v)` |

A 0.3.x bucket carries `last_refill_nanos`; that field is ignored, so the bucket is re-initialised
full once on upgrade.

## Behavior

### Token Bucket Algorithm

1. **Initial State**: Bucket starts full (capacity tokens) - allows initial burst
2. **Refill**: Whole tokens are credited from the elapsed time, clamped to one window:
   ```
   elapsed_micros = min(max(0, now_micros - last_refill_micros), window_micros)
   tokens_to_add  = floor(elapsed_micros * capacity / window_micros)
   ```
   The timestamp then advances by exactly the time those tokens cost, so the sub-token remainder
   carries into the next call. A full bucket resets the stamp to `now`.
3. **Consume**: If every band has `tokens >= permits`, consume from all of them; otherwise reject and
   write nothing
4. **Cap**: Tokens never exceed capacity

### When Bucket is Full (Request Rejected)

When a request is rejected:

1. **No bucket state is written** — not for the rejecting band, and not for the bands that would have
   allowed the request
2. **Only the TTLs are refreshed**, with `EXPIRE` on every TOKEN_BUCKET and SLIDING_WINDOW key (a
   FIXED_WINDOW counter keeps its absolute `PEXPIREAT`). `EXPIRE` is a no-op for a key that
   does not exist, so a band that was never charged is not created, and a bucket that sees nothing but
   rejections still expires on schedule instead of inheriting the shrinking TTL of its last allowed
   request
3. **Wait time calculated** for every rejecting band, and the longest one is reported:
   ```
   TOKEN_BUCKET    micros_to_wait = ceil(tokens_needed * window_micros / capacity)
   SLIDING_WINDOW  micros_to_wait = (k + buckets) * sub_duration - now, where k is the oldest
                   sub-bucket whose departure lets the request fit
   FIXED_WINDOW    micros_to_wait = window_end - now
   ```
4. **Reset time provided** (`reset_time_millis`): TOKEN_BUCKET - when the bucket is full again;
   SLIDING_WINDOW - when everything counted now has left the window, rounded **up** to the
   millisecond; FIXED_WINDOW - the window end. On the *allow* path it is computed **after**
   consumption, so the caller is told when the bucket is really full again rather than when it would
   have been without this request

### RateLimitResult Fields

| Field | Description |
|-------|-------------|
| `isAllowed()` | `true` if request was allowed, `false` if rejected |
| `getRemainingTokens()` | Tokens left in the binding band after this request. On rejection this is the rejecting band's **real** remaining count, not a hardcoded `0`. `-1` means unknown (no bucket was consulted) |
| `getNanosToWaitForRefill()` | Nanoseconds until enough tokens are available (0 when allowed) |
| `getLimit()` | Capacity of the binding band. `-1` means unknown |
| `getResetTimeMillis()` | Epoch millis when the binding band resets, per algorithm as in the [Output](#output) table: TOKEN_BUCKET - full again; SLIDING_WINDOW - everything counted now has left the window; FIXED_WINDOW - the window end. `-1` means unknown |
| `getPolicy()` | The matched rule's `OnLimitExceedPolicy` |
| `getBandLabel()` | `RateLimitBand.getKeyLabel()` of the binding band, e.g. `100-per-60s` |
| `getMatchedRule()` | The rule that produced this decision |
| `getKey()` | The resolved rate limit key, e.g. `ip:203.0.113.10` |
| `hasRule()` | `false` when no rule was consulted |

`Bucket4jRateLimiter` and `RedisRateLimiter` produce **identical** semantics for all of these, so the
in-memory fallback and the distributed limiter emit the same HTTP headers.

### Mapping to HTTP Headers

If you use a Spring Boot starter you do not need this: the filter and the `@RateLimit` aspect write
both header families through `RateLimitHeaderWriter`. This is for a direct integration.

`RateLimitResult` now carries everything a header needs, so nothing has to be reconstructed from the
rule:

```java
RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

// Convert once; nanos -> millis is rounded UP, so a sub-millisecond delay never becomes 0.
RateLimitResponse response429 = RateLimitResponse.from(result);

// A value the limiter reported as unknown is -1: omit the header rather than writing "-1".
if (response429.getLimit() >= 0) {
    response.setHeader("X-RateLimit-Limit", String.valueOf(response429.getLimit()));
    response.setHeader("RateLimit-Limit", String.valueOf(response429.getLimit()));
}
if (response429.getRemainingTokens() >= 0) {
    response.setHeader("X-RateLimit-Remaining", String.valueOf(response429.getRemainingTokens()));
    response.setHeader("RateLimit-Remaining", String.valueOf(response429.getRemainingTokens()));
}
if (response429.getResetTimeMillis() >= 0) {
    // Legacy: epoch seconds. IETF: delta seconds.
    response.setHeader("X-RateLimit-Reset",
        String.valueOf((response429.getResetTimeMillis() + 999) / 1000));
    long deltaMillis = Math.max(0, response429.getResetTimeMillis() - System.currentTimeMillis());
    response.setHeader("RateLimit-Reset", String.valueOf((deltaMillis + 999) / 1000));
}
if (response429.getLimit() > 0 && response429.getWindowSeconds() > 0) {
    response.setHeader("RateLimit-Policy",
        response429.getLimit() + ";w=" + response429.getWindowSeconds());
}

if (!result.isAllowed()) {
    // Round up, and never advertise 0 - that sends clients into a busy loop.
    long retryAfter = Math.max(1L, (response429.getRetryAfterMillis() + 999) / 1000);
    response.setHeader("Retry-After", String.valueOf(retryAfter));
    response.setStatus(429);
}
```

**Example Response Headers (Allowed)**:

```http
HTTP/1.1 200 OK
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 42
X-RateLimit-Reset: 1701388800
RateLimit-Limit: 100
RateLimit-Remaining: 42
RateLimit-Reset: 35
RateLimit-Policy: 100;w=60
```

**Example Response Headers (Rejected)**:

```http
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1701388800
RateLimit-Limit: 100
RateLimit-Remaining: 0
RateLimit-Reset: 45
RateLimit-Policy: 100;w=60
Retry-After: 45
Content-Type: application/problem+json;charset=UTF-8

{"type":"about:blank","title":"Too Many Requests","status":429,
 "detail":"Rate limit exceeded, retry after 45 seconds","retryAfterMillis":44120}
```

## Testing

### Integration Tests in fluxgate-testkit

The `fluxgate-testkit` module contains comprehensive integration tests:

| Test | Description |
|------|-------------|
| `shouldAllowFirst100RequestsThenReject101st` | Sequential E2E test: MongoDB rule storage -> Redis enforcement, 100 allowed, 101st rejected |
| `shouldEnforceRateLimitUnderConcurrentLoad` | Concurrency stress test: 20 threads x 50 requests = 1000 total, verifies exactly 100 allowed |

### Two test tiers

FluxGate splits tests so that a clean checkout tests fully with no infrastructure at all:

```bash
# Unit tier only - surefire. No Docker, no Redis, no MongoDB.
./mvnw test -pl fluxgate-redis-ratelimiter

# Unit tier + integration tier - failsafe runs *IntegrationTest / *IT
./mvnw verify -pl fluxgate-redis-ratelimiter

# Integration tier skipped explicitly
./mvnw verify -pl fluxgate-redis-ratelimiter -DskipITs

# One class
./mvnw verify -pl fluxgate-redis-ratelimiter -Dit.test=TokenBucketConsumeLuaIntegrationTest

# Redis Cluster tests (opt-in profile; expects docker/redis-cluster.yml or localhost:7100-7105)
./mvnw verify -pl fluxgate-redis-ratelimiter -am -Predis-cluster-it

# ... in CI: fail instead of skipping when the cluster is unreachable
./mvnw verify -pl fluxgate-redis-ratelimiter -am -Predis-cluster-it -Dfluxgate.redis.cluster.require=true
```

The cluster tests skip themselves when no cluster answers. `-Dfluxgate.redis.cluster.require=true`
(or `FLUXGATE_REDIS_CLUSTER_REQUIRE=true`) turns that skip into a failure, so a CI job whose cluster
did not come up cannot pass with every test skipped. `-Dfluxgate.redis.cluster.uri` (or
`FLUXGATE_REDIS_CLUSTER_URI`) points the tests at a cluster on other ports.

`./mvnw test` no longer runs the integration tests. The integration classes in this module are
`TokenBucketConsumeLuaIntegrationTest`, `TokenBucketRefundLuaIntegrationTest`,
`MultiAlgorithmLuaIntegrationTest`, `RedisTokenBucketStoreIntegrationTest`,
`RedisRateLimiterIntegrationTest` and `RedisRateLimiterCrossRuleIntegrationTest`, plus
`ClusterConnectionIntegrationTest` and `ClusterCrossRuleIntegrationTest`, which run only in the
`redis-cluster-it` profile.

### How the integration tier finds Redis

In this order:

1. a URI supplied through the environment or a system property,
2. a disposable [Testcontainers](https://testcontainers.com/) `redis:7-alpine`,
3. **skip** — a JUnit assumption aborts the test. Integration tests never *fail* for want of
   infrastructure.

| Variable | Purpose |
|----------|---------|
| `FLUXGATE_REDIS_URI` (or `-Dfluxgate.redis.uri`) | Use an existing Redis, e.g. `redis://localhost:6379` |
| `FLUXGATE_MONGO_URI` | Used by the MongoDB modules and the testkit |
| `FLUXGATE_MONGO_DB` | Database name for `FLUXGATE_MONGO_URI` |

```bash
FLUXGATE_REDIS_URI=redis://localhost:6379 ./mvnw verify -pl fluxgate-redis-ratelimiter
```

Local infrastructure if you want it — these compose files bind every port to `127.0.0.1` and are
labelled local development only:

```bash
docker compose -f ../docker/redis-standalone.yml up -d
docker compose -f ../docker/mongo.yml up -d
```

### Pointing the tests at a shared Redis is safe

No test calls `flushdb()`. Every rule set id and bucket key carries a per-JVM run id, cleanup is a
`SCAN` + `DEL` restricted to those keys, and the test support class **refuses** any pattern that does
not start with `fluxgate:`. Earlier versions did call `flushdb()`, which destroyed developer and CI
data.

### CI/CD

See `.github/workflows/maven-ci.yml`. Each of its jobs (Java 21 all modules, Java 11 with the Spring
Boot 2 starter, Java 17 with the Spring Boot 3 starter) starts Redis, a Redis Cluster and MongoDB as
services and runs `./mvnw -B verify -Predis-cluster-it -Dfluxgate.redis.cluster.require=true`, so the
cluster integration tests run on every build and fail - rather than skip - when the cluster is
unreachable.

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                        Your application                              │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   RequestContext ──▶ RedisRateLimiter ──▶ RateLimitResult            │
│                            │                                         │
│                            ▼                                         │
│                   RedisTokenBucketStore                              │
│                   (tryConsume / check / refund)                      │
│                            │                                         │
│                            ▼                                         │
│          token_bucket_consume.lua (atomic, all-or-nothing)           │
│            - Redis TIME, microseconds                                │
│            - pass 1 checks every band, pass 2 writes all or none     │
│            - read-only on reject (expiries only)                     │
│          token_bucket_refund.lua (cross-slot compensation)           │
│                            │                                         │
└────────────────────────────┼─────────────────────────────────────────┘
                             │
                             ▼
┌──────────────────────────────────────────────────────────────────────┐
│                              Redis                                   │
│                                                                      │
│  fluxgate:bucket:{api-limits:rule-1:ip:203.0.113.10}:100-per-60s     │
│  ├── tokens: 42                                                      │
│  └── last_refill_micros: 1701388799123456                            │
│  TTL: 66 s = min(max-bucket-ttl, ceil(60 s × 1.1))                   │
│                                                                      │
│  fluxgate:bucket:{api-limits:rule-1:ip:203.0.113.10}:10-per-60s:fw   │
│  ├── count: 3                                                        │
│  └── window_end_micros: 1701388800000000                             │
│  PEXPIREAT at the window end                                         │
└──────────────────────────────────────────────────────────────────────┘
```

### Thread Safety

- **Single instance**: `RedisRateLimiter` is thread-safe
- **Distributed**: Multiple application instances can share the same Redis database
- **Atomicity**: Lua scripts guarantee atomic operations (no race conditions)

## Configuration Options

```java
// Option 1: URI (standalone, or comma-separated nodes for a cluster). Credentials and the
// database go into the URI; the connect timeout defaults to RedisUriUtils.DEFAULT_TIMEOUT (5 s).
RedisRateLimiterConfig config = new RedisRateLimiterConfig("redis://:secret@redis.example.com:6379/0");

// Option 2: URI, connect timeout and bucket TTL cap (null = DEFAULT_MAX_BUCKET_TTL, 7 days)
RedisRateLimiterConfig config =
    new RedisRateLimiterConfig("redis://localhost:6379", Duration.ofSeconds(5), Duration.ofDays(1));

// Option 3: explicit mode and node list
RedisRateLimiterConfig config = new RedisRateLimiterConfig(
    RedisConnectionProvider.RedisMode.CLUSTER,
    List.of("redis://node1:6379", "redis://node2:6379", "redis://node3:6379"),
    Duration.ofSeconds(5));

// Option 4: a connection you manage yourself, e.g. from an existing Lettuce RedisClient.
// config.close() leaves it open (ownsConnectionProvider() is false).
RedisClient client = RedisClient.create("redis://localhost:6379");
RedisConnectionProvider provider = new StandaloneRedisConnection(client.connect().sync());
RedisRateLimiterConfig config = new RedisRateLimiterConfig(provider);
```

`RedisConnectionProvider` and `StandaloneRedisConnection` are in `org.fluxgate.redis.connection`.

## Best Practices

### 1. Connection Pooling

Reuse `RedisRateLimiterConfig` across requests:

```java
// DO: Singleton pattern
@Bean
public RedisRateLimiterConfig redisRateLimiterConfig() {
    return new RedisRateLimiterConfig("redis://localhost:6379");
}

// DON'T: Create per request (connection leak!)
RedisRateLimiterConfig config = new RedisRateLimiterConfig(...); // Bad!
```

### 2. Graceful Shutdown

Always close resources:

```java
try (RedisRateLimiterConfig config = new RedisRateLimiterConfig(...)) {
    RedisRateLimiter limiter = new RedisRateLimiter(config.getTokenBucketStore());
    // Use limiter
} // Auto-closes
```

### 3. Error Handling (Fail Open vs Fail Closed)

```java
try {
    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);
    if (!result.isAllowed()) {
        return ResponseEntity.status(429).body("Rate limited");
    }
} catch (Exception e) {
    log.error("Rate limiter unavailable", e);
    // Fail open: allow request when Redis is down
    // Fail closed: reject request when Redis is down (the FluxGate default)
}
```

With a Spring Boot starter you do not write this: `fluxgate.ratelimit.failure-behavior` (default
`DENY`) decides, and `fluxgate.ratelimit.fallback.mode=IN_MEMORY` is usually the better third option —
it keeps limits in force per instance during an outage instead of choosing between "allow everything"
and "reject everything".

### 4. Monitoring

Track these metrics:
- Redis connection pool utilization
- Rate limit check latency (p50, p99)
- Rejection rate by rule/endpoint
- Redis memory usage

## Troubleshooting

### Scripts Not Loaded

**Error**: `ScriptExecutionException` naming `token_bucket_consume.lua`

Each `RedisTokenBucketStore` owns a `LuaScriptRegistry` and loads the script when it is constructed,
so this means the script is missing from the classpath or could not be read — check that
`fluxgate-redis-ratelimiter`'s jar is intact and not shaded without its resources.

```java
RedisRateLimiterConfig config = new RedisRateLimiterConfig("redis://localhost:6379");
RedisTokenBucketStore store = config.getTokenBucketStore();   // loaded by the constructor above
```

A `NOSCRIPT` from Redis (for example after `SCRIPT FLUSH`) is **not** an error: the store falls back
to `EVAL` and reloads the script.

### Invalid Script Result

**Error**: `ScriptExecutionException: Lua script returned invalid result`

**Solution**: The script must return exactly **8** integers:
```
[allowed, rejecting_band_index, min_remaining, micros_to_wait,
 reset_time_millis, limit, binding_band_index, now_micros]
```
This normally only happens if you replaced `token_bucket_consume.lua` on the classpath. See
[Lua Script Contract](#lua-script-contract).

### High Memory Usage

**Symptom**: Redis memory grows unbounded

**Check TTL**:
```bash
redis-cli TTL "fluxgate:bucket:{api-limits:rule-1:ip:203.0.113.10}:100-per-60s"
```

Expected TTL for a TOKEN_BUCKET or SLIDING_WINDOW key is `min(max-bucket-ttl, max(1, ceil(window_seconds *
1.1)))` — for a 60s window, 66. A FIXED_WINDOW key (`:fw` suffix) expires at its window end. If it is
`-1` (no expiration), the key was not written by this version of the script.

**Count buckets**:
```bash
redis-cli --scan --pattern 'fluxgate:bucket:*' | wc -l
```

Idle buckets live at most `fluxgate.redis.max-bucket-ttl` (7 days by default), whatever the window. A
FIXED_WINDOW counter is exempt from that cap and lives until its window ends, so a 30-day calendar
quota on `PER_IP` keeps one key per distinct IP for up to 30 days. Use long windows only with scopes
whose cardinality is bounded. Lowering the cap below a TOKEN_BUCKET / SLIDING_WINDOW window shortens
that window and logs the warning described under [Key Features](#key-features).

## Utilities

### RedisUriUtils

`org.fluxgate.redis.connection.RedisUriUtils` is the single source of truth for parsing a Redis URI.
Use it instead of hand-rolling string splits:

| Member | Purpose |
|--------|---------|
| `DEFAULT_TIMEOUT` | The default connection timeout, so it is not duplicated per call site |
| `detectMode(String uri)` | `STANDALONE` or `CLUSTER`, parsed by Lettuce rather than by counting commas — a password containing a comma is no longer misread as two nodes |
| `splitNodes(String uri)` | Splits a multi-node cluster URI correctly |
| `mask(String uri)` | Renders a URI as `host:port[/db]` only |

`mask` matters: the `redis://:password@host` form that the documentation recommends used to be logged
in clear text, and the old `maskPassword` regex did not match a URI with no username.

## Deprecated API

Everything here still compiles and still works; it emits a deprecation warning and will be removed in
a later release.

| Deprecated | Since | Use instead |
|------------|-------|-------------|
| `org.fluxgate.redis.script.LuaScripts` | 0.4.0, `forRemoval` | `LuaScriptRegistry`. Nothing in FluxGate reads `LuaScripts`; it kept the script SHA in process-global mutable static state, so two instances interfered with each other |
| `org.fluxgate.redis.script.LuaScriptLoader` | 0.4.0, `forRemoval` | `LuaScriptRegistry`. The shim still declares `throws IOException` for source compatibility but never throws |
| `org.fluxgate.redis.store.RedisRuleSetStore` | 0.4.0 | Store rules in MongoDB behind a `RateLimitRuleSetProvider` |
| `org.fluxgate.redis.store.RuleSetData` | 0.4.0 | As above |
| `RedisRateLimiterConfig.getRuleSetStore()` | 0.4.0 | As above |
| `org.fluxgate.redis.connection.RedisConnectionException` | 0.2.0, `forRemoval` | `org.fluxgate.core.exception.RedisConnectionException`, which it now extends — existing `catch` blocks still compile and still catch |
| Pre-`TrustedProxies` `ClientIpExtractor` overloads (starter) | 0.4.0 | The four-argument overload taking `TrustedProxies` |

Removed in 0.4.0: `BucketState.getRetryAfterSeconds()` and `RedisTokenBucketStore.close()`.

`RedisRuleSetStore` also changed behaviour before deprecation: a half-written rule-set hash now throws
`IllegalStateException` naming the key and the missing field, instead of silently becoming "capacity
10, window 60s".

## Related Modules

- [`fluxgate-core`](../fluxgate-core/README.md) - Core abstractions and interfaces
- [`fluxgate-mongo-adapter`](../fluxgate-mongo-adapter/README.md) - MongoDB rule storage
- [`fluxgate-testkit`](../fluxgate-testkit/README.md) - Testing utilities and benchmarks
- [`fluxgate-spring-boot3-starter`](../fluxgate-spring-boot3-starter/README.md) - Auto-configuration
- [Storage Layer](../docs/en/architecture/storage-layer.md) - How this module fits the architecture
- [Migrating to 0.4](../docs/en/operations/migration-0.4.md) - Key format and Lua contract changes

## License

MIT License

## Support

- Issues: [GitHub Issues](https://github.com/OpenFluxGate/fluxgate/issues)
- Discussions: [GitHub Discussions](https://github.com/OpenFluxGate/fluxgate/discussions)
