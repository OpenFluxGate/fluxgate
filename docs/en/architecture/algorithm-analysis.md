# Algorithm Analysis

This document analyzes the algorithmic design and complexity of FluxGate's core components.

[< Back to Architecture Overview](README.md)

---

## Table of Contents

1. [Overview](#1-overview)
2. [Token Bucket Algorithm (Lua Script)](#2-token-bucket-algorithm-lua-script)
3. [Key Resolution](#3-key-resolution)
4. [Complexity Summary](#4-complexity-summary)
5. [Optimization Opportunities](#5-optimization-opportunities)

---

## 1. Overview

FluxGate is designed for **high-throughput, low-latency** rate limiting in distributed environments. The core algorithms prioritize:

- **O(1) time complexity** for rate limit checks
- **Atomic operations** to prevent race conditions
- **Distributed consistency** without clock drift issues

---

## 2. Token Bucket Algorithm (Lua Script)

The heart of FluxGate is an optimized Token Bucket implementation running as a Redis Lua script.

### 2.1 Complexity

| Metric | Complexity | Description |
|--------|------------|-------------|
| **Time** | O(1) | Constant time per rate limit check |
| **Space** | O(1) per band | 2 fields per bucket (`tokens`, `last_refill_micros`) |
| **Network** | 1 RTT per rule | Every band of one rule in a single round-trip |

### 2.2 Key Optimizations

#### Fix #1: Redis Server Time (Clock Drift Prevention)

**Problem:** Distributed nodes have different system clocks, causing inconsistent rate limiting.

**Solution:** Use Redis `TIME` command instead of client-side timestamps.

```lua
-- Uses Redis server time (consistent across all clients)
local time_info = redis.call('TIME')
local current_time_nanos = tonumber(time_info[1]) * 1000000000
                         + tonumber(time_info[2]) * 1000
```

**Impact:** Eliminates clock drift in multi-node deployments.

---

#### Fix #2: Integer Arithmetic Only (Precision Guarantee)

**Problem:** Floating-point arithmetic causes precision loss over time.

```lua
-- BAD: Floating point (precision loss)
local refill_rate = capacity / window_nanos  -- 0.0000000016667...
local tokens_to_add = elapsed_nanos * refill_rate

-- GOOD: Integer only (no precision loss)
local tokens_to_add = math.floor((elapsed_nanos * capacity) / window_nanos)
```

**Impact:** Accurate token counting even after millions of requests.

---

#### Fix #3: Read-Only on Rejection (Fair Rate Limiting)

**Problem:** Updating timestamps on rejection causes unfair rate limiting.

```
Scenario without this fix:
1. Request A: tokens=0, rejected, but timestamp updated to T1
2. Request B (1ms later): sees only 1ms elapsed, gets fewer refill tokens
   Result: Request B is unfairly penalized
```

**Solution:** Only update state on successful consumption.

```lua
if new_tokens >= permits then
    -- SUCCESS: Update state
    redis.call('HMSET', bucket_key, 'tokens', new_tokens, ...)
else
    -- REJECTED: Read-only, do NOT update state
    return {0, new_tokens, nanos_to_wait, reset_time_millis}
end
```

**Impact:** Fair rate limiting under high contention.

---

#### Fix #4: TTL Safety Margin, with a configurable upper cap

**Problem:** Clock skew can cause premature key expiration. The hard 24-hour cap of 0.3.x caused the
opposite problem: it silently reset any window longer than a day.

```lua
-- Window plus a 10% margin for clock skew, never below 1s, capped at max_bucket_ttl (ARGV[2],
-- fluxgate.redis.max-bucket-ttl, default 7 days). Used for TOKEN_BUCKET and SLIDING_WINDOW.
local function ttl_for_window(win_micros)
    return math.min(max_ttl_seconds, math.max(1, math.ceil(win_micros / 1000000 * 1.1)))
end
```

**Impact:** Buckets no longer expire too early. The previous `math.min(desired_ttl, 86400)` cap meant
a 7-day quota reset every 24 hours, effectively allowing 7× its configured capacity. FIXED_WINDOW
counters expire at their window end (`PEXPIREAT`) and are exempt from the cap. A TOKEN_BUCKET or
SLIDING_WINDOW bucket idle for longer than `max-bucket-ttl` expires and starts full, so a window longer
than the cap is effectively shortened for idle callers; the limiter logs a WARN once per such rule.

On rejection the script issues `EXPIRE` on every key but writes no state. `EXPIRE` is a no-op for a
key that does not exist, so a band that was never charged is not created, and a bucket that only ever
sees rejections still expires on schedule instead of inheriting the shrinking TTL of its last allowed
request.

---

### 2.3 Atomicity Guarantee

All operations execute in a single Lua script, providing:

```
┌─────────────────────────────────────────────────────────────┐
│                    Redis Lua Script                         │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  1. Get current time (Redis TIME)                   │   │
│  │  2. Read bucket state (HMGET)                       │   │
│  │  3. Calculate token refill                          │   │
│  │  4. Check if consumption allowed                    │   │
│  │  5. Update state if allowed (HMSET)                 │   │
│  │  6. Set TTL (EXPIRE)                                │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  All steps execute atomically - no race conditions          │
└─────────────────────────────────────────────────────────────┘

Concurrent Requests:
Client A ──┐
Client B ──┼──→ [Lua Script] ──→ Serialized execution
Client C ──┘
```

---

## 3. Key Resolution

### 3.1 LimitScopeKeyResolver

Key resolution uses O(1) switch-case mapping:

```java
switch (scope) {
    case GLOBAL:      return "global";           // O(1)
    case PER_IP:      return context.getClientIp();    // O(1)
    case PER_USER:    return context.getUserId();      // O(1)
    case PER_API_KEY: return context.getApiKey();      // O(1)
    case CUSTOM:      return context.getAttribute(key); // O(1)
}
```

| Scope | Time Complexity | Space Complexity |
|-------|-----------------|------------------|
| All scopes | O(1) | O(1) |

---

## 4. Complexity Summary

### Per-Request Path

| Component | Time | Space | Notes |
|-----------|------|-------|-------|
| Filter (context build) | O(1) | O(H) | H = number of headers |
| Key Resolution | O(1) | O(1) | Switch-case lookup |
| Rule Cache (Caffeine) | O(1) | O(R) | R = cached rules |
| Token Bucket (Lua) | O(1) | O(1) | Single Redis call |
| **Total** | **O(1)** | **O(H)** | Constant time |

### Background Operations

| Operation | Time | Notes |
|-----------|------|-------|
| Cache refresh | O(R) | R = number of rules in MongoDB |
| Bucket cleanup (SCAN + UNLINK) | O(N) total, non-blocking | N = total Redis keys; one SCAN page at a time (see 5.1) |

---

## 5. Optimization Opportunities

### 5.1 Bucket Deletion: KEYS → SCAN (done in 0.4)

**0.3.x (O(N), blocking):** `connectionProvider.keys("fluxgate:*")` - `KEYS` blocks Redis for a
full keyspace scan, and the `fluxgate:*` pattern also matched rule set definitions.

**0.4 (implemented):** the reset paths (`deleteBucketsByRuleSetId`, `deleteAllBuckets`) scan
`fluxgate:bucket:...` patterns with `SCAN` and delete each page with `UNLINK` as it arrives, so the
keys are never held in memory all at once.

```java
// RedisTokenBucketStore.java - actual code
private long scanAndUnlink(String pattern) {
  long[] deleted = {0L};
  try {
    connectionProvider.scanKeys(
        pattern, BUCKET_SCAN_COUNT, page -> deleted[0] += deleteInBatches(page));
  } catch (RedisException e) {
    throw driverFailed("SCAN/UNLINK " + pattern, e);
  }
  return deleted[0];
}

private long deleteInBatches(List<String> keys) {
  long deleted = 0;
  for (int start = 0; start < keys.size(); start += DELETE_BATCH_SIZE) {
    int end = Math.min(start + DELETE_BATCH_SIZE, keys.size());
    deleted += connectionProvider.unlink(keys.subList(start, end).toArray(new String[0]));
  }
  return deleted;
}
```

| Approach | Time | Blocking |
|----------|------|----------|
| KEYS | O(N) | Yes (blocks Redis) |
| SCAN | O(N) total, O(1) per call | No |

---

### 5.2 Rule Matching (Future Enhancement)

For applications with many rules (100+), consider:

| Approach | Current | Enhanced |
|----------|---------|----------|
| Path matching | O(R) linear scan | O(K) Trie lookup |
| Bloom filter | N/A | O(1) pre-check for miss |

Where R = number of rules, K = path length.

---

## Summary

FluxGate achieves **O(1) time complexity** for the critical path (rate limit checks) through:

1. **Atomic Lua scripts** - Single Redis round-trip
2. **Server-side timestamps** - No clock drift
3. **Integer arithmetic** - No precision loss
4. **Caffeine caching** - O(1) rule lookups
5. **Fair rejection handling** - Read-only on reject

These optimizations make FluxGate suitable for **high-throughput production environments** with millions of requests per second.

---

## Related

- [Storage Layer](storage-layer.md) - Redis implementation details
- [Architecture Overview](README.md)
