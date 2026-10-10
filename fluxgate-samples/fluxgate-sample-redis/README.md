# FluxGate Sample - Redis (Data-plane, Raw RateLimiter API)

This sample demonstrates **Redis-backed rate limiting using the raw `RateLimiter` API**. The
controllers call `rateLimiter.tryConsume()` directly and build their own responses; there is no
`@EnableFluxgateFilter` and no FluxGate servlet filter. Use
[`fluxgate-sample-filter`](../fluxgate-sample-filter) for filter mode with automatic
`X-RateLimit-*` / `RateLimit-*` headers.

It is also the **Data-plane** of the other samples:
[`fluxgate-sample-filter`](../fluxgate-sample-filter) calls its `POST /api/ratelimit/check`, and
[`fluxgate-sample-api`](../fluxgate-sample-api) registers rule sets through its `POST /admin/rules`
and proxies `/api/*` to it.

## Key Features

- **Redis token bucket** - `RedisRateLimiter` (Lua scripts, atomic per bucket), limits shared by
  every instance that uses the same Redis
- **Programmatic API** - `GET /api/test` and `GET /api/hello` call `RateLimiter.tryConsume()` and
  answer 429 with a `Retry-After` header
- **Rate limit check API** - `POST /api/ratelimit/check` decides for a caller-supplied context
  (used by `fluxgate-sample-filter`'s `HttpRateLimitHandler`)
- **Dynamic rule sets** - `/admin/rules` registers, lists and deletes rule sets at runtime;
  `DynamicRuleSetProvider` builds a `RateLimitRuleSet` from them on every request

Rule sets are stored in Redis with `RedisRuleSetStore` / `RuleSetData` (one capacity and window per
rule set, always per client IP when registered through `/admin/rules`). Both classes are
**deprecated since 0.4.0**; new applications should keep rules in MongoDB behind a
`RateLimitRuleSetProvider`, as `fluxgate-sample-standalone-java21` does.

## Prerequisites

```bash
# Start Redis (single node, no auth)
docker run -d --name redis -p 127.0.0.1:6379:6379 redis:7.2.5-alpine
```

## Quick Start

### 1. Run the Application

> Run `./mvnw -B install -DskipTests` (JDK 21) once from the project root first, or
> `./mvnw -B install -DskipTests -pl fluxgate-samples/fluxgate-sample-redis -am` (JDK 17+):
> the samples depend on the `0.4.0-SNAPSHOT` modules (see [Build Once](../README.md#build-once)).

```bash
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-redis
```

The application starts on port **8082**.

### 2. Default Rule Set

No seeding is needed. At startup `DynamicRuleSetProvider` (and `RateLimitController`) store the rule
set `api-limits` in Redis if it does not exist yet: **10 requests per 60 seconds per client IP**.

### 3. Test Rate Limiting

```bash
# Send 12 requests rapidly (limit: 10 per 60 seconds)
for i in {1..12}; do
  echo -n "Request $i: "
  curl -s -o /dev/null -w "%{http_code}" http://localhost:8082/api/hello
  echo ""
done
```

Expected output:
```
Request 1: 200
Request 2: 200
...
Request 10: 200
Request 11: 429  # Rate limited!
Request 12: 429
```

### 4. Inspect the Responses

```bash
curl -i http://localhost:8082/api/hello
```

When rate-limited, the controller sets **only `Retry-After`**. It does **not** emit `X-RateLimit-*`
or `RateLimit-*` headers, because those are written by the FluxGate filter, which this sample does
not use. `Retry-After` and `retryAfterSeconds` are the whole seconds until the next token (at least
1; with 10 per 60 s a token refills every 6 s):

```
HTTP/1.1 429
Retry-After: 5
Content-Type: application/json

{
  "status": "REJECTED",
  "error": "Rate limit exceeded",
  "requestNumber": 11,
  "ruleSetId": "api-limits",
  "retryAfterSeconds": 5,
  "clientIp": "127.0.0.1",
  "timestamp": "2026-10-01T00:00:00Z"
}
```

A 200 response body looks like:
```json
{
  "status": "ALLOWED",
  "requestNumber": 1,
  "ruleSetId": "api-limits",
  "remainingTokens": 9,
  "clientIp": "127.0.0.1",
  "timestamp": "2026-10-01T00:00:00Z"
}
```

Field order varies (the body is a `Map`). `requestNumber` is one counter for `/api/test` and
`/api/hello` together. `clientIp` is the socket address (`request.getRemoteAddr()`): forwarding
headers are never read, and `localhost` may resolve to IPv6 (`0:0:0:0:0:0:0:1`).

### 5. Register Your Own Rule Set

```bash
# 5 requests per 10 seconds, as rule set "test-limits"
curl -X POST http://localhost:8082/admin/rules/sample

# or any capacity and window
curl -X POST http://localhost:8082/admin/rules \
  -H 'Content-Type: application/json' \
  -d '{"ruleSetId": "my-limits", "capacity": 3, "windowSeconds": 30}'

curl "http://localhost:8082/api/test?ruleSetId=my-limits"
```

## API Endpoints

### Rate-limited API (`ApiController`, `/api`)

| Method | Path | Description | Rate Limited |
|--------|------|-------------|:------------:|
| GET | `/api/test?ruleSetId=` | Consumes one token of the rule set (default `api-limits`); 200, 429 with `Retry-After`, or 400 with `availableRuleSets` for an unknown rule set | ✅ (in the controller) |
| GET | `/api/hello` | Same as `/api/test?ruleSetId=api-limits` | ✅ (in the controller) |
| GET | `/api/status` | `status`, `totalRequests`, `availableRuleSets`, `timestamp` | ❌ |

### Rate Limit Check API (`RateLimitController`, `/api/ratelimit`)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/ratelimit/check` | Decides for the JSON context in the body and always answers 200 |

```bash
curl -X POST http://localhost:8082/api/ratelimit/check \
  -H 'Content-Type: application/json' \
  -d '{"ruleSetId": "api-limits", "clientIp": "203.0.113.7", "endpoint": "/api/hello", "method": "GET"}'
```

Request fields: `ruleSetId`, `clientIp`, `userId`, `apiKey`, `endpoint`, `method`. Response:
`{"allowed": true, "remaining": 9, "retryAfterMs": 0}`, or `{"allowed": false, "remaining": 0,
"retryAfterMs": 5999}` when rejected. An **unknown rule set is allowed** (`"remaining": -1`): the
check fails open. The key scope follows the stored `keyStrategyId` (`userId` -> `PER_USER`, `apiKey`
-> `PER_API_KEY`, anything else -> `PER_IP`); rule sets registered through `/admin/rules` are always
`clientIp`, so per IP.

### Rule Set Admin (`RuleAdminController`, `/admin/rules`)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/admin/rules` | Register or replace a rule set; JSON body `{"ruleSetId", "capacity", "windowSeconds"}`; 201 |
| POST | `/admin/rules/sample` | Register `test-limits` (5 per 10 s); 201 |
| GET | `/admin/rules` | `{"ruleSets": [...], "count": n}` |
| DELETE | `/admin/rules/{ruleSetId}` | Delete one rule set; 204, or 404 if unknown |
| DELETE | `/admin/rules` | Delete every rule set and re-register `api-limits`; 200 |

Swagger UI: `http://localhost:8082/swagger-ui.html`.

## Project Structure

```
fluxgate-sample-redis/
├── src/main/java/org/fluxgate/sample/redis/
│   ├── RedisSampleApplication.java          # Main application
│   ├── config/
│   │   ├── DynamicRuleSetProvider.java      # RateLimitRuleSetProvider over RedisRuleSetStore
│   │   ├── OpenApiConfig.java               # Swagger / OpenAPI configuration
│   │   └── RedisConfig.java                 # RedisRateLimiterConfig, RateLimiter, RedisRuleSetStore
│   └── controller/
│       ├── ApiController.java               # /api/test, /api/hello, /api/status
│       ├── RateLimitController.java         # POST /api/ratelimit/check
│       └── RuleAdminController.java         # /admin/rules
├── src/main/resources/
│   └── application.yml                      # Configuration
└── src/test/java/org/fluxgate/sample/redis/
    ├── RedisSampleStartupTest.java          # shipped application.yml, Testcontainers Redis
    └── config/OpenApiConfigTest.java
```

## Configuration

### application.yml

```yaml
server:
  port: 8082

spring:
  application:
    name: fluxgate-sample-redis

# FluxGate Configuration - Data-plane (Redis, RateLimiter API called by the controllers)
fluxgate:
  mongo:
    enabled: false  # No MongoDB in data-plane
  redis:
    enabled: true
    # Standalone mode (default)
    uri: redis://localhost:6379
    # mode: auto  # auto-detect from URI (default)
    # timeout-ms: 5000  # connection timeout in milliseconds

    # For Redis Cluster, use comma-separated URIs:
    # uri: redis://node1:6379,redis://node2:6379,redis://node3:6379
    # Or explicitly set mode:
    # mode: cluster
  ratelimit:
    enabled: true
    default-rule-set-id: api-limits
    include-patterns:
      - /api/**
    exclude-patterns:
      - /actuator/**
      - /health
    # Honour X-Forwarded-For only when a proxy you control sets it AND is listed in trusted-proxies;
    # otherwise any caller can forge its own IP bucket.
    client-ip-header: X-Forwarded-For
    trust-client-ip-header: ${FLUXGATE_TRUST_CLIENT_IP_HEADER:false}
    # trusted-proxies: [10.0.0.0/8]

logging:
  level:
    org.fluxgate: DEBUG
    org.fluxgate.sample: DEBUG
```

The sample's own code reads only `fluxgate.redis.uri` (`RedisConfig`, default
`redis://localhost:6379`). The `fluxgate.ratelimit.*` keys configure the starter's servlet filter,
which is only registered by `@EnableFluxgateFilter`, and the `@RateLimit` aspect, which needs
`@EnableFluxgateAspect`; this sample has neither, so they have no effect here. They are kept to show
the settings a filter-based data-plane would use.

### Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `fluxgate.redis.enabled` | `false` | Enable the starter's Redis auto-configuration |
| `fluxgate.redis.uri` | `redis://localhost:6379` | Redis URI; comma-separated URIs select cluster mode |
| `fluxgate.ratelimit.enabled` | `true` | Master switch for the filter and the `@RateLimit` aspect |
| `fluxgate.ratelimit.default-rule-set-id` | unset | Rule set of the filter (filter mode only) |
| `fluxgate.ratelimit.include-patterns` | unset (-> `@EnableFluxgateFilter.includePatterns`, and when that is empty too `/**`) | URL patterns to rate-limit (filter mode only) |
| `fluxgate.ratelimit.exclude-patterns` | unset (-> `@EnableFluxgateFilter.excludePatterns`) | URL patterns to exclude (filter mode only) |
| `fluxgate.ratelimit.client-ip-header` | `X-Forwarded-For` | Header read for the client IP when trusted |
| `fluxgate.ratelimit.trust-client-ip-header` | `false` | Trust that header (only behind a proxy listed in `trusted-proxies`) |
| `fluxgate.ratelimit.include-headers` | `true` | Emit rate limit headers (filter mode only) |
| `fluxgate.reload.enabled` | `true` | Rule hot reload |

## How It Works

### Token Bucket Algorithm

FluxGate uses the token bucket algorithm for rate limiting:

```
┌─────────────────────────────────────────────────────────┐
│                    Token Bucket                         │
├─────────────────────────────────────────────────────────┤
│                                                         │
│   Capacity: 10 tokens                                   │
│   Refill Rate: 10 tokens per 60 seconds                 │
│                                                         │
│   ┌─────────────────────────────────────────────────┐   │
│   │  [●][●][●][●][●][●][●][●][●][●]  = 10 tokens   │   │
│   └─────────────────────────────────────────────────┘   │
│                          │                              │
│                          ▼                              │
│                    Request comes in                     │
│                          │                              │
│              ┌───────────┴───────────┐                  │
│              │                       │                  │
│        Token available?        No tokens?               │
│              │                       │                  │
│              ▼                       ▼                  │
│         ✅ Allow               ❌ Reject               │
│         (consume 1)            (429 status)             │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### Redis Data Structure

Rule sets are hashes under `fluxgate:ruleset:<ruleSetId>`, indexed by the set `fluxgate:rulesets`.
`DynamicRuleSetProvider` turns each into one rule, `<ruleSetId>-rule`, scoped per IP, with one band
labelled `<capacity>-per-<windowSeconds>s`.

Bucket keys have the form `fluxgate:bucket:{<ruleSetId>:<ruleId>:<keyValue>}:<bandLabel>`. For the
default rule set `api-limits`:

```bash
# View rate limit buckets in Redis (SCAN, not KEYS, which blocks the server)
redis-cli --scan --pattern 'fluxgate:bucket:*'

# Check bucket state
redis-cli HGETALL 'fluxgate:bucket:{api-limits:api-limits-rule:ip:127.0.0.1}:10-per-60s'

# Stored rule set
redis-cli HGETALL 'fluxgate:ruleset:api-limits'
```

Bucket output:
```
1) "tokens"
2) "8"
3) "last_refill_micros"
4) "1701234567000000"
```

## Distributed Rate Limiting

Multiple instances share the same Redis, enabling distributed rate limiting:

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Instance 1 │     │  Instance 2 │     │  Instance 3 │
│  (8082)     │     │  (8083)     │     │  (8084)     │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │
       └───────────────────┼───────────────────┘
                           │
                    ┌──────▼──────┐
                    │    Redis    │
                    │             │
                    │ Shared rate │
                    │   limits    │
                    └─────────────┘
```

