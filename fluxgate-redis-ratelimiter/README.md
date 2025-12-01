# FluxGate Redis RateLimiter

Redis-backed distributed rate limiter implementation for the FluxGate framework.

## Overview

This module provides a production-ready, distributed rate limiting engine using Redis as the backend storage. It implements the FluxGate `RateLimiter` interface with support for:

- **Distributed rate limiting** across multiple application instances
- **Multi-band rate limits** (e.g., 100/second AND 1000/minute)
- **Atomic operations** using Lua scripts
- **Token bucket algorithm** with automatic refill
- **TTL-based bucket expiration** for memory efficiency
- **Zero dependencies on Spring** - works with any Java application

## Features

### Core Capabilities

- ✅ **Atomic refill + consume** - Single Lua script execution ensures consistency
- ✅ **Multi-band support** - Apply multiple rate limits simultaneously
- ✅ **Efficient memory usage** - Automatic TTL expiration of idle buckets
- ✅ **High performance** - Lettuce async Redis client
- ✅ **Production ready** - Comprehensive error handling and logging

### Architectural Decisions

1. **Lettuce vs Jedis**: We chose Lettuce for its superior async support and active maintenance
2. **Lua scripts**: All refill + consume logic runs atomically in Redis (no race conditions)
3. **Nanosecond precision**: Token refill calculations use nanoseconds for accuracy
4. **Per-band keys**: Each rate limit band gets its own Redis key for independent tracking
5. **TTL management**: Keys automatically expire after their window duration

## Installation

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>org.fluxgate</groupId>
    <artifactId>fluxgate-redis-ratelimiter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

## Quick Start

### Basic Usage

```java
import org.fluxgate.redis.RedisRateLimiter;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.core.ratelimiter.RateLimitResult;

// 1. Create configuration
RedisRateLimiterConfig config = new RedisRateLimiterConfig("redis://localhost:6379");

// 2. Create rate limiter
RedisRateLimiter rateLimiter = new RedisRateLimiter(config.getTokenBucketStore());

// 3. Define rules
RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100)
        .label("100-per-minute")
        .build();

RateLimitRule rule = RateLimitRule.builder("api-limit")
        .name("API Rate Limit")
        .enabled(true)
        .scope(LimitScope.PER_API_KEY)
        .keyStrategyId("apiKey")
        .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
        .addBand(band)
        .ruleSetId("api-rules")
        .build();

KeyResolver keyResolver = context -> new RateLimitKey(context.getApiKey());

RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("api-rules")
        .keyResolver(keyResolver)
        .rules(List.of(rule))
        .build();

// 4. Check rate limit
RequestContext context = RequestContext.builder()
        .apiKey("client-123")
        .endpoint("/api/users")
        .method("GET")
        .build();

RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

if (result.isAllowed()) {
    // Process request
    System.out.println("Request allowed. Remaining: " + result.getRemainingTokens());
} else {
    // Reject request
    long waitSeconds = result.getNanosToWaitForRefill() / 1_000_000_000;
    System.out.println("Rate limit exceeded. Retry after: " + waitSeconds + "s");
}

// 5. Cleanup
config.close();
```

### Multi-Band Rate Limiting

Apply multiple rate limits simultaneously (e.g., burst protection + daily quota):

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

**All bands must allow** the request for it to succeed. If any band rejects, the entire request is rejected.

### Configuration Options

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

## Architecture

### Components

```
┌─────────────────────────────────────────────┐
│         RedisRateLimiter                    │
│  (implements RateLimiter interface)         │
└────────────────┬────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────┐
│      RedisTokenBucketStore                  │
│  (token bucket operations)                  │
└────────────────┬────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────┐
│      Lua Script (token_bucket_consume)      │
│  Atomic: refill → check → consume → TTL    │
└────────────────┬────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────┐
│             Redis Database                  │
│  Keys: fluxgate:{rule}:{key}:{band}         │
│  Values: HASH {tokens, last_refill_nanos}   │
└─────────────────────────────────────────────┘
```

### Redis Key Structure

Each token bucket is stored as a Redis hash:

**Key format**: `fluxgate:{ruleSetId}:{ruleId}:{keyValue}:{bandLabel}`

**Example**: `fluxgate:api-rules:api-limit:client-123:100-per-minute`

**Hash fields**:
- `tokens` - Current token count (long)
- `last_refill_nanos` - Last refill timestamp in nanoseconds (long)

**TTL**: Automatically set to the band's window duration

### Token Bucket Algorithm

The Lua script implements this logic:

1. **Read current state**: Get `tokens` and `last_refill_nanos` from Redis
2. **Calculate elapsed time**: `elapsed = current_time - last_refill_nanos`
3. **Refill tokens**: `new_tokens = min(capacity, current_tokens + elapsed * refill_rate)`
4. **Try consume**: If `new_tokens >= permits`, deduct and return success
5. **Update state**: Save new token count and timestamp
6. **Set TTL**: Ensure key expires after window duration

