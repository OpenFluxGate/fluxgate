# Storage Layer

The Storage Layer manages rate limit state in Redis and rules in MongoDB.

[< Back to Architecture Overview](README.md)

---

## Components

### RedisTokenBucketStore

Stores token bucket state in Redis using Lua scripts for atomic operations.

```
📁 fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/store/
└── RedisTokenBucketStore.java
```

**Features:**
- Atomic token consumption via Lua scripts
- Multi-band support
- TTL-based automatic cleanup

### Lua Script

The Lua script ensures atomic token consumption without race conditions.

```
📁 fluxgate-redis-ratelimiter/src/main/resources/lua/
└── token_bucket_consume.lua
```

**Benefits:**
1. **Race Condition Prevention** - All operations are atomic
2. **Network Efficiency** - Single round-trip to Redis
3. **Clock Drift Prevention** - Uses Redis server time

### MongoRateLimitRuleRepository

Stores and retrieves rate limit rules from MongoDB.

```
📁 fluxgate-mongo-adapter/src/main/java/org/fluxgate/adapter/mongo/repository/
└── MongoRateLimitRuleRepository.java
```

---

## Data Flow

```
┌─────────────────┐    ┌─────────────────┐
│   MongoDB       │    │   Redis         │
│   (Rules)       │    │   (State)       │
├─────────────────┤    ├─────────────────┤
│ rate_limit_rules│    │ bucket:key:band │
│   - ruleSetId   │    │   - tokens      │
│   - path        │    │   - lastRefill  │
│   - method      │    │                 │
│   - bands       │    │                 │
└─────────────────┘    └─────────────────┘
```

---

## Related

- [RateLimiter Layer](ratelimiter-layer.md)
- [Hot Reload](hot-reload.md)
- [Architecture Overview](README.md)
