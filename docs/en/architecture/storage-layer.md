# Storage Layer

The Storage Layer keeps token bucket state in Redis and rule definitions in MongoDB.

[< Back to Architecture Overview](README.md) | [한국어 (deep dive)](../../ko/architecture/deep-dive/storage-layer.ko.md)

---

## Components

### RedisTokenBucketStore

Executes the Lua scripts over a **Lettuce** connection (not Jedis): one call for the bands it is
given - one rule's, or those of every matching rule when `RedisRateLimiter` finds their keys in one
slot.

```
📁 fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/store/
└── RedisTokenBucketStore.java
```

```java
/** All bands of ONE rule. bucketKeys.size() must equal bands.size(). */
public BucketState tryConsume(List<String> bucketKeys, List<RateLimitBand> bands, long permits)

/** Single-band convenience form, delegating to the above. */
public BucketState tryConsume(String bucketKey, RateLimitBand band, long permits)

/** The same decision without consuming anything (check-only mode of the consume script). */
public BucketState check(List<String> bucketKeys, List<RateLimitBand> bands, long permits)

/** Gives a charged rule its permits back; returns the permits given back per band. */
public List<Long> refund(
    List<String> bucketKeys, List<RateLimitBand> bands, long permits, long consumedAtMicros)

/** Whether the keys may share one script call: always on standalone, one slot on a cluster. */
public boolean canEvaluateAtomically(Collection<String> bucketKeys)
```

**Features:**
- Atomic multi-band consumption for one rule
- `permits > band capacity`, a window or sliding sub-bucket below 1 ms, and a TOKEN_BUCKET band whose
  `capacity × window_micros` exceeds 2^53 are rejected **before** touching Redis, with
  `InvalidRuleConfigException`
- `EVALSHA`, falling back to `EVAL` plus a script reload on `NOSCRIPT` (one reload at a time, at
  least 1 s apart, doubling up to 1 min while reloads fail)
- Lua errors surface as `ScriptExecutionException`, not a raw `RedisCommandExecutionException`; a
  command timeout as `FluxgateTimeoutException`, any other driver failure as a `Phase.COMMAND`
  `RedisConnectionException` (never retried, since Redis may have executed the consumption)
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

### Lua scripts

```
📁 fluxgate-redis-ratelimiter/src/main/resources/lua/
├── token_bucket_consume.lua   one rule (or several rules in one slot), all bands, all-or-nothing
└── token_bucket_refund.lua    gives a charged rule its permits back (cross-slot compensation)
```

**Consume contract** (the header of `token_bucket_consume.lua` is the authoritative copy):

```
KEYS[1..n]          one bucket key per band, all in the same hash slot

ARGV[1]             permits
ARGV[2]             max_bucket_ttl_seconds - upper bound on every TOKEN_BUCKET / SLIDING_WINDOW
                    bucket TTL (fluxgate.redis.max-bucket-ttl); FIXED_WINDOW uses PEXPIREAT instead

per band i (1-based), base = 2 + 5 * (i - 1):
ARGV[base + 1]      capacity
ARGV[base + 2]      window_micros (>= 1000: windows below 1 ms are refused)
ARGV[base + 3]      algorithm code: 1 TOKEN_BUCKET, 2 SLIDING_WINDOW, 3 FIXED_WINDOW
ARGV[base + 4]      SLIDING_WINDOW sub-bucket count [2..60] (each sub-bucket >= 1 ms), else 0
ARGV[base + 5]      FIXED_WINDOW calendar window end in epoch micros, else 0 (derived from now)

optional, after the last band:
ARGV[3 + 5 * n]     "1" = check-only: decide exactly as a consumption, write nothing on allow

#ARGV = 2 + 5 * n, or 3 + 5 * n with the check-only flag

returns 8 integers:
  [1] allowed                 1 when every band allowed
  [2] rejecting_band_index    1-based index of the rejecting band with the LONGEST wait
                              (first of them on a tie), 0 when allowed
  [3] min_remaining           binding band's remaining after consumption (before it in
                              check-only mode) / rejecting band's remaining
  [4] micros_to_wait          longest wait of all rejecting bands, 0 when allowed
  [5] reset_time_millis       TOKEN_BUCKET: bucket full again; SLIDING_WINDOW: everything counted
                              now has left the window, rounded up to the millisecond;
                              FIXED_WINDOW: window end
  [6] limit                   capacity of the binding band
  [7] binding_band_index      1-based index of the binding band (= [2] on reject)
  [8] now_micros              the Redis TIME of the decision; the refund script needs it

state per algorithm:
  TOKEN_BUCKET    hash {tokens, last_refill_micros}
  SLIDING_WINDOW  hash {"<sub-bucket index>@<sub-bucket duration micros>": count}
                  - fields of another geometry, or outside the current window, are ignored
                    and deleted on the next admitted request
  FIXED_WINDOW    hash {count, window_end_micros}, PEXPIREAT at the window end
every number written via string.format('%.0f', v)
TTL (TOKEN_BUCKET, SLIDING_WINDOW): min(max_bucket_ttl, max(1, ceil(window_seconds * 1.1)))
```

**Refund contract** (`token_bucket_refund.lua`): the same `KEYS` and the same five values per band,
but `ARGV[2]` is the `now_micros` the consumption returned (result `[8]`), not the TTL cap. It
returns one integer per band, the permits actually given back, never more than the consumption
took and never creating a key. It validates its arguments with the same rules and messages as the
consume script (`window must be at least 1 ms`, `sliding window sub-bucket must be at least 1 ms`),
and validates **every** band before it refunds any: an invalid band fails the call with nothing
written, since Redis does not roll a script back.

