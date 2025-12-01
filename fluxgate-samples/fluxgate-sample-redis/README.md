# FluxGate Sample - Redis (Data-plane)

This sample demonstrates **Redis-based rate limiting** using the token bucket algorithm. It represents the **data-plane** component that handles high-throughput rate limiting decisions.

## Key Features

- **High-performance rate limiting** - Redis-backed token bucket algorithm
- **Distributed rate limiting** - Share limits across multiple application instances
- **Atomic operations** - Lua scripts ensure consistency
- **Configuration-based rules** - Define rules in application.yml

## Prerequisites

```bash
# Start Redis
docker run -d --name redis -p 6379:6379 redis:latest
```

## Quick Start

### 1. Run the Application

```bash
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-redis
```

The application starts on port **8082**.

### 2. Test Rate Limiting

```bash
# Send 15 requests rapidly
for i in {1..15}; do
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
...
```

### 3. Check Rate Limit Headers

```bash
curl -i http://localhost:8082/api/hello
```

Response headers:
```
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 9
X-RateLimit-Reset: 1701234567
```

## Project Structure

```
fluxgate-sample-redis/
├── src/main/java/org/fluxgate/sample/redis/
│   ├── RedisSampleApplication.java      # Main application
│   ├── config/
│   │   └── RateLimitConfig.java         # RuleSet configuration
│   └── controller/
│       └── ApiController.java           # Rate-limited endpoints
└── src/main/resources/
    └── application.yml                  # Configuration
```

## Configuration

### application.yml

```yaml
server:
  port: 8082

fluxgate:
  mongo:
    enabled: false  # No MongoDB in data-plane
  redis:
    enabled: true
    uri: redis://localhost:6379
  ratelimit:
    enabled: true
    filter-enabled: true
    default-rule-set-id: api-limits
    include-patterns:
      - /api/**
    exclude-patterns:
      - /actuator/**
      - /health
    client-ip-header: X-Forwarded-For
    trust-client-ip-header: true
    include-headers: true
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
| `fluxgate.redis.enabled` | `false` | Enable Redis |
| `fluxgate.redis.uri` | - | Redis URI |
| `fluxgate.ratelimit.filter-enabled` | `false` | Enable HTTP filter |
| `fluxgate.ratelimit.default-rule-set-id` | - | Default RuleSet |
| `fluxgate.ratelimit.include-patterns` | `[]` | URLs to rate limit |
| `fluxgate.ratelimit.exclude-patterns` | `[]` | URLs to exclude |
| `fluxgate.ratelimit.client-ip-header` | - | Header for client IP |
| `fluxgate.ratelimit.trust-client-ip-header` | `false` | Trust proxy header |
| `fluxgate.ratelimit.include-headers` | `true` | Add rate limit headers |

## API Endpoints

| Method | Path | Description | Rate Limited |
|--------|------|-------------|:------------:|
| GET | `/api/hello` | Hello endpoint | ✅ |
| GET | `/api/users` | Users endpoint | ✅ |
| GET | `/health` | Health check | ❌ |

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

### Redis Connection Pool

```yaml
# For production, consider connection pooling
fluxgate:
  redis:
    enabled: true
    uri: redis://localhost:6379
    # Connection pool settings are managed by Lettuce
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

| Feature | Redis (this) | Filter | Mongo | API |
|---------|:------------:|:------:|:-----:|:---:|
| Rate limiting | ✅ | ✅ | ❌ | ✅ |
| Dynamic rules | ❌ | ✅ | ✅ | ✅ |
| Rule storage | Config | Redis | MongoDB | Both |
| Use case | Data-plane | Simple | Control | Full |

## When to Use This Sample

- **High-throughput rate limiting** without dynamic rule management
- **Microservices** that need distributed rate limiting
- **Standalone data-plane** deployment
- **Configuration-driven** rule definitions

## Next Steps

- [FluxGate Samples Overview](../README.md)
- [fluxgate-sample-filter](../fluxgate-sample-filter) - For dynamic Redis-backed rules
- [fluxgate-sample-mongo](../fluxgate-sample-mongo) - For control-plane functionality
