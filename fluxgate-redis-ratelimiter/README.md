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
| **Integer Arithmetic** | All calculations use integer math - no floating-point precision loss |
| **Read-Only on Rejection** | Rejected requests do NOT modify bucket state - ensures fair rate limiting |
| **TTL Safety Margin** | Adds 10% buffer to TTL - prevents premature key expiration due to clock skew |
| **TTL Max Cap** | Caps TTL at 24 hours - prevents runaway TTLs from misconfiguration |

### Core Capabilities

- Atomic refill + consume - Single Lua script execution ensures consistency
- Multi-band support - Apply multiple rate limits simultaneously
- Efficient memory usage - Automatic TTL expiration of idle buckets
- High performance - Lettuce async Redis client
- Production ready - Comprehensive error handling and logging

## Installation

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>org.fluxgate</groupId>
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
// Close resources when done
config.close();
```

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
fluxgate:{ruleSetId}:{ruleId}:{keyValue}:{bandLabel}
```

**Examples**:

```
fluxgate:my-api-limits:per-ip-100:203.0.113.10:100-per-minute
fluxgate:api-limits:multi-band:user-123:10-per-second
fluxgate:api-limits:multi-band:user-123:100-per-minute
fluxgate:api-limits:multi-band:user-123:1000-per-hour
```

Each key is a Redis Hash with fields:
- `tokens` - Current token count
- `last_refill_nanos` - Timestamp of last refill (Redis TIME in nanoseconds)

## Behavior

### Token Bucket Algorithm

1. **Initial State**: Bucket starts full (capacity tokens) - allows initial burst
2. **Refill**: Tokens added based on elapsed time using integer arithmetic:
   ```
   tokens_to_add = (elapsed_nanos * capacity) / window_nanos
   ```
3. **Consume**: If `tokens >= permits`, consume and allow; otherwise reject
4. **Cap**: Tokens never exceed capacity

### When Bucket is Full (Request Rejected)

When a request is rejected:

1. **State is NOT modified** - Critical for fair rate limiting (production fix)
2. **Wait time calculated**:
   ```
   wait_nanos = (permits_needed * window_nanos) / capacity
   ```
3. **Reset time provided**: Unix timestamp (milliseconds) when bucket will be full again

### RateLimitResult Fields

| Field | Description |
|-------|-------------|
| `isAllowed()` | `true` if request was allowed, `false` if rejected |
| `getRemainingTokens()` | Tokens left after this request (current tokens if rejected) |
| `getNanosToWaitForRefill()` | Nanoseconds until enough tokens available (0 if allowed) |
| `getMatchedRule()` | The rule that matched/rejected this request |
| `getKey()` | The rate limit key used (e.g., client IP) |

### Mapping to HTTP Headers

Use `RateLimitResult` to set standard rate limit headers:

```java
RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

// Get the band configuration for limit value
RateLimitBand band = result.getMatchedRule().getBands().get(0);

// X-RateLimit-Limit: Maximum requests allowed in the window
response.setHeader("X-RateLimit-Limit", String.valueOf(band.getCapacity()));

// X-RateLimit-Remaining: Requests remaining in current window
response.setHeader("X-RateLimit-Remaining", String.valueOf(result.getRemainingTokens()));

// X-RateLimit-Reset: Unix timestamp (seconds) when the bucket resets
long resetEpochSeconds = System.currentTimeMillis() / 1000
    + (result.getNanosToWaitForRefill() / 1_000_000_000);
response.setHeader("X-RateLimit-Reset", String.valueOf(resetEpochSeconds));

if (!result.isAllowed()) {
    // Retry-After: Seconds to wait before retrying (rounded up)
    long retryAfterSeconds = (result.getNanosToWaitForRefill() + 999_999_999) / 1_000_000_000;
    response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
    response.setStatus(429); // Too Many Requests
}
```

**Example Response Headers (Allowed)**:

```http
HTTP/1.1 200 OK
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 42
X-RateLimit-Reset: 1701388800
```

**Example Response Headers (Rejected)**:

