# RateLimiter Layer

The RateLimiter Layer implements the token bucket algorithm.

[< Back to Architecture Overview](README.md)

---

## Components

### RateLimiter Interface

```
📁 fluxgate-core/src/main/java/org/fluxgate/core/ratelimiter/
└── RateLimiter.java
```

```java
public interface RateLimiter {
    RateLimitResult tryConsume(RequestContext context, RateLimitRuleSet ruleSet, long permits);
}
```

### Bucket4jRateLimiter

The default implementation using the Bucket4j library.

**Responsibilities:**
- Find matching rules
- Generate rate limit keys
- Consume tokens from bucket store
- Return rate limit results

---

## Token Bucket Algorithm

```
┌─────────────────────────────────────────┐
│ Token Bucket                            │
│                                         │
│   Capacity: 100 tokens                  │
│   Refill: 10 tokens per second          │
│                                         │
│   ┌─────────────────────────────────┐   │
│   │ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ (tokens)   │   │
│   └─────────────────────────────────┘   │
│                                         │
│   Request → Consume 1 token             │
│   - If tokens > 0: Allow                │
│   - If tokens = 0: Reject (429)         │
└─────────────────────────────────────────┘
```

---

## Related

- [Engine Layer](engine-layer.md)
- [Storage Layer](storage-layer.md)
- [Architecture Overview](README.md)
