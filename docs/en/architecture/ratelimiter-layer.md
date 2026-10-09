# RateLimiter Layer

The RateLimiter Layer implements the token bucket algorithm and resolves keys per rule.

[< Back to Architecture Overview](README.md) | [한국어 (deep dive)](../../ko/architecture/deep-dive/ratelimiter-layer.ko.md)

---

## Components

### RateLimiter interface

```
📁 fluxgate-core/src/main/java/org/fluxgate/core/ratelimiter/
└── RateLimiter.java
```

```java
public interface RateLimiter {

  default RateLimitResult tryConsume(RequestContext context, RateLimitRuleSet ruleSet) {
    return tryConsume(context, ruleSet, 1L);
  }

  RateLimitResult tryConsume(RequestContext context, RateLimitRuleSet ruleSet, long permits);
}
```

Implementations are expected to be thread-safe.

### What both implementations do

| Step | Detail |
|------|--------|
| 1 | Iterate the rule set's **enabled** rules. There is no path or method matching |
| 2 | Resolve a key per rule via `ruleSet.getKeyResolver().resolve(context, rule)` |
| 3 | Evaluate **all bands of that rule together**, all-or-nothing |
| 4 | Stop at the first rejecting rule (fail fast) |
| 5 | Report `limit`, `resetTimeMillis`, `policy`, `bandLabel` and the real remaining tokens of the binding band |

A `MissingRateLimitKeyException` (from `missing-key-behavior=REJECT`) becomes a rejected result with
`nanosToWaitForRefill = 0` and a synthetic key. `permits` greater than a band's capacity throws
`InvalidRuleConfigException`.

**Cross-rule consumption is atomic within one slot, compensated across slots.** When all keys of
the matching rules may share a script call (standalone Redis, or one cluster slot) every rule is
evaluated in one Lua call, all-or-nothing. Otherwise rules are charged one by one, and when one
rejects the rules already charged are refunded. The refund is not atomic with the charge: in between
concurrent requests see the earlier rule a permit lower, and a refund that cannot run leaves the
permit spent. Keys of all rules are resolved before anything is charged.

### Bucket4jRateLimiter

```
📁 fluxgate-core/src/main/java/org/fluxgate/core/ratelimiter/impl/bucket4j/
└── Bucket4jRateLimiter.java
```

In-memory, used by `fluxgate.ratelimit.mode=IN_MEMORY` and by
`fluxgate.ratelimit.fallback.mode=IN_MEMORY`.

- Buckets live in a **Caffeine cache**, not an unbounded map: `maximumSize` (default 100 000) and a
  fixed `expireAfterAccess` (default 1 hour). The unbounded map it replaced was a memory leak and an
  OOM denial-of-service vector.
- One multi-bandwidth bucket per `(ruleSetId, ruleId, key)`, so the bands of a rule are atomic
  inside Bucket4j.
- All-or-nothing across rules: every rule's key is resolved first, the buckets of all matching rules
  are locked in a global order (creation sequence, so no deadlock), each is checked with
  `estimateAbilityToConsume`, and only if all can serve the request is each charged. A request
  rejected by a later rule costs the earlier rules nothing; should a charge fail after the check, the
  rules already charged get their tokens back through `addTokens` (capped at capacity). Requests
  contend only on buckets they share, for a few in-memory calls.
- `reset(String ruleSetId)`, `resetAll()`, `size()` support the in-memory reset handler and the
  testkit.

It does **not** distribute: buckets live in this instance, so N instances enforce N times the limit.
Single instance, development, and Redis-outage fallback are its uses.

### RedisRateLimiter

```
📁 fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/
└── RedisRateLimiter.java
```

Distributed. One Lua call per rule, all of that rule's band keys in one cluster hash tag.

```
fluxgate:bucket:{api-limits:per-ip-rule:ip:192.168.1.100}:100-per-60s
```

`RedisRateLimiter.BUCKET_KEY_PREFIX` and `bucketKeyPattern(String ruleSetId)` are the only sanctioned
way to build those keys and patterns — never assemble `"fluxgate:" + id + ":*"` by hand.

It implements `AutoCloseable` with a documented **no-op** `close()`: it owns neither the store nor
the connection.

---

## Token Bucket Algorithm

```
┌─────────────────────────────────────────┐
│ Token Bucket                            │
│                                         │
│   Capacity: 100 tokens                  │
│   Window:   60 seconds                  │
│                                         │
│   ┌─────────────────────────────────┐   │
│   │ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ (tokens)   │   │
│   └─────────────────────────────────┘   │
│                                         │
│   Request → consume `permits` tokens    │
│   - tokens >= permits: allow            │
│   - tokens <  permits: reject (429)     │
│                                         │
│   Refill is continuous: capacity tokens │
│   per window, credited in whole tokens, │
│   sub-token remainder carried over      │
└─────────────────────────────────────────┘
```

A band is declared as `(window, capacity)` — "100 per 60s" — not as a refill rate. Carrying the
sub-token remainder matters: discarding it systematically under-allowed high-frequency bands.

---

## Related

- [Engine Layer](engine-layer.md)
- [Storage Layer](storage-layer.md)
- [Algorithm Analysis](algorithm-analysis.md)
- [Architecture Overview](README.md)