**Benefits:**
1. **Race condition prevention** — all bands of a rule are checked and written in one script
2. **Network efficiency** — one round trip per rule (per request when all rules share a slot)
3. **Clock drift prevention** — `redis.call('TIME')`, so every node shares one clock

**Two passes:** check every band first, without writing; only then consume from all of them, or
from none. Pass 1 keeps checking after a band rejects, so the reported wait is the longest of all
rejecting bands: a Retry-After taken from the first one would send the client back while another
band still rejects. On rejection (and in check-only mode) no token, counter or sub-bucket is
written; only the TTLs of the TOKEN_BUCKET and SLIDING_WINDOW keys are refreshed with `EXPIRE` - a
no-op for a key that does not exist, so a band that was never charged is not created - and a
FIXED_WINDOW counter found without an expiry gets its `PEXPIREAT`.

**Sliding window wait.** A rejecting SLIDING_WINDOW band walks its counted sub-buckets oldest to
newest and waits for the first sub-bucket `k` whose departure lets the request fit
(`total - freed + permits <= capacity`): `wait = (k + buckets) * sub_duration - now`. A burst that
fills the window inside one sub-bucket therefore waits almost a whole window.

**Time base is microseconds.** Redis runs Lua 5.1, where every number is an IEEE-754 double with an
exact integer range of 2^53 (≈ 9.0e15). Epoch microseconds are ≈ 1.76e15 and fit; **nanoseconds are
≈ 1.76e18 and do not**, which is why the old `last_refill_nanos` field was serialised as `1.76e+18`
through Lua's default `%.14g`. The script's old "integer arithmetic only" claim was not true.

**TTL cap.** TOKEN_BUCKET and SLIDING_WINDOW bucket TTLs are capped by `fluxgate.redis.max-bucket-ttl`
(7 days by default), so a forged identity key cannot pin a Redis hash for a whole 30-day window. A
window longer than the cap is effectively shortened, and `RedisRateLimiter` warns once per rule. A
FIXED_WINDOW counter is not capped: it expires exactly at its window end.

### MongoRuleSetProvider and MongoRateLimitRuleRepository

```
📁 fluxgate-core/src/main/java/org/fluxgate/core/spi/
├── RateLimitRuleSetProvider.java
└── RateLimitRuleRepository.java

📁 fluxgate-mongo-adapter/src/main/java/org/fluxgate/adapter/mongo/
├── rule/MongoRuleSetProvider.java
└── repository/MongoRateLimitRuleRepository.java
```

The repository reads rule documents with `Filters.eq("ruleSetId", …)` and addresses a single rule by
`(ruleSetId, id)`. `save()` is an `updateOne` with `$set`/`$unset` of the rule fields; only when no
document matched does it upsert, copying the rule set's access control with `$setOnInsert`. The
provider assembles the documents into a `RateLimitRuleSet` and attaches the `KeyResolver`.

Documents map onto `RateLimitRule`'s fields, including the 0.4 matcher fields (`priority`,
`methods`, `pathPatterns`, `excludePathPatterns`, `headerEquals`, `headerPresent`). The access
control lists are stored on the same document but are not part of the domain `RateLimitRule`: they
belong to the rule set and are read through `findAccessControlByRuleSetId`. Every enabled rule whose
matchers accept the request is evaluated.

`fluxgate.mongo.ddl-auto=create` creates the collections and calls
`MongoRateLimitRuleRepository#ensureIndexes()`, which builds the unique `ruleSetId_1_id_1_unique`
index on `{ruleSetId: 1, id: 1}` and the `id_1` index on `{id: 1}`. Index creation failures are fatal:
duplicate `(ruleSetId, id)` pairs or a conflicting index fail startup with an `IllegalStateException`
(the duplicates are listed). `ddl-auto=validate` fails startup when there is no unique index on
`{ruleSetId: 1, id: 1}` (any name) that enforces uniqueness for every rule: a `sparse` or partial
(`partialFilterExpression`) index, or one with a collation other than `simple`, is rejected by name.

Access control (`allowedIps`, `deniedIps`, `allowedKeys`, `deniedKeys`) is rule-set level but stored
on every rule document. `save()` never rewrites it on an existing document; a new document copies the
merged access control of the rule set, and a rule that moves to another `ruleSetId` adopts the new
rule set's lists (or loses them if the new set has none). Every write of the access control
(`saveAccessControl`, the insert of a new rule, `moveRule`) also sets the marker field
`aclUpdatedAt`; empty lists are removed. Only the marker's presence matters: `saveAccessControl` and
`moveRule` write the server time (`$currentDate`), while the insert of a new rule writes the client
time, because `$currentDate` cannot be used in `$setOnInsert`.

Reads merge every copy: the documents of the rule set that carry `aclUpdatedAt` or a non-empty list.
Each deny list is the union over the copies, each allow list the intersection, and a copy without a
list counts as empty. A document with neither marker nor list (written by 0.3.x or by hand) holds no
access control and is ignored. The merge fails closed: an interrupted `saveAccessControl` that
cleared the lists leaves an empty allow list, never the revoked one.

**Race with `saveAccessControl`:** the copy for a new or moved document is a read followed by a
separate write. If `saveAccessControl(...)` runs for the same rule set between the two, that one
document keeps the previous lists, and the merged read fails closed (allow = old ∩ new, deny =
old ∪ new, with a WARN) until `saveAccessControl(...)` is called again. Call it again after saving
rules when rules and access control are edited concurrently.

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
│   - scope            │    │   TTL = min(cap, ceil(window * 1.1)) │
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
