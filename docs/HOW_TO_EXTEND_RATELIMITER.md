# How to Extend RateLimiter

This guide explains how to create custom RateLimiter implementations by extending or implementing the core interfaces.

---

## 📋 Table of Contents

- [Extensibility Overview](#extensibility-overview)
- [Approach 1: Implement RateLimiter Interface (Recommended)](#approach-1-implement-ratelimiter-interface-recommended)
- [Approach 2: Extend Bucket4jRateLimiter](#approach-2-extend-bucket4jratelimiter)
- [Real-World Use Cases](#real-world-use-cases)
- [Best Practices](#best-practices)

---

## 🎯 Extensibility Overview

### Current Structure

```java
// Interface (can be implemented)
public interface RateLimiter {
    RateLimitResult tryConsume(RequestContext context,
                               RateLimitRuleSet ruleSet,
                               long permits);
}

// Implementation (can be extended)
public class Bucket4jRateLimiter implements RateLimiter {
    private final ConcurrentMap<BucketKey, Bucket> buckets;
    // ...
}
```

### Extensibility Options

| Component | Type | Can Implement? | Can Extend? | Recommended |
|-----------|------|----------------|-------------|-------------|
| `RateLimiter` | Interface | ✅ Yes | N/A | ✅ **Best for custom implementations** |
| `Bucket4jRateLimiter` | Class | N/A | ✅ Yes | ⚠️ Limited (private fields) |

---

## 🔧 Approach 1: Implement RateLimiter Interface (Recommended)

This is the **recommended approach** for creating custom rate limiter implementations.

### Use Cases

- **Redis-based distributed rate limiting**
- **Hazelcast in-memory data grid rate limiting**
- **Custom storage backend** (PostgreSQL, DynamoDB, etc.)
- **Hybrid implementations** (in-memory + persistent storage)
- **External service integration** (Kong, AWS API Gateway, etc.)

### Example 1: Redis-based RateLimiter

```java
package com.yourapp.ratelimit;

import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Redis-based distributed RateLimiter using Token Bucket algorithm
 *
 * This implementation allows multiple application instances to share
 * the same rate limiting state via Redis.
 */
public class RedisRateLimiter implements RateLimiter {

    private final RedisTemplate<String, Long> redisTemplate;

    public RedisRateLimiter(RedisTemplate<String, Long> redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
    }

    @Override
    public RateLimitResult tryConsume(RequestContext context,
                                      org.fluxgate.core.ratelimiter.RateLimitRuleSet ruleSet,
                                      long permits) {

        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");

        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }

        // 1. Resolve key
        RateLimitKey logicalKey = ruleSet.getKeyResolver().resolve(context);
        String redisKey = buildRedisKey(ruleSet.getId(), logicalKey);

        // 2. Get first rule (simplification)
        RateLimitRule rule = ruleSet.getRules().get(0);
        long capacity = rule.getBands().get(0).getCapacity();
        Duration window = rule.getBands().get(0).getWindow();

        // 3. Try to consume using Redis
        Long currentTokens = redisTemplate.opsForValue().get(redisKey);

        if (currentTokens == null) {
            // First request: initialize bucket
            currentTokens = capacity;
            redisTemplate.opsForValue().set(redisKey, currentTokens, window);
        }

        // 4. Check and consume
        if (currentTokens >= permits) {
            // Allowed: decrement tokens
            Long remaining = redisTemplate.opsForValue().decrement(redisKey, permits);

            return org.fluxgate.core.ratelimiter.RateLimitResult.allowed(
                    logicalKey,
                    rule,
                    remaining,
                    0L
            );
        } else {
            // Rejected: not enough tokens
            Long ttl = redisTemplate.getExpire(redisKey, TimeUnit.NANOSECONDS);

            return org.fluxgate.core.ratelimiter.RateLimitResult.rejected(
                    logicalKey,
                    rule,
                    ttl != null ? ttl : 0L
            );
        }
    }

    private String buildRedisKey(String ruleSetId, RateLimitKey key) {
        return "ratelimit:" + ruleSetId + ":" + key.getKey();
    }
}
```

### Example 2: Lua Script-based Redis RateLimiter (More Accurate)

```java
package com.yourapp.ratelimit;

import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.ratelimiter.RateLimitResult;
org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.Objects;

/**
 * Production-grade Redis RateLimiter using Lua scripts for atomic operations
 */
public class LuaScriptRedisRateLimiter implements RateLimiter {

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisScript<Long> rateLimitScript;

    public LuaScriptRedisRateLimiter(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.rateLimitScript = createRateLimitScript();
    }

    @Override
    public org.fluxgate.core.ratelimiter.RateLimitResult tryConsume(RequestContext context,
                                                                        RateLimitRuleSet ruleSet,
                                                                        long permits) {

        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");

        // Resolve key
        RateLimitKey logicalKey = ruleSet.getKeyResolver().resolve(context);
        String redisKey = "ratelimit:" + ruleSet.getId() + ":" + logicalKey.getKey();

        // Get rule configuration
        RateLimitRule rule = ruleSet.getRules().get(0);
        RateLimitBand band = rule.getBands().get(0);

        long capacity = band.getCapacity();
        long windowSeconds = band.getWindow().toSeconds();

        // Execute Lua script atomically
        Long allowed = redisTemplate.execute(
                rateLimitScript,
                Collections.singletonList(redisKey),
                String.valueOf(capacity),
                String.valueOf(windowSeconds),
                String.valueOf(permits),
                String.valueOf(System.currentTimeMillis() / 1000)
        );

        if (allowed != null && allowed > 0) {
            return RateLimitResult.allowed(logicalKey, rule, allowed, 0L);
        } else {
            return RateLimitResult.rejected(logicalKey, rule, windowSeconds * 1_000_000_000L);
        }
    }

    private RedisScript<Long> createRateLimitScript() {
        String script = """
                local key = KEYS[1]
                local capacity = tonumber(ARGV[1])
                local window = tonumber(ARGV[2])
                local permits = tonumber(ARGV[3])
                local now = tonumber(ARGV[4])
                
                local count_key = key .. ':count'
                local timestamp_key = key .. ':timestamp'
                
                local last_timestamp = tonumber(redis.call('get', timestamp_key))
                if not last_timestamp then
                    last_timestamp = now
                    redis.call('set', timestamp_key, now)
                    redis.call('expire', timestamp_key, window)
                end
                
                local elapsed = now - last_timestamp
                if elapsed >= window then
                    redis.call('set', count_key, 0)
                    redis.call('set', timestamp_key, now)
                    redis.call('expire', count_key, window)
                    redis.call('expire', timestamp_key, window)
                    elapsed = 0
                end
                
                local current = tonumber(redis.call('get', count_key)) or 0
                
                if current + permits <= capacity then
                    redis.call('incrby', count_key, permits)
                    redis.call('expire', count_key, window)
                    return capacity - current - permits
                else
                    return -1
                end
                """;

        return RedisScript.of(script, Long.class);
    }
}
```

### Example 3: Logging/Auditing RateLimiter Decorator

```java
package com.yourapp.ratelimit;

import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.context.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Decorator that adds logging and auditing to any RateLimiter implementation
 */
public class AuditingRateLimiter implements RateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(AuditingRateLimiter.class);

    private final RateLimiter delegate;
    private final AuditService auditService;

    public AuditingRateLimiter(org.fluxgate.core.ratelimiter.RateLimiter delegate, AuditService auditService) {
        this.delegate = Objects.requireNonNull(delegate);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public RateLimitResult tryConsume(RequestContext context,
                                      RateLimitRuleSet ruleSet,
                                      long permits) {

        long startTime = System.nanoTime();

        try {
            RateLimitResult result = delegate.tryConsume(context, ruleSet, permits);

            long duration = System.nanoTime() - startTime;

            if (result.isAllowed()) {
                logger.debug("Rate limit check ALLOWED: ruleSet={}, key={}, permits={}, duration={}ns",
                        ruleSet.getId(), result.getAppliedKey(), permits, duration);
            } else {
                logger.warn("Rate limit check REJECTED: ruleSet={}, key={}, permits={}, retryAfter={}s",
                        ruleSet.getId(), result.getAppliedKey(), permits,
                        result.getNanosToWaitForRefill() / 1_000_000_000.0);

                // Audit rejected requests
                auditService.recordRejection(
                        context.getClientIp(),
                        context.getEndpoint(),
                        ruleSet.getId(),
                        result.getAppliedKey().getKey()
                );
            }

            return result;

        } catch (Exception e) {
            logger.error("Rate limit check FAILED: ruleSet={}, error={}",
                    ruleSet.getId(), e.getMessage(), e);
            throw e;
        }
    }
}

// Usage
RateLimiter baseRateLimiter = new Bucket4jRateLimiter();
RateLimiter auditingRateLimiter = new AuditingRateLimiter(baseRateLimiter, auditService);
```

### Example 4: Circuit Breaker RateLimiter

```java
package com.yourapp.ratelimit;

import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.context.RequestContext;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RateLimiter with circuit breaker pattern
 *
 * If the underlying storage (Redis, etc.) fails repeatedly,
 * temporarily fail-open to prevent cascading failures.
 */
public class CircuitBreakerRateLimiter implements org.fluxgate.core.ratelimiter.RateLimiter {

    private final RateLimiter delegate;
    private final int failureThreshold;
    private final long resetTimeoutMillis;

    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);

    private enum State {
        CLOSED,  // Normal operation
        OPEN,    // Failing, bypass rate limiting
        HALF_OPEN // Testing if backend recovered
    }

    public CircuitBreakerRateLimiter(RateLimiter delegate,
                                     int failureThreshold,
                                     long resetTimeoutMillis) {
        this.delegate = delegate;
        this.failureThreshold = failureThreshold;
        this.resetTimeoutMillis = resetTimeoutMillis;
    }

    @Override
    public org.fluxgate.core.ratelimiter.RateLimitResult tryConsume(RequestContext context,
                                                                        org.fluxgate.core.ratelimiter.RateLimitRuleSet ruleSet,
                                                                        long permits) {

        State state = getState();

        if (state == State.OPEN) {
            // Circuit is open: fail-open (allow request)
            return org.fluxgate.core.ratelimiter.RateLimitResult.allowed(
                    ruleSet.getKeyResolver().resolve(context),
                    ruleSet.getRules().get(0),
                    Long.MAX_VALUE,
                    0L
            );
        }

        try {
            org.fluxgate.core.ratelimiter.RateLimitResult result = delegate.tryConsume(context, ruleSet, permits);

            // Success: reset failure count
            failureCount.set(0);

            return result;

        } catch (Exception e) {
            // Failure: increment counter
            int failures = failureCount.incrementAndGet();
            lastFailureTime.set(System.currentTimeMillis());

            if (failures >= failureThreshold) {
                // Open circuit
                return org.fluxgate.core.ratelimiter.RateLimitResult.allowed(
                        ruleSet.getKeyResolver().resolve(context),
                        ruleSet.getRules().get(0),
                        Long.MAX_VALUE,
                        0L
                );
            }

            throw e;
        }
    }

    private State getState() {
        long now = System.currentTimeMillis();
        long lastFailure = lastFailureTime.get();
        int failures = failureCount.get();

        if (failures >= failureThreshold) {
            if (now - lastFailure >= resetTimeoutMillis) {
                return State.HALF_OPEN;
            }
            return State.OPEN;
        }

        return State.CLOSED;
    }
}
```

---

## 🔧 Approach 2: Extend Bucket4jRateLimiter

You can extend `Bucket4jRateLimiter`, but with limitations due to private fields.

### ⚠️ Limitations

- `buckets` field is **private** → Cannot access bucket cache directly
- Helper methods (`createBucket`, `toBandwidth`, etc.) are **private** → Cannot reuse
- `BucketKey` inner class is **private** → Cannot use directly

### Use Cases

- **Add pre/post-processing hooks**
- **Override tryConsume behavior**
- **Add validation logic**

### Example: RateLimiter with Pre-Check Hook

```java
package com.yourapp.ratelimit;

import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.fluxgate.core.context.RequestContext;

/**
 * Extended RateLimiter with IP whitelist support
 */
public class WhitelistRateLimiter extends Bucket4jRateLimiter {

    private final WhitelistService whitelistService;

    public WhitelistRateLimiter(WhitelistService whitelistService) {
        this.whitelistService = whitelistService;
    }

    @Override
    public RateLimitResult tryConsume(RequestContext context,
                                      RateLimitRuleSet ruleSet,
                                      long permits) {

        // Pre-check: Bypass rate limiting for whitelisted IPs
        if (whitelistService.isWhitelisted(context.getClientIp())) {
            return RateLimitResult.allowed(
                    ruleSet.getKeyResolver().resolve(context),
                    ruleSet.getRules().get(0),
                    Long.MAX_VALUE,
                    0L
            );
        }

        // Delegate to parent implementation
        return super.tryConsume(context, ruleSet, permits);
    }
}
```

### Example: RateLimiter with Custom Metrics

```java
package com.yourapp.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.context.RequestContext;

/**
 * RateLimiter with Micrometer metrics
 */
public class MetricsRateLimiter extends org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter {

    private final MeterRegistry meterRegistry;

    public MetricsRateLimiter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public RateLimitResult tryConsume(RequestContext context,
                                      RateLimitRuleSet ruleSet,
                                      long permits) {

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            RateLimitResult result = super.tryConsume(context, ruleSet, permits);

            // Record metrics
            meterRegistry.counter("rate_limit.checks",
                    "ruleset", ruleSet.getId(),
                    "status", result.isAllowed() ? "allowed" : "rejected",
                    "endpoint", context.getEndpoint()
            ).increment();

            if (!result.isAllowed()) {
                meterRegistry.gauge("rate_limit.wait_time_seconds",
                        result.getNanosToWaitForRefill() / 1_000_000_000.0);
            }

            sample.stop(Timer.builder("rate_limit.check.duration")
                    .tag("ruleset", ruleSet.getId())
                    .register(meterRegistry));

            return result;

        } catch (Exception e) {
            meterRegistry.counter("rate_limit.errors",
                    "ruleset", ruleSet.getId(),
                    "error", e.getClass().getSimpleName()
            ).increment();

            throw e;
        }
    }
}
```

---

## 💡 Real-World Use Cases

### Use Case 1: Multi-Region Distributed RateLimiter

```java
/**
 * RateLimiter that routes to different Redis instances based on region
 */
public class MultiRegionRateLimiter implements RateLimiter {

    private final Map<String, RedisRateLimiter> regionalLimiters;

    @Override
    public RateLimitResult tryConsume(RequestContext context,
                                      RateLimitRuleSet ruleSet,
                                      long permits) {

        String region = (String) context.getAttributes().get("region");

        RedisRateLimiter regionalLimiter = regionalLimiters.getOrDefault(
            region,
            regionalLimiters.get("default")
        );

        return regionalLimiter.tryConsume(context, ruleSet, permits);
    }
}
```

### Use Case 2: Hierarchical RateLimiter (Local + Distributed)

```java
/**
 * Two-tier rate limiting: Fast local check + Distributed check
 */
public class HierarchicalRateLimiter implements RateLimiter {

    private final RateLimiter localLimiter;   // Fast in-memory
    private final RateLimiter distributedLimiter; // Redis (slower but shared)

    @Override
    public RateLimitResult tryConsume(RequestContext context,
                                      RateLimitRuleSet ruleSet,
                                      long permits) {

        // First: Fast local check
        RateLimitResult localResult = localLimiter.tryConsume(context, ruleSet, permits);
        if (!localResult.isAllowed()) {
            return localResult; // Rejected locally
        }

        // Second: Distributed check
        return distributedLimiter.tryConsume(context, ruleSet, permits);
    }
}
```

### Use Case 3: Adaptive RateLimiter (Dynamic Capacity)

```java
/**
 * RateLimiter that adjusts capacity based on system load
 */
public class AdaptiveRateLimiter implements RateLimiter {

    private final RateLimiter delegate;
    private final SystemLoadMonitor loadMonitor;

    @Override
    public RateLimitResult tryConsume(RequestContext context,
                                      RateLimitRuleSet ruleSet,
                                      long permits) {

        double cpuLoad = loadMonitor.getCpuLoad();

        // Under high load: Reduce effective capacity
        if (cpuLoad > 0.8) {
            permits = permits * 2; // Make requests "cost" more
        }

        return delegate.tryConsume(context, ruleSet, permits);
    }
}
```

---

## ✅ Best Practices

### 1. Always Implement the Interface (Not Extend the Class)

```java
// ✅ Good: Implement interface for maximum flexibility
public class MyRateLimiter implements RateLimiter {
    // Full control over implementation
}

// ⚠️ Limited: Extending class has limitations
public class MyRateLimiter extends Bucket4jRateLimiter {
    // Cannot access private fields
}
```

### 2. Use Decorator Pattern for Cross-Cutting Concerns

```java
// ✅ Good: Composable decorators
RateLimiter base = new RedisRateLimiter(redisTemplate);
RateLimiter withAudit = new AuditingRateLimiter(base, auditService);
RateLimiter withMetrics = new MetricsRateLimiter(withAudit, meterRegistry);
RateLimiter withCircuitBreaker = new CircuitBreakerRateLimiter(withMetrics, 5, 60000);
```

### 3. Handle Failures Gracefully

```java
@Override
public RateLimitResult tryConsume(RequestContext context,
                                  RateLimitRuleSet ruleSet,
                                  long permits) {
    try {
        return actualImplementation(context, ruleSet, permits);
    } catch (Exception e) {
        logger.error("Rate limiter failed, failing open", e);

        // Fail-open: Allow request when rate limiter is down
        return RateLimitResult.allowed(
            ruleSet.getKeyResolver().resolve(context),
            ruleSet.getRules().get(0),
            Long.MAX_VALUE,
            0L
        );
    }
}
```

### 4. Make Implementations Thread-Safe

```java
// ✅ Good: Thread-safe using ConcurrentHashMap
private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

// ❌ Bad: Not thread-safe
private final Map<String, Bucket> buckets = new HashMap<>();
```

### 5. Add Comprehensive Tests

```java
@Test
void customRateLimiter_shouldRespectLimits() {
    RateLimiter rateLimiter = new MyCustomRateLimiter();

    RateLimitRuleSet ruleSet = createTestRuleSet();
    RequestContext context = createTestContext();

    // First 5 requests should be allowed
    for (int i = 0; i < 5; i++) {
        RateLimitResult result = rateLimiter.tryConsume(context, ruleSet);
        assertThat(result.isAllowed()).isTrue();
    }

    // 6th request should be rejected
    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet);
    assertThat(result.isAllowed()).isFalse();
}
```

---

## 🎓 Summary

### Comparison

| Approach | Flexibility | Complexity | Use Case |
|----------|-------------|------------|----------|
| **Implement Interface** | ⭐⭐⭐⭐⭐ | Medium | Custom storage backends |
| **Extend Class** | ⭐⭐ | Low | Add hooks/validation |
| **Decorator Pattern** | ⭐⭐⭐⭐ | Low | Add cross-cutting concerns |

### Recommendations

1. **For custom storage backends** → Implement `RateLimiter` interface
2. **For adding hooks** → Extend `Bucket4jRateLimiter`
3. **For logging/metrics/auditing** → Use Decorator pattern
4. **For production** → Always add circuit breaker and fail-open logic

