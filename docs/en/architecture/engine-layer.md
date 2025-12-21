# Engine Layer

The Engine Layer handles rule matching and key resolution.

[< Back to Architecture Overview](README.md)

---

## Components

### RateLimitEngine

The core engine that processes rate limit requests.

```
📁 fluxgate-core/src/main/java/org/fluxgate/core/engine/
└── RateLimitEngine.java
```

**Responsibilities:**
- Retrieve rule sets from provider
- Match requests to rules
- Resolve rate limit keys
- Delegate to rate limiter for token consumption

### RuleSetProvider

Provides rule sets from storage (MongoDB, Redis, or in-memory).

**Implementations:**
- `MongoRuleSetProvider` - Loads from MongoDB
- `RedisRuleSetProvider` - Loads from Redis
- `CachingRuleSetProvider` - Adds caching layer

### RuleCache

Caches rule sets for performance.

**Default Implementation:** Caffeine cache with configurable TTL.

### KeyResolver

Resolves rate limit keys based on `LimitScope`.

| LimitScope | Key Example |
|------------|-------------|
| `GLOBAL` | `api-limits:rule-1:global` |
| `IP` | `api-limits:rule-1:192.168.1.1` |
| `USER_ID` | `api-limits:rule-1:user-123` |
| `COMPOSITE` | `api-limits:rule-1:192.168.1.1:user-123` |

---

## Related

- [Handler Layer](handler-layer.md)
- [RateLimiter Layer](ratelimiter-layer.md)
- [Architecture Overview](README.md)
