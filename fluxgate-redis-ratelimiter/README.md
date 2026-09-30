# FluxGate Redis Rate Limiter

Redis-backed distributed rate limiter implementation for the FluxGate framework.

## Overview

This module provides a production-ready, distributed rate limiting engine using Redis as the backend storage. It implements the FluxGate `RateLimiter` interface with support for:

- **Distributed rate limiting** across multiple application instances
- **Multi-band rate limits** (e.g., 10/sec AND 100/min AND 1000/hour)
- **Atomic operations** using Lua scripts
- **Token bucket algorithm** with automatic refill
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
| **Read-only on rejection** | A rejected request writes no bucket state; only the TTLs of existing keys are refreshed |
| **Carried remainder** | Refill credits whole tokens and advances the timestamp only by the time those tokens cost, so the sub-token remainder carries instead of being dropped (dropping it systematically under-allowed high-frequency bands) |
| **TTL safety margin** | 10% buffer, `max(1, ceil(window_seconds * 1.1))`, which prevents premature expiry under clock skew |
| **No TTL cap** | There is deliberately no upper cap. A 7-day window keeps a 7-day bucket. The previous 24-hour cap silently reset any longer window, so a 7-day quota effectively allowed 7× its capacity |
| **Cluster hash tag** | All bands of one rule and key share a `{...}` hash tag, so they live in one slot and the multi-key script is atomic on Redis Cluster |

> **Not** guaranteed: atomicity **across rules**. All bands of one rule are evaluated in a single Lua
> call, but a rule set with several rules is evaluated rule by rule. Evaluation stops at the first
> rejecting rule, and earlier rules keep the tokens they already charged. Use one rule with several
> bands when you need strict atomicity.

### Core Capabilities

- Atomic refill + consume - one Lua script execution per rule
- Multi-band support - every band of a rule in that one call, all-or-nothing
- Efficient memory usage - Automatic TTL expiration of idle buckets
- High performance - Lettuce async Redis client
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

// Key resolver extracts the rate limit key from request context
KeyResolver ipKeyResolver = context -> new RateLimitKey(context.getClientIp());

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
  redis:7
```

### Docker Compose (with RedisInsight)

Create a `docker-compose.yml`:

```yaml
version: '3.8'

services:
  redis:
    image: redis:7
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
    image: redis/redisinsight:latest
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
KeyResolver ipKeyResolver = ctx -> new RateLimitKey(ctx.getClientIp());
MongoRuleSetProvider provider = new MongoRuleSetProvider(ruleRepo, ipKeyResolver);

