# FluxGate Sample - Redis (Raw RateLimiter API)

This sample demonstrates **Redis-backed rate limiting using the raw `RateLimiter` API** — the
controller calls `rateLimiter.tryConsume()` directly and crafts its own response, rather than
delegating to the FluxGate servlet filter.  Use
[`fluxgate-sample-filter`](../fluxgate-sample-filter) if you want the filter mode with
automatic `X-RateLimit-*` / `RateLimit-*` headers.

## Key Features

- **High-performance rate limiting** — Redis-backed token bucket algorithm
- **Distributed rate limiting** — limits shared across multiple application instances
- **Atomic operations** — Lua scripts ensure consistency
- **Programmatic API** — direct `RateLimiter.tryConsume()` usage, full control of the response

## Prerequisites

```bash
# Start Redis (single-node, no auth)
docker run -d --name redis -p 6379:6379 redis:latest
```

## Quick Start

### 1. Run the Application

```bash
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-redis
```

The application starts on port **8082**.

### 2. Seed a Rule

The sample ships with a programmatic `RateLimitConfig` that registers the `api-limits` rule set
at startup — no manual seeding required.

### 3. Test Rate Limiting

```bash
# Send 12 requests rapidly (limit: 10 per minute)
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

### 4. Inspect the 429 Response

```bash
curl -i http://localhost:8082/api/hello
```

When rate-limited, the controller returns **only `Retry-After`** (set manually); it does **not**
emit `X-RateLimit-*` or `RateLimit-*` headers because those are added by the FluxGate filter,
which this sample bypasses.

```
HTTP/1.1 429 Too Many Requests
Retry-After: 60
Content-Type: application/json

{
  "status": "REJECTED",
  "error": "Rate limit exceeded",
  "requestNumber": 11,
  "ruleSetId": "api-limits",
  "retryAfterSeconds": 60,
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

## Project Structure

```
fluxgate-sample-redis/
├── src/main/java/org/fluxgate/sample/redis/
│   ├── RedisSampleApplication.java          # Main application
│   ├── config/
│   │   ├── RateLimitConfig.java             # RuleSet registration bean
│   │   └── DynamicRuleSetProvider.java      # In-memory RuleSet registry
│   └── controller/
│       └── ApiController.java               # Raw-API endpoints
└── src/main/resources/
    └── application.yml                      # Configuration
```

## Configuration

### application.yml

```yaml
server:
  port: 8082

fluxgate:
  mongo:
    enabled: false
  redis:
    enabled: true
    uri: redis://localhost:6379
  ratelimit:
    enabled: true
    default-rule-set-id: api-limits
    include-patterns:
      - /api/**
    exclude-patterns:
      - /actuator/**
      - /health
    trust-client-ip-header: true
    include-headers: true
  reload:
    enabled: false   # workaround for circular-dep in FluxgateReloadAutoConfiguration
```

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

```bash
# View rate limit buckets in Redis
redis-cli KEYS "fluxgate:bucket:*"

# Check bucket state
redis-cli HGETALL "fluxgate:bucket:api-limits:127.0.0.1"
```

Output:
```
1) "tokens"
2) "8"
3) "last_refill"
4) "1701234567000"
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

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `fluxgate.redis.enabled` | `false` | Enable Redis backend |
| `fluxgate.redis.uri` | - | Redis URI (standalone or cluster) |
| `fluxgate.ratelimit.enabled` | `true` | Master switch |
| `fluxgate.ratelimit.default-rule-set-id` | - | Default RuleSet ID |
| `fluxgate.ratelimit.include-patterns` | `[]` | URL patterns to rate-limit |
| `fluxgate.ratelimit.exclude-patterns` | `[]` | URL patterns to exclude |
| `fluxgate.ratelimit.trust-client-ip-header` | `false` | Trust `X-Forwarded-For` |
| `fluxgate.ratelimit.include-headers` | `true` | Emit rate-limit headers (filter mode only) |
| `fluxgate.reload.enabled` | `true` | Enable hot-reload (set `false` to avoid circular-dep) |

## API Endpoints

| Method | Path | Description | Rate Limited |
|--------|------|-------------|:------------:|
| GET | `/api/hello` | Hello endpoint (default `api-limits` rule) | via raw API |
| GET | `/api/test` | Test endpoint (ruleSetId query param) | via raw API |
| GET | `/api/status` | Service status + available rule sets | ❌ |
| GET | `/health` | Health check | ❌ |

> **Note:** Rate limiting here is enforced in-controller, not by the FluxGate filter.
> The `X-RateLimit-*` and `RateLimit-*` response headers (RFC 6585 / IETF draft) are therefore
> **absent**. To see those headers, use [`fluxgate-sample-filter`](../fluxgate-sample-filter).

## Production Considerations

### Lua Script Atomicity

All rate limiting operations use Lua scripts for atomicity:

```lua
-- token_bucket_consume.lua (simplified)
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local tokens_to_consume = tonumber(ARGV[3])

-- Get current state
local bucket = redis.call('HGETALL', key)

-- Calculate tokens to add based on elapsed time
local elapsed = current_time - last_refill
local tokens_to_add = elapsed * (capacity / window_ms)

-- Attempt to consume
if available_tokens >= tokens_to_consume then
    redis.call('HSET', key, 'tokens', available_tokens - tokens_to_consume)
    return {1, available_tokens - tokens_to_consume, reset_time}
else
    return {0, available_tokens, reset_time}  -- Rejected
end
```

### High Availability

```yaml
# Redis Sentinel
fluxgate:
  redis:
    uri: redis-sentinel://sentinel1:26379,sentinel2:26379/mymaster

# Redis Cluster
fluxgate:
  redis:
    uri: redis://node1:6379,node2:6379,node3:6379
```

## Comparison with Other Samples

| Feature | Redis (this) | Filter | Mongo | Standalone |
|---------|:------------:|:------:|:-----:|:----------:|
| Rate limiting | ✅ | ✅ | ❌ | ✅ |
| Dynamic rules | ❌ | ✅ | ✅ | ✅ |
| Rule storage | Config | Redis | MongoDB | Both |
| Standard headers | ❌ | ✅ | ❌ | ✅ |
| Use case | Raw API demo | Simple filter | Control-plane | Full stack |

## When to Use This Sample

- Learning the **programmatic `RateLimiter` API** directly
- Building custom response shapes or async flows
- **Microservices** that already have their own HTTP middleware

## Next Steps

- [FluxGate Samples Overview](../README.md)
- [fluxgate-sample-filter](../fluxgate-sample-filter) — For filter mode with standard rate-limit headers
- [fluxgate-sample-mongo](../fluxgate-sample-mongo) — For MongoDB control-plane functionality