Run extra instances with `--server.port=8083` and so on (`fluxgate-sample-filter` itself uses 8083).

## Production Considerations

### Lua Script Atomicity

All rate limiting operations use Lua scripts for atomicity:

```lua
-- token_bucket_consume.lua (SIMPLIFIED: one TOKEN_BUCKET band, no validation, no sliding or
-- fixed windows, no check-only mode, no multi-band reject handling - read the real script for those)
-- KEYS[1] = fluxgate:bucket:{<ruleSetId>:<ruleId>:<keyValue>}:<bandLabel>
local permits         = tonumber(ARGV[1])  -- tokens to consume, usually 1
local max_ttl_seconds = tonumber(ARGV[2])  -- fluxgate.redis.max-bucket-ttl
-- Per band (5 arguments each): capacity, window_micros, algorithm_code, buckets_or_zero,
-- window_end_micros_or_zero
local capacity        = tonumber(ARGV[3])
local win_micros      = tonumber(ARGV[4])

-- One clock for every node: Redis TIME, in microseconds
local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000000 + tonumber(t[2])

local data   = redis.call('HMGET', KEYS[1], 'tokens', 'last_refill_micros')
local tokens = tonumber(data[1]) or capacity       -- a missing bucket starts full
local last   = tonumber(data[2]) or now

-- Credit whole tokens only; the timestamp advances by what they cost (remainder carries over)
local elapsed = math.min(math.max(0, now - last), win_micros)
local to_add  = math.floor(elapsed * capacity / win_micros)
last   = last + math.floor(to_add * win_micros / capacity)
tokens = math.min(capacity, tokens + to_add)
if tokens >= capacity then last = now end

if tokens < permits then
    -- Rejected: no state is written, only the TTL is refreshed
    redis.call('EXPIRE', KEYS[1], math.min(max_ttl_seconds, math.max(1, math.ceil(win_micros / 1000000 * 1.1))))
    return {0, 1, tokens, math.ceil((permits - tokens) * win_micros / capacity), ...}
end

redis.call('HMSET', KEYS[1], 'tokens', tokens - permits, 'last_refill_micros', last)
redis.call('EXPIRE', KEYS[1], math.min(max_ttl_seconds, math.max(1, math.ceil(win_micros / 1000000 * 1.1))))
-- Real return value: {allowed, rejecting_band_index, min_remaining, micros_to_wait,
--                     reset_time_millis, limit, binding_band_index, now_micros}
return {1, 0, tokens - permits, 0, ...}
```