// Load rule set by ID
Optional<RateLimitRuleSet> ruleSet = provider.findById("my-api-limits");
```

### Example Rule Document (MongoDB)

```json
{
  "_id": "per-ip-100-per-minute",
  "name": "Per-IP Rate Limit: 100/minute",
  "enabled": true,
  "scope": "PER_IP",
  "keyStrategyId": "clientIp",
  "onLimitExceedPolicy": "REJECT_REQUEST",
  "bands": [
    {
      "windowSeconds": 60,
      "capacity": 100,
      "label": "100-per-minute"
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
  `global` — and is sanitised (characters outside `[A-Za-z0-9._:@-]` become `_`, values longer than
  256 characters become their SHA-256 hex digest). See
  [Key Resolver](../docs/en/customization/key-resolver.md).
- **The band segment is `RateLimitBand.getKeyLabel()`**: the explicit label if the band has one,
  otherwise `<capacity>-per-<windowSeconds>s` (for example `100-per-60s`). Two unlabelled bands of one
  rule therefore no longer collide on a single key — they used to, which meant the second band was
  never enforced. Renaming a label moves that band's bucket, resetting it once.

Each key is a Redis Hash with fields:

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
📁 src/main/resources/lua/token_bucket_consume.lua
```

The script is loaded by a per-store `LuaScriptRegistry` (not from process-global static state) and
executed with `EVALSHA`, falling back to `EVAL` plus a reload on `NOSCRIPT`.

### Inputs

```
KEYS[1..n]    one bucket key per band of ONE rule, all inside the same {...} hash tag
ARGV[1]       permits
ARGV[2 + 3i]  capacity of band i+1
ARGV[3 + 3i]  window_micros of band i+1
ARGV[4 + 3i]  reserved for future use, pass "0"
```

`n` is `#KEYS`, and `#ARGV` must equal `1 + 3 * n`.

### Output

Always an array of **7 integers**:

| Index | Name | Meaning |
|-------|------|---------|
| 1 | `allowed` | `1` when every band allowed, `0` otherwise |
| 2 | `rejecting_band_index` | 1-based index of the first band that rejected, `0` when allowed |
| 3 | `min_remaining` | Allow: the binding band's tokens **after** consumption. Reject: the rejecting band's tokens |
| 4 | `micros_to_wait` | Microseconds until the rejecting band can serve the request, `0` when allowed |
| 5 | `reset_time_millis` | Epoch millis at which the binding band's bucket is full again, computed **after** consumption on the allow path |
| 6 | `limit` | Capacity of the binding band |
| 7 | `binding_band_index` | 1-based index of the binding band; equals `rejecting_band_index` on a reject |

Java mapping in `RedisTokenBucketStore`: `micros_to_wait × 1000` → `BucketState.nanosToWaitForRefill()`,
and `binding_band_index − 1` → `BucketState.bandIndex()` (0-based, `-1` unknown).

### Errors

Returned as `redis.error_reply` and surfaced as `ScriptExecutionException` — never as a raw
`RedisCommandExecutionException`:

```
'at least one bucket key is required'
'expected N arguments for M band(s)'
'permits must be positive'
'capacity must be positive'
'window must be positive'
'permits exceed capacity'
```

`permits exceed capacity` is also pre-validated in Java, which throws `InvalidRuleConfigException`
before touching Redis: a band whose capacity is below the requested permits can never serve the
request, so returning a wait time would send the caller into a retry loop.

### Execution

```
Pass 1  for every band: HMGET tokens/last_refill_micros, refill, check
        ├─ any band short of permits → write NOTHING, EXPIRE every key, return a reject
        └─ all bands OK              → continue

Pass 2  for every band: HMSET tokens + last_refill_micros, EXPIRE
        → return an allow, with reset_time computed after consumption
```

TTL per band is `max(1, ceil(window_seconds * 1.1))`, with **no upper cap**.

### Hash fields

| Field | Written as |
|-------|-----------|
| `tokens` | `string.format('%.0f', v)` |
| `last_refill_micros` | `string.format('%.0f', v)` |

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
2. **Only the TTLs are refreshed**, with `EXPIRE` on every key. `EXPIRE` is a no-op for a key that
   does not exist, so a band that was never charged is not created, and a bucket that sees nothing but
   rejections still expires on schedule instead of inheriting the shrinking TTL of its last allowed
   request
3. **Wait time calculated** from the rejecting band:
   ```
   micros_to_wait = ceil(tokens_needed * window_micros / capacity)
   ```
4. **Reset time provided**: epoch milliseconds when the bucket will be full again. On the *allow* path
   this is computed **after** consumption, so the caller is told when the bucket is really full again
   rather than when it would have been without this request

### RateLimitResult Fields

| Field | Description |
|-------|-------------|
| `isAllowed()` | `true` if request was allowed, `false` if rejected |
| `getRemainingTokens()` | Tokens left in the binding band after this request. On rejection this is the rejecting band's **real** remaining count, not a hardcoded `0`. `-1` means unknown (no bucket was consulted) |
| `getNanosToWaitForRefill()` | Nanoseconds until enough tokens are available (0 when allowed) |
| `getLimit()` | Capacity of the binding band. `-1` means unknown |
| `getResetTimeMillis()` | Epoch millis when the binding band's bucket is full again. `-1` means unknown |
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
./mvnw verify -pl fluxgate-redis-ratelimiter -Predis-cluster-it
```

`./mvnw test` no longer runs the integration tests. The integration classes in this module are
`TokenBucketConsumeLuaIntegrationTest`, `RedisRateLimiterIntegrationTest`,
`RedisTokenBucketStoreIntegrationTest` and `ClusterConnectionIntegrationTest` (cluster profile only).

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

See `.github/workflows/maven-ci.yml`, which runs `./mvnw -B verify` with Redis and MongoDB services
plus a `-Predis-cluster-it` job.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        API Gateway                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   RequestContext ──▶ RedisRateLimiter ──▶ RateLimitResult       │
│                            │                                     │
│                            ▼                                     │
│                   RedisTokenBucketStore                          │
│                            │                                     │
│                            ▼                                     │
│                    Lua Script (atomic)                           │
│                      - Redis TIME                                │
│                      - Integer arithmetic                        │
│                      - Read-only on reject                       │
│                            │                                     │
└────────────────────────────┼─────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                         Redis                                    │
│                                                                  │
│   fluxgate:api-limits:rule-1:203.0.113.10:100-per-minute        │
│   ├── tokens: 42                                                 │
│   └── last_refill_nanos: 1701388799123456789                    │
│                                                                  │
│   TTL: 66 seconds (window + 10% safety margin)                  │
└─────────────────────────────────────────────────────────────────┘
```

### Thread Safety

- **Single instance**: `RedisRateLimiter` is thread-safe
- **Distributed**: Multiple application instances can share the same Redis database
- **Atomicity**: Lua scripts guarantee atomic operations (no race conditions)

## Configuration Options

```java
// Option 1: Simple URI
RedisRateLimiterConfig config = new RedisRateLimiterConfig("redis://localhost:6379");

// Option 2: RedisURI with authentication
RedisURI uri = RedisURI.builder()
    .withHost("redis.example.com")
    .withPort(6379)
    .withPassword("secret")
    .withDatabase(0)
    .withTimeout(Duration.ofSeconds(5))
    .build();
RedisRateLimiterConfig config = new RedisRateLimiterConfig(uri);

// Option 3: Use existing RedisClient
RedisClient client = RedisClient.create("redis://localhost:6379");
RedisRateLimiterConfig config = new RedisRateLimiterConfig(client);
```

## Best Practices

### 1. Connection Pooling

Reuse `RedisRateLimiterConfig` across requests:

```java
// DO: Singleton pattern
@Bean
public RedisRateLimiterConfig redisRateLimiterConfig() throws IOException {
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
RedisTokenBucketStore store = config.tokenBucketStore();   // loads the script here
```

A `NOSCRIPT` from Redis (for example after `SCRIPT FLUSH`) is **not** an error: the store falls back
to `EVAL` and reloads the script.

### Invalid Script Result

**Error**: `ScriptExecutionException: Lua script returned invalid result`

**Solution**: The script must return exactly **7** integers:
```
[allowed, rejecting_band_index, min_remaining, micros_to_wait,
 reset_time_millis, limit, binding_band_index]
```
This normally only happens if you replaced `token_bucket_consume.lua` on the classpath. See
[Lua Script Contract](#lua-script-contract).

### High Memory Usage

**Symptom**: Redis memory grows unbounded

**Check TTL**:
```bash
redis-cli TTL "fluxgate:bucket:{api-limits:rule-1:ip:203.0.113.10}:100-per-60s"
```

Expected TTL is `ceil(window_seconds * 1.1)` — for a 60s window, 66. If it is `-1` (no expiration),
the key was not written by this version of the script.

**Count buckets**:
```bash
redis-cli --scan --pattern 'fluxgate:bucket:*' | wc -l
```

There is no TTL cap any more, so a rule with a very long window keeps its buckets for that long. Use
long windows only with scopes whose cardinality is bounded — a 30-day quota on `PER_IP` keeps one key
per distinct IP for 30 days.

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
| `org.fluxgate.redis.connection.RedisConnectionException` | 0.4.0 | `org.fluxgate.core.exception.RedisConnectionException`, which it now extends — existing `catch` blocks still compile and still catch |
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