```http
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1701388800
Retry-After: 45
Content-Type: application/json

{"error": "Rate limit exceeded", "retryAfter": 45}
```

## Testing

### Integration Tests in fluxgate-testkit

The `fluxgate-testkit` module contains comprehensive integration tests:

| Test | Description |
|------|-------------|
| `shouldAllowFirst100RequestsThenReject101st` | Sequential E2E test: MongoDB rule storage -> Redis enforcement, 100 allowed, 101st rejected |
| `shouldEnforceRateLimitUnderConcurrentLoad` | Concurrency stress test: 20 threads x 50 requests = 1000 total, verifies exactly 100 allowed |

### Running Tests Locally

**1. Start MongoDB and Redis:**

```bash
# MongoDB
docker run -d \
  --name fluxgate-mongo \
  -p 27017:27017 \
  -e MONGO_INITDB_ROOT_USERNAME=fluxgate \
  -e MONGO_INITDB_ROOT_PASSWORD=fluxgate123 \
  -e MONGO_INITDB_DATABASE=fluxgate \
  mongo:7

# Redis
docker run -d \
  --name fluxgate-redis \
  -p 6379:6379 \
  redis:7
```

**2. Run the tests:**

```bash
# Run all integration tests
./mvnw test -pl fluxgate-testkit

# Run specific test class
./mvnw test -pl fluxgate-testkit \
  -Dtest=org.fluxgate.testkit.redis.MongoRedisRateLimitIntegrationTest

# Run only the concurrency test
./mvnw test -pl fluxgate-testkit \
  -Dtest=MongoRedisRateLimitIntegrationTest#shouldEnforceRateLimitUnderConcurrentLoad
```

### Environment Variables

Tests support environment variables for CI/CD:

| Variable | Default | Description |
|----------|---------|-------------|
| `FLUXGATE_MONGO_URI` | `mongodb://fluxgate:fluxgate123@localhost:27017/fluxgate?authSource=admin` | MongoDB connection string |
| `FLUXGATE_MONGO_DB` | `fluxgate` | MongoDB database name |
| `FLUXGATE_REDIS_URI` | `redis://localhost:6379` | Redis connection string |

### CI/CD

See `.github/workflows/ci.yml` for GitHub Actions configuration with MongoDB and Redis services.

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
    // Fail closed: reject request when Redis is down (more secure)
}
```

### 4. Monitoring

Track these metrics:
- Redis connection pool utilization
- Rate limit check latency (p50, p99)
- Rejection rate by rule/endpoint
- Redis memory usage

## Troubleshooting

### Scripts Not Loaded

**Error**: `IllegalStateException: Lua scripts not loaded`

**Solution**: Ensure `RedisRateLimiterConfig` is created before `RedisTokenBucketStore`:

```java
RedisRateLimiterConfig config = new RedisRateLimiterConfig("redis://localhost:6379");
// Scripts are auto-loaded during config initialization
```

### Invalid Script Result

**Error**: `IllegalStateException: Lua script returned invalid result`

**Solution**: Ensure the Lua script returns exactly 4 values:
```
[consumed, remaining_tokens, nanos_to_wait, reset_time_millis]
```

### High Memory Usage

**Symptom**: Redis memory grows unbounded

**Check TTL**:
```bash
redis-cli TTL "fluxgate:api-limits:rule-1:203.0.113.10:100-per-minute"
```

If TTL is `-1` (no expiration), check the Lua script's EXPIRE call.

## Related Modules

- [`fluxgate-core`](../fluxgate-core) - Core abstractions and interfaces
- [`fluxgate-mongo-adapter`](../fluxgate-mongo-adapter) - MongoDB rule storage
- [`fluxgate-testkit`](../fluxgate-testkit) - Integration tests

## License

Apache License 2.0

## Support

- Issues: [GitHub Issues](https://github.com/OpenFluxGate/fluxgate/issues)
- Discussions: [GitHub Discussions](https://github.com/OpenFluxGate/fluxgate/discussions)
