# Storage Layer Deep Dive

이 문서는 FluxGate의 Storage Layer를 **실제 소스코드**와 함께 상세히 설명합니다.

[< 아키텍처 개요로 돌아가기](../README.ko.md)

---

## 목차

1. [RedisTokenBucketStore](#1-redistokenbucketstore)
2. [NOSCRIPT 에러 처리 및 스크립트 리로드](#2-noscript-에러-처리-및-스크립트-리로드)
3. [Lua 스크립트 관리](#3-lua-스크립트-관리)
4. [Lua 스크립트 (원자적 토큰 소비)](#4-lua-스크립트-원자적-토큰-소비)
5. [BucketState](#5-bucketstate)

---

## 1. RedisTokenBucketStore

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/store/
└── RedisTokenBucketStore.java
```

Redis 기반의 Token Bucket 저장소입니다. Standalone과 Cluster 모드를 모두 지원합니다.

```java
// RedisTokenBucketStore.java - 실제 코드
public class RedisTokenBucketStore {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketStore.class);
    private final AtomicBoolean reloadingLuaScript = new AtomicBoolean(false);
    private final RedisConnectionProvider connectionProvider;

    public RedisTokenBucketStore(RedisConnectionProvider connectionProvider) {
        this.connectionProvider =
                Objects.requireNonNull(connectionProvider, "connectionProvider must not be null");

        // Ensure scripts are loaded
        if (!LuaScriptLoader.isLoaded()) {
            throw new IllegalStateException(
                    "Lua scripts not loaded. Call LuaScriptLoader.loadScripts() first.");
        }
    }

    /**
     * Try to consume permits from a token bucket.
     *
     * CRITICAL: Do NOT pass System.nanoTime() - Lua script uses Redis TIME instead!
     * This solves clock drift across distributed nodes.
     */
    public BucketState tryConsume(String bucketKey, RateLimitBand band, long permits) {
        Objects.requireNonNull(bucketKey, "bucketKey must not be null");
        Objects.requireNonNull(band, "band must not be null");

        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }

        long capacity = band.getCapacity();
        long windowNanos = band.getWindow().toNanos();

        // Build arguments for Lua script
        // KEYS[1] = bucketKey
        // ARGV[1] = capacity
        // ARGV[2] = window_nanos
        // ARGV[3] = permits
        String[] keys = {bucketKey};
        String[] args = {
                String.valueOf(capacity), String.valueOf(windowNanos), String.valueOf(permits)
        };

        // Execute Lua script with NOSCRIPT fallback
        List<Long> result = executeScriptWithFallback(keys, args);

        // Parse result: [consumed, remaining_tokens, nanos_to_wait, reset_time_millis]
        if (result == null || result.size() != 4) {
            log.error("Unexpected Lua script result: {}", result);
            throw new IllegalStateException("Lua script returned invalid result");
        }

        long consumed = result.get(0);
        long remainingTokens = result.get(1);
        long nanosToWait = result.get(2);
        long resetTimeMillis = result.get(3);

        if (consumed == 1) {
            return BucketState.allowed(remainingTokens, resetTimeMillis);
        } else {
            return BucketState.rejected(remainingTokens, nanosToWait, resetTimeMillis);
        }
    }
}
```

### 핵심 설계 원칙

| 원칙 | 설명 |
|-----|------|
| Redis TIME 사용 | System.nanoTime() 대신 Redis 서버 시간 사용 (Clock Drift 방지) |
| 정수 연산만 사용 | 부동소수점 정밀도 손실 방지 |
| 거부 시 상태 미변경 | 공정한 Rate Limiting (거부된 요청이 타임스탬프 갱신 안함) |
| TTL 안전 마진 | 10% 여유분 + 24시간 최대 제한 |

---

## 2. NOSCRIPT 에러 처리 및 스크립트 리로드

Redis가 재시작되면 캐시된 Lua 스크립트가 사라집니다. FluxGate는 이를 자동으로 처리합니다.

```java
// RedisTokenBucketStore.java - 실제 코드
private List<Long> executeScriptWithFallback(String[] keys, String[] args) {
    String sha = LuaScripts.getTokenBucketConsumeSha();
    String script = LuaScripts.getTokenBucketConsumeScript();

    try {
        // Try EVALSHA first (efficient, uses cached script)
        return connectionProvider.evalsha(sha, keys, args);
    } catch (RedisNoScriptException e) {
        // Script not in Redis cache (e.g., Redis was restarted)
        log.warn(
                "Lua script not found in Redis cache (NOSCRIPT). "
                        + "Falling back to EVAL and reloading script. SHA: {}",
                sha);

        // Fallback 1: Execute using EVAL (slower but works immediately)
        List<Long> result = connectionProvider.eval(script, keys, args);

        // Fallback 2: Reload script for future calls (thread-safe with AtomicBoolean)
        reloadScript();

        return result;
    }
}

/**
 * Reloads the Lua script into Redis cache.
 *
 * Uses AtomicBoolean to prevent multiple concurrent reload attempts.
 */
private void reloadScript() {
    if (!reloadingLuaScript.compareAndSet(false, true)) {
        log.debug("Lua script is already being reloaded, skipping...");
        return;
    }

    try {
        String script = LuaScripts.getTokenBucketConsumeScript();
        String sha = connectionProvider.scriptLoad(script);
        LuaScripts.setTokenBucketConsumeSha(sha);
        log.info("Lua script reloaded into Redis. SHA: {}", sha);
    } catch (Exception e) {
        log.error("Failed to reload Lua script: {}", e.getMessage(), e);
    } finally {
        reloadingLuaScript.set(false);
    }
}
```

### NOSCRIPT 처리 흐름

```
+-------------------+     EVALSHA     +------------------+
|  tryConsume()     | --------------> |  Redis Server    |
+-------------------+                 +------------------+
         |                                    |
         |  NOSCRIPT error (Redis restarted)  |
         | <--------------------------------- |
         |                                    |
         v                                    |
+-------------------+      EVAL       +------------------+
|  Fallback: EVAL   | --------------> |  Redis Server    |
|  (slower)         |                 |  (script in msg) |
+-------------------+                 +------------------+
         |                                    |
         |  Success                           |
         | <--------------------------------- |
         |                                    |
         v                                    |
+-------------------+   SCRIPT LOAD   +------------------+
|  reloadScript()   | --------------> |  Redis Server    |
|  (AtomicBoolean)  |                 |  (cache script)  |
+-------------------+                 +------------------+
         |
         | compareAndSet(false, true)
         | -> 동시 호출 방지
         v
   Future calls use EVALSHA again
```

### AtomicBoolean을 사용한 동시성 제어

```java
// compareAndSet(false, true) 동작 원리
if (!reloadingLuaScript.compareAndSet(false, true)) {
    // 현재 값이 false가 아님 (= 이미 다른 스레드가 리로드 중)
    // -> 스킵하고 리턴
    return;
}
// 현재 값이 false였고, true로 변경됨
// -> 이 스레드가 리로드 수행
```

| 시나리오 | compareAndSet 결과 | 동작 |
|---------|-------------------|------|
| 첫 번째 스레드 | true (false → true) | 스크립트 리로드 수행 |
| 동시 요청 스레드 | false (이미 true) | 즉시 리턴 (스킵) |
| 리로드 완료 후 | finally에서 false로 복원 | 다음 NOSCRIPT 시 리로드 가능 |

---

## 3. Lua 스크립트 관리

### LuaScripts (스크립트 저장소)

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/script/
└── LuaScripts.java
```

```java
// LuaScripts.java - 실제 코드
public final class LuaScripts {

    /** SHA hash of the script (for EVALSHA) */
    private static volatile String tokenBucketConsumeSha;

    /** The actual Lua script content (for EVAL fallback) */
    private static volatile String tokenBucketConsumeScript;

    private LuaScripts() {
        // Utility class
    }

    public static String getTokenBucketConsumeSha() {
        return tokenBucketConsumeSha;
    }

    public static void setTokenBucketConsumeSha(String sha) {
        tokenBucketConsumeSha = sha;
    }

    public static String getTokenBucketConsumeScript() {
        return tokenBucketConsumeScript;
    }

    public static void setTokenBucketConsumeScript(String script) {
        tokenBucketConsumeScript = script;
    }
}
```

### volatile 키워드

`volatile`은 멀티스레드 환경에서 변수의 가시성(visibility)을 보장합니다:
- 한 스레드에서 변경한 값이 다른 스레드에서 즉시 보임
- 리로드 시 새 SHA가 모든 스레드에서 즉시 사용 가능

### LuaScriptLoader (스크립트 로더)

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/script/
└── LuaScriptLoader.java
```

```java
// LuaScriptLoader.java - 실제 코드
public final class LuaScriptLoader {

    private static final String TOKEN_BUCKET_SCRIPT_PATH = "/lua/token_bucket_consume.lua";

    /**
     * Load all Lua scripts from resources and upload them to Redis.
     * In cluster mode, the script is automatically distributed to all master nodes.
     */
    public static void loadScripts(RedisConnectionProvider connectionProvider) throws IOException {
        log.info("Loading Lua scripts into Redis ({} mode)...", connectionProvider.getMode().name());

        // Load token bucket consume script
        String tokenBucketScript = loadScriptFromClasspath(TOKEN_BUCKET_SCRIPT_PATH);
        String sha = connectionProvider.scriptLoad(tokenBucketScript);

        LuaScripts.setTokenBucketConsumeScript(tokenBucketScript);
        LuaScripts.setTokenBucketConsumeSha(sha);

        log.info("Loaded token_bucket_consume.lua with SHA: {}", sha);

        if (connectionProvider.getMode() == RedisConnectionProvider.RedisMode.CLUSTER) {
            log.info("Script automatically distributed to all cluster master nodes");
        }
    }

    public static boolean isLoaded() {
        return LuaScripts.getTokenBucketConsumeSha() != null
            && LuaScripts.getTokenBucketConsumeScript() != null;
    }
}
```

### EVALSHA vs EVAL

| 명령어 | 동작 | 네트워크 비용 | 사용 시점 |
|-------|------|-------------|----------|
| EVALSHA | SHA로 캐시된 스크립트 실행 | 낮음 (SHA만 전송) | 정상 상황 |
| EVAL | 스크립트 전체를 전송하여 실행 | 높음 (전체 스크립트 전송) | NOSCRIPT 폴백 |

---

## 4. Lua 스크립트 (원자적 토큰 소비)

```
fluxgate-redis-ratelimiter/src/main/resources/lua/
└── token_bucket_consume.lua
```

```lua
-- token_bucket_consume.lua - 실제 코드 (주요 부분)
--[[
KEYS[1] = bucket key
ARGV[1] = capacity (max tokens)
ARGV[2] = window_nanos (window duration in nanoseconds)
ARGV[3] = permits (number of tokens to consume)

Returns: {consumed, remaining_tokens, nanos_to_wait, reset_time_millis}
]]

local bucket_key = KEYS[1]
local capacity = tonumber(ARGV[1])
local window_nanos = tonumber(ARGV[2])
local permits = tonumber(ARGV[3])

-- FIX #1: Use Redis TIME (same clock for all nodes, no clock drift)
local time_info = redis.call('TIME')
local current_time_micros = tonumber(time_info[1]) * 1000000 + tonumber(time_info[2])
local current_time_nanos = current_time_micros * 1000

-- Get current bucket state
local bucket_data = redis.call('HMGET', bucket_key, 'tokens', 'last_refill_nanos')
local current_tokens = tonumber(bucket_data[1])
local last_refill_nanos = tonumber(bucket_data[2])

-- Initialize if bucket doesn't exist
if current_tokens == nil then
    current_tokens = capacity  -- Start with full capacity (allows initial burst)
    last_refill_nanos = current_time_nanos
end

-- FIX #2: Use INTEGER arithmetic only (no floating point precision loss)
local elapsed_nanos = math.max(0, current_time_nanos - last_refill_nanos)
local tokens_to_add = math.floor((elapsed_nanos * capacity) / window_nanos)
local new_tokens = math.min(capacity, current_tokens + tokens_to_add)

-- Try to consume permits
if new_tokens >= permits then
    -- SUCCESS: Consume tokens
    new_tokens = new_tokens - permits

    -- Update bucket state (ONLY on success)
    redis.call('HMSET', bucket_key, 'tokens', new_tokens, 'last_refill_nanos', current_time_nanos)

    -- FIX #3: TTL with safety margin (10%) + max cap (24 hours)
    local desired_ttl = math.ceil(window_nanos / 1000000000 * 1.1)
    local actual_ttl = math.min(desired_ttl, 86400)
    redis.call('EXPIRE', bucket_key, actual_ttl)

    return {1, new_tokens, 0, reset_time_millis}
else
    -- REJECTED: Not enough tokens
    -- FIX #4: Do NOT update state on rejection (read-only operation)
    local tokens_needed = permits - new_tokens
    local nanos_to_wait = math.ceil((tokens_needed * window_nanos) / capacity)

    return {0, new_tokens, nanos_to_wait, reset_time_millis}
end
```

### Lua 스크립트 핵심 개선사항

| 개선 | 문제 | 해결 |
|-----|------|------|
| Redis TIME 사용 | 분산 노드 간 Clock Drift | 모든 노드가 동일한 Redis 시간 사용 |
| 정수 연산 | 부동소수점 정밀도 손실 | `(elapsed * capacity) / window` |
| 거부 시 읽기 전용 | 불공정한 Rate Limiting | 거부된 요청은 상태 변경 안함 |
| TTL 안전 마진 | 조기 만료 | 10% 여유분 + 24시간 최대 제한 |

### 원자적 처리의 중요성

```
+-------------------+     +-------------------+     +-------------------+
|  Client A         |     |  Client B         |     |  Client C         |
|  (Request 1)      |     |  (Request 2)      |     |  (Request 3)      |
+-------------------+     +-------------------+     +-------------------+
         |                         |                         |
         |    동시 요청             |                         |
         +---------+---------------+---------+---------------+
                   |                         |
                   v                         v
         +------------------------------------------------+
         |               Redis Lua Script                 |
         |  - 원자적 실행 (다른 명령어 끼어들 수 없음)       |
         |  - Race Condition 없음                         |
         |  - 한 번의 네트워크 왕복                         |
         +------------------------------------------------+
```

---

## 5. BucketState

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/store/
└── BucketState.java
```

토큰 소비 결과를 담는 불변 객체입니다.

```java
// BucketState.java - 실제 코드
public final class BucketState {

    private final boolean consumed;
    private final long remainingTokens;
    private final long nanosToWaitForRefill;
    private final long resetTimeMillis;

    /** Create a BucketState for a successful consumption. */
    public static BucketState allowed(long remainingTokens, long resetTimeMillis) {
        return new BucketState(true, remainingTokens, 0, resetTimeMillis);
    }

    /** Create a BucketState for a rejected consumption. */
    public static BucketState rejected(long remainingTokens, long nanosToWait, long resetTimeMillis) {
        return new BucketState(false, remainingTokens, nanosToWait, resetTimeMillis);
    }

    public boolean consumed() { return consumed; }
    public long remainingTokens() { return remainingTokens; }
    public long nanosToWaitForRefill() { return nanosToWaitForRefill; }
    public long resetTimeMillis() { return resetTimeMillis; }

    /** Get retry-after time in seconds (for HTTP Retry-After header). */
    public long getRetryAfterSeconds() {
        return (nanosToWaitForRefill + 999_999_999) / 1_000_000_000; // Round up
    }
}
```

### BucketState 필드

| 필드 | 타입 | 설명 |
|-----|------|------|
| `consumed` | boolean | 토큰 소비 성공 여부 |
| `remainingTokens` | long | 버킷에 남은 토큰 수 |
| `nanosToWaitForRefill` | long | 토큰 리필까지 대기 시간 (나노초) |
| `resetTimeMillis` | long | 버킷이 가득 찰 시간 (Unix timestamp) |

### HTTP 헤더 매핑

| BucketState 필드 | HTTP 헤더 |
|-----------------|----------|
| `remainingTokens` | X-RateLimit-Remaining |
| `resetTimeMillis` | X-RateLimit-Reset |
| `getRetryAfterSeconds()` | Retry-After |

---

## 관련 문서

- [RateLimiter Layer Deep Dive](ratelimiter-layer.ko.md)
- [Hot Reload Deep Dive](hot-reload.ko.md)
- [아키텍처 개요](../README.ko.md)