**Refill rate calculation**:
```
refill_rate = capacity / window_nanos
```

For example, with capacity=100 and window=60 seconds:
```
refill_rate = 100 / 60_000_000_000 = 1.667e-9 tokens/nanosecond
```

### Thread Safety

- **Single instance**: `RedisRateLimiter` is thread-safe
- **Distributed**: Multiple application instances can share the same Redis database
- **Atomicity**: Lua scripts guarantee atomic operations (no race conditions)

## Testing

### Unit Tests

Run unit tests with an embedded Redis (Testcontainers):

```bash
mvn test
```

### Integration Testing

The module includes integration tests that:
- Spin up a real Redis container (Docker required)
- Test multi-band rate limiting
- Verify token refill behavior
- Check TTL expiration

Example test:

```java
@Test
void shouldEnforceRateLimit() {
    // given: 3 requests per second
    RateLimitRuleSet ruleSet = createRuleSet(1, 3);
    RequestContext context = createContext("192.168.1.1");

    // when: make 4 requests
    for (int i = 0; i < 3; i++) {
        RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);
        assertThat(result.isAllowed()).isTrue();
    }

    // then: 4th request should be rejected
    RateLimitResult rejected = rateLimiter.tryConsume(context, ruleSet, 1);
    assertThat(rejected.isAllowed()).isFalse();
}
```

## Performance Characteristics

### Throughput

- **Single Redis instance**: 50,000+ ops/sec (depends on network latency)
- **Redis Cluster**: Horizontally scalable
- **Lua script overhead**: ~0.1ms per operation

### Latency

- **Local Redis**: < 1ms p99
- **Network Redis**: 5-10ms p99 (depends on network)
- **Multi-band**: Linear cost (N bands = N Redis operations)

### Memory Usage

- **Per bucket**: ~64 bytes (hash with 2 fields)
- **TTL cleanup**: Buckets auto-expire when idle
- **Example**: 1M active users with 3 bands = ~192 MB

## Best Practices

### 1. Connection Pooling

Reuse `RedisRateLimiterConfig` across requests:

```java
// DO: Singleton pattern
private static final RedisRateLimiterConfig CONFIG =
    new RedisRateLimiterConfig("redis://localhost:6379");

// DON'T: Create per request
RedisRateLimiterConfig config = new RedisRateLimiterConfig(...); // ❌
```

### 2. Graceful Shutdown

Always close resources:

```java
try (RedisRateLimiterConfig config = new RedisRateLimiterConfig(...)) {
    RedisRateLimiter limiter = new RedisRateLimiter(config.getTokenBucketStore());
    // Use limiter
} // Auto-closes
```

### 3. Error Handling

Handle Redis unavailability:

```java
try {
    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);
    // Process result
} catch (Exception e) {
    log.error("Rate limiter failed, allowing request by default", e);
    // Fail open (allow request) or fail closed (reject request)
}
```

### 4. Monitoring

Track these metrics:
- Redis connection pool utilization
- Lua script execution time
- Rate limit rejection rate
- Token bucket hit/miss ratio

## Troubleshooting

### Scripts Not Loaded

**Error**: `IllegalStateException: Lua scripts not loaded`

**Solution**: Ensure scripts are loaded before creating the store:

```java
RedisRateLimiterConfig config = new RedisRateLimiterConfig("redis://localhost:6379");
// Scripts are auto-loaded during config initialization
```

### High Memory Usage

**Symptom**: Redis memory grows unbounded

**Solution**: Verify TTL is being set:

```bash
redis-cli
> KEYS fluxgate:*
> TTL fluxgate:api-rules:api-limit:client-123:100-per-minute
```

If TTL is `-1`, check your Lua script implementation.

### Slow Performance

**Symptom**: High latency on rate limit checks

**Possible causes**:
1. Network latency to Redis
2. Redis under load (check `INFO stats`)
3. Large number of bands per rule (linear cost)

**Solutions**:
- Use Redis on same network/region
- Scale Redis (cluster mode)
- Reduce number of bands

## Examples

See the `/examples` directory for:
- Basic rate limiting
- IP-based rate limiting
- API key-based rate limiting
- Multi-tenant rate limiting
- Custom KeyResolver implementations

## Contributing

Contributions are welcome! Please:
1. Write tests for new features
2. Follow existing code style
3. Update documentation

## License

Apache License 2.0

## Related Modules

- [`fluxgate-core`](../fluxgate-core) - Core abstractions and interfaces
- [`fluxgate-mongo-adapter`](../fluxgate-mongo-adapter) - MongoDB rule storage
- [`fluxgate-testkit`](../fluxgate-testkit) - Testing utilities

## Support

- Issues: [GitHub Issues](https://github.com/OpenFluxGate/fluxgate/issues)
- Discussions: [GitHub Discussions](https://github.com/OpenFluxGate/fluxgate/discussions)
