# Storage Layer

The Storage Layer keeps token bucket state in Redis and rule definitions in MongoDB.

[< Back to Architecture Overview](README.md) | [한국어 (deep dive)](../../ko/architecture/deep-dive/storage-layer.ko.md)

---

## Components

### RedisTokenBucketStore

Executes the Lua script over a **Lettuce** connection (not Jedis), one call per rule.

```
📁 fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/store/
└── RedisTokenBucketStore.java
```

```java
/** All bands of ONE rule. bucketKeys.size() must equal bands.size(). */
public BucketState tryConsume(List<String> bucketKeys, List<RateLimitBand> bands, long permits)

/** Single-band convenience form, delegating to the above. */
public BucketState tryConsume(String bucketKey, RateLimitBand band, long permits)
```

**Features:**
- Atomic multi-band consumption for one rule
- `permits > band capacity` is rejected **before** touching Redis, with `InvalidRuleConfigException`
- `EVALSHA`, falling back to `EVAL` plus a script reload on `NOSCRIPT`
- Lua errors surface as `ScriptExecutionException`, not a raw `RedisCommandExecutionException`
- Script body and SHA live in a per-store `LuaScriptRegistry`, not in process-global static state
- Deletion uses `SCAN` + `UNLINK`, never `KEYS` + `DEL`

### Bucket key format

```
fluxgate:bucket:{<ruleSetId>:<ruleId>:<keyValue>}:<bandKeyLabel>
fluxgate:bucket:{api-limits:per-ip-rule:ip:192.168.1.100}:100-per-60s
```

The `{...}` hash tag pins all bands of one rule and key to a single Redis Cluster slot, which is the
precondition for the multi-key script being atomic there.

| Operation | Pattern |
|-----------|---------|
| `deleteBucketsByRuleSetId(id)` | `RedisRateLimiter.bucketKeyPattern(id)`, glob metacharacters escaped |
| `deleteAllBuckets()` | `fluxgate:bucket:*` only |

Scoping the second pattern matters: the previous `fluxgate:*` also deleted the rule definitions
stored under `fluxgate:ruleset:*`.

### Lua script

```
📁 fluxgate-redis-ratelimiter/src/main/resources/lua/
└── token_bucket_consume.lua
```

**Contract:**

```
KEYS[1..n]    one bucket key per band of ONE rule, all in the same hash tag
ARGV[1]       permits
ARGV[2 + 3i]  capacity of band i+1
ARGV[3 + 3i]  window_micros of band i+1
ARGV[4 + 3i]  reserved, pass "0"

returns 7 integers:
  [1] allowed                 1 when every band allowed
  [2] rejecting_band_index    1-based index of the first rejecting band, 0 when allowed
  [3] min_remaining           binding band's tokens after consumption / rejecting band's tokens
  [4] micros_to_wait          until the rejecting band can serve the request, 0 when allowed
  [5] reset_time_millis       when the binding band's bucket is full again (computed AFTER consumption)
  [6] limit                   capacity of the binding band
  [7] binding_band_index      1-based index of the binding band

hash fields: 'tokens', 'last_refill_micros'   (both written via string.format('%.0f', v))
TTL:         max(1, ceil(window_seconds * 1.1)), no upper cap
```

**Benefits:**
1. **Race condition prevention** — all bands of a rule are checked and written in one script
2. **Network efficiency** — one round trip per rule
3. **Clock drift prevention** — `redis.call('TIME')`, so every node shares one clock

**Two passes:** refill and check every band first; only then consume from all of them, or from none.
On rejection nothing is written except `EXPIRE` on every key — a no-op for a key that does not exist,
so a band that was never charged is not created, and a bucket that only ever sees rejections still
expires on schedule.

**Time base is microseconds.** Redis runs Lua 5.1, where every number is an IEEE-754 double with an
exact integer range of 2^53 (≈ 9.0e15). Epoch microseconds are ≈ 1.76e15 and fit; **nanoseconds are
≈ 1.76e18 and do not**, which is why the old `last_refill_nanos` field was serialised as `1.76e+18`
through Lua's default `%.14g`. The script's old "integer arithmetic only" claim was not true.

**No TTL cap.** The previous 24-hour cap silently reset any window longer than a day, so a 7-day
quota effectively allowed 7× its capacity.

### MongoRuleSetProvider and MongoRateLimitRuleRepository

```
📁 fluxgate-core/src/main/java/org/fluxgate/core/spi/
├── RateLimitRuleSetProvider.java
└── RateLimitRuleRepository.java

📁 fluxgate-mongo-adapter/src/main/java/org/fluxgate/adapter/mongo/
├── rule/MongoRuleSetProvider.java
└── repository/MongoRateLimitRuleRepository.java
```

The repository reads and writes rule documents (`Filters.eq("ruleSetId", …)`, `Filters.eq("id", …)`,
`replaceOne` with upsert). The provider assembles them into a `RateLimitRuleSet` and attaches the
`KeyResolver`.

Documents map 1:1 onto `RateLimitRule`'s real fields. There is no `path`, `method` or `priority`
field, and no sorting: every enabled rule in the set is evaluated.

`fluxgate.mongo.ddl-auto=create` creates the collections plus a `{ruleSetId: 1}` index and a unique
`{ruleSetId: 1, id: 1}` index. Index creation failures are warnings, not fatal.

---

## Data Flow

```
┌──────────────────────┐    ┌──────────────────────────────────────┐
│   MongoDB            │    │   Redis                              │
│   (Rules)            │    │   (Bucket state)                     │
├──────────────────────┤    ├──────────────────────────────────────┤
│ rate_limit_rules     │    │ fluxgate:bucket:{rs:rule:key}:band   │
│   - id               │    │   - tokens                           │
│   - ruleSetId        │    │   - last_refill_micros               │
│   - scope            │    │   TTL = ceil(window * 1.1)           │
│   - keyStrategyId    │    │                                      │
│   - bands            │    │ fluxgate:ruleset:* (deprecated store) │
│   - enabled          │    │                                      │
│   - attributes       │    │                                      │
└──────────────────────┘    └──────────────────────────────────────┘
```

`fluxgate:ruleset:*` belongs to `RedisRuleSetStore`, which is deprecated since 0.4 — store rules in
MongoDB behind a `RateLimitRuleSetProvider` instead.

---

## Related

- [RateLimiter Layer](ratelimiter-layer.md)
- [Hot Reload](hot-reload.md)
- [Redis Rate Limiter](../../../fluxgate-redis-ratelimiter/README.md) - the full module reference
- [Architecture Overview](README.md)