### Redis Cluster

`RedisRateLimiterConfig` switches to cluster mode when the URI lists several nodes, each with its
own scheme (Redis Sentinel URIs are not supported):

```yaml
fluxgate:
  redis:
    uri: redis://node1:6379,redis://node2:6379,redis://node3:6379
```

## Comparison with Other Samples

| Feature | Redis (this) | Filter | Mongo | Standalone |
|---------|:------------:|:------:|:-----:|:----------:|
| Rate limiting | ✅ (in the controller) | ✅ (filter, decision from this sample over HTTP) | ❌ | ✅ (filters + `@RateLimit`) |
| Dynamic rules | ✅ (`/admin/rules`) | ❌ (rules live in this sample) | ✅ (stored, not enforced) | ✅ (`/api/admin/rules/*`) |
| Rule storage | Redis (`RedisRuleSetStore`, deprecated) | none | MongoDB | MongoDB |
| Standard headers | ❌ (`Retry-After` only) | ✅ | ❌ | ✅ |
| Use case | Raw API demo, Data-plane | Simple filter | Control-plane | Full stack |

## When to Use This Sample

- Learning the **programmatic `RateLimiter` API** directly
- Building custom response shapes or async flows
- **Microservices** that already have their own HTTP middleware

## Next Steps

- [FluxGate Samples Overview](../README.md)
- [fluxgate-sample-filter](../fluxgate-sample-filter) — For filter mode with standard rate-limit headers
- [fluxgate-sample-mongo](../fluxgate-sample-mongo) — For MongoDB control-plane functionality
