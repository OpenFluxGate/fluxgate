# Storage Layer Deep Dive

> 이 문서는 FluxGate **0.4**의 코드를 기준으로 합니다. 0.3.x에서 무엇이 바뀌었는지는
> [0.4 마이그레이션](../../operations/migration-0.4.ko.md)에, 전체 계층을 한 문서로 훑는 서술은
> [아키텍처 Deep Dive](../../../ARCHITECTURE_DEEP_DIVE.ko.md)에 있습니다.

이 문서는 FluxGate의 Storage Layer를 **실제 소스코드**와 함께 상세히 설명합니다.

[< 아키텍처 개요로 돌아가기](../README.ko.md)

---

## 목차

1. [RedisTokenBucketStore](#1-redistokenbucketstore)
2. [NOSCRIPT 에러 처리 및 스크립트 리로드](#2-noscript-에러-처리-및-스크립트-리로드)
3. [LuaScriptRegistry: 스크립트 관리](#3-luascriptregistry-스크립트-관리)
4. [Lua 스크립트 (원자적 다중 대역 토큰 소비)](#4-lua-스크립트-원자적-다중-대역-토큰-소비)
5. [버킷 삭제: SCAN + UNLINK](#5-버킷-삭제-scan--unlink)
6. [BucketState](#6-bucketstate)

---

## 1. RedisTokenBucketStore

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/store/
└── RedisTokenBucketStore.java
```

Redis 기반의 Token Bucket 저장소입니다. Standalone과 Cluster 모드를 모두 지원합니다.

```java
// RedisTokenBucketStore.java - 실제 코드 (발췌)
/**
 * Redis-backed implementation of token bucket storage.
 *
 * <p>Properties of the storage layer:
 *
 * <ol>
 *   <li>The Lua script uses Redis TIME, not {@code System.nanoTime()} - no clock drift across nodes
 *   <li>Time is tracked in microseconds, which stay inside Lua's exact double range
 *   <li>All bands of one rule are evaluated in a single script call: either every band is charged
 *       or none is, so a rejected request never drains a band that would have allowed it
 *   <li>Token state is not modified on rejection. TOKEN_BUCKET and SLIDING_WINDOW bucket TTLs are
 *       refreshed with {@code EXPIRE}; a FIXED_WINDOW counter keeps its absolute expiry, and only
 *       one left without a TTL gets a {@code PEXPIREAT} at its window end
 *   <li>Returns the binding band's capacity and reset time, for HTTP rate limit headers
 *   <li>Every TOKEN_BUCKET and SLIDING_WINDOW bucket TTL is capped at {@link
 *       #DEFAULT_MAX_BUCKET_TTL} (or the configured {@code fluxgate.redis.max-bucket-ttl}), so a
 *       long window cannot keep forged identity keys resident in Redis for weeks. FIXED_WINDOW
 *       counters are exempt: they expire exactly at the end of their window, which the cap must not
 *       cut short (a monthly quota would otherwise reset weekly)
 *   <li>Each decision reports the Redis time it was taken at, so {@link #refund} can give a
 *       consumption back exactly - the compensation step of cross-rule evaluation
 * </ol>
 *
 * <p>Each store owns its own {@link LuaScriptRegistry}, so two stores pointing at different Redis
 * deployments in one JVM keep independent script SHAs.
 *
 * <p>Thread-safe and suitable for distributed environments with multiple API gateway nodes.
 *
 * <p>Supports both Standalone and Cluster deployments via {@link RedisConnectionProvider}. In
 * cluster mode every bucket key of one call must hash to the same slot; {@link RedisRateLimiter}
 * guarantees that with a hash tag.
 */
public class RedisTokenBucketStore {

  private static final String SCRIPT_NAME = "token_bucket_consume.lua";
  private static final String REFUND_SCRIPT_NAME = "token_bucket_refund.lua";
  private static final long BUCKET_SCAN_COUNT = 1000L;
  private static final int DELETE_BATCH_SIZE = 500;
  private static final int RESULT_SIZE = 8;
  private static final long MICROS_PER_SECOND = 1_000_000L;
  private static final long NANOS_PER_MICRO = 1_000L;

  /** Trailing ARGV value that puts the consume script into check-only mode. */
  private static final String CHECK_ONLY_FLAG = "1";

  /** Shortest window, and shortest sliding sub-bucket, the Lua script accepts: 1 ms. */
  private static final long MIN_WINDOW_MICROS = 1_000L;

  static final long IEEE_754_MAX_PRODUCT = 9_007_199_254_740_992L; // 2^53

  /**
   * Default upper bound on a bucket TTL.
   *
   * <p>A bucket normally lives one window plus 10%. Without a cap, a rule with a 30 day window
   * keeps one Redis hash per distinct key alive for 33 days - and identity keys are cheap to forge,
   * so the keyspace an attacker can pin down is bounded only by the window. Seven days keeps long
   * windows working while bounding that; raise it deliberately if you rate limit over longer
   * periods with a scope whose cardinality you control.
   */
  public static final Duration DEFAULT_MAX_BUCKET_TTL = Duration.ofDays(7);

  private final AtomicBoolean reloadingLuaScript = new AtomicBoolean(false);
  // ... 재적재 throttle 상태 (lastReloadAttemptNanos, reloadIntervalNanos: 1초 → 최대 1분) ...
  private final RedisConnectionProvider connectionProvider;
  private final LuaScriptRegistry scripts;
  private final long maxBucketTtlSeconds;

  public RedisTokenBucketStore(
      RedisConnectionProvider connectionProvider,
      LuaScriptRegistry scripts,
      Duration maxBucketTtl) {
    this.connectionProvider =
        Objects.requireNonNull(connectionProvider, "connectionProvider must not be null");
    this.scripts = Objects.requireNonNull(scripts, "scripts must not be null");

    Duration effectiveTtl = maxBucketTtl != null ? maxBucketTtl : DEFAULT_MAX_BUCKET_TTL;
    if (effectiveTtl.getSeconds() < 1) {
      throw new IllegalArgumentException("maxBucketTtl must be at least 1 second");
    }
    this.maxBucketTtlSeconds = effectiveTtl.getSeconds();

    if (!scripts.isLoaded()) {
      scripts.loadInto(connectionProvider);
    }
  }
}
```

0.3.x 생성자는 "스크립트가 이미 로드되었는지"를 검사하고 아니면 `IllegalStateException`을 던졌습니다.
호출자가 `LuaScriptLoader.loadScripts()`를 먼저 불러야 했고, 순서를 틀리면 런타임에 실패했습니다.
0.4의 스토어는 **자기 스크립트를 스스로 챙깁니다**(`if (!scripts.isLoaded()) scripts.loadInto(...)`).

### 단일 대역 편의 메서드

```java
// RedisTokenBucketStore.java - 실제 코드
/**
 * Try to consume permits from a single band's token bucket.
 */
public BucketState tryConsume(String bucketKey, RateLimitBand band, long permits) {
  Objects.requireNonNull(bucketKey, "bucketKey must not be null");
  Objects.requireNonNull(band, "band must not be null");

  return tryConsume(
      Collections.singletonList(bucketKey), Collections.singletonList(band), permits);
}
```

단일 대역은 **다중 대역 경로에 위임하는 얇은 껍데기**입니다. 코드 경로가 하나이므로 두 경로의 동작이
갈라질 여지가 없습니다.

### 다중 대역 tryConsume (핵심 메서드)

```java
// RedisTokenBucketStore.java - 실제 코드 (발췌)
/**
 * Try to consume permits from every band of one rule, atomically.
 *
 * <p>The bands are evaluated in two passes inside a single Lua call: every band is refilled and
 * checked first, and the tokens are taken only when all of them can serve the request. A rejected
 * request therefore leaves all buckets untouched, which is what makes a multi-band rule enforce
 * its nominal limits rather than a systematically lower one.
 *
 * <p>In cluster mode all keys must live in the same hash slot; {@link RedisRateLimiter} pins them
 * with a hash tag over {@code ruleSetId:ruleId:keyValue}.
 *
 * @param bucketKeys one bucket key per band, in the same order as {@code bands}
 * @param bands the bands of a single rule (capacity, window)
 * @param permits Number of permits to consume
 * @return BucketState describing the binding band: the one that rejected, or the one left with
 *     the fewest tokens
 * @throws InvalidRuleConfigException if {@code permits} exceeds the capacity of any band, which
 *     no amount of waiting could satisfy, or a band's window (or sliding sub-bucket) is shorter
 *     than 1 ms
 * @throws ScriptExecutionException if the Lua script fails or returns an unexpected result
 */
public BucketState tryConsume(List<String> bucketKeys, List<RateLimitBand> bands, long permits) {
  return evaluate(bucketKeys, bands, permits, false);
}

/**
 * Tells whether {@link #tryConsume(List, List, long)} would admit the request, without consuming
 * anything.
 *
 * <p>The script takes the same decision as for a consumption but writes no token, counter or
 * sub-bucket (a rejection only refreshes TTLs, as it always does). {@link RedisRateLimiter} uses
 * it to learn how long rules it did not charge would make a rejected request wait, so the
 * reported Retry-After is the longest one.
 *
 * @param bucketKeys one bucket key per band, in the same order as {@code bands}
 * @param bands the bands of a single rule (capacity, window)
 * @param permits number of permits the request would consume
 * @return the decision a consumption would take; on allow nothing has been consumed
 * @throws InvalidRuleConfigException as for {@link #tryConsume(List, List, long)}
 * @throws ScriptExecutionException if the Lua script fails or returns an unexpected result
 */
public BucketState check(List<String> bucketKeys, List<RateLimitBand> bands, long permits) {
  return evaluate(bucketKeys, bands, permits, true);
}

private BucketState evaluate(
    List<String> bucketKeys, List<RateLimitBand> bands, long permits, boolean checkOnly) {
  Objects.requireNonNull(bucketKeys, "bucketKeys must not be null");
  Objects.requireNonNull(bands, "bands must not be null");

  if (permits <= 0) {
    throw new IllegalArgumentException("permits must be > 0");
  }
  if (bands.isEmpty()) {
    throw new IllegalArgumentException("bands must not be empty");
  }
  if (bucketKeys.size() != bands.size()) {
    throw new IllegalArgumentException(
        "bucketKeys ("
            + bucketKeys.size()
            + ") and bands ("
            + bands.size()
            + ") must have the same size");
  }

  // Fail before touching Redis: a band whose capacity is below the requested permits can
  // never serve the request, so returning a wait time would send the caller into a retry loop.
  // For TOKEN_BUCKET bands also guard against the IEEE-754 double precision limit: the Lua
  // refill formula computes elapsed × capacity / window_micros; if capacity × window_micros
  // exceeds 2^53 the intermediate product loses precision and the refill amount is wrong.
  for (RateLimitBand band : bands) {
    validateWindow(band);
    if (permits > band.getCapacity()) {
      throw new InvalidRuleConfigException(
          "permits ("
              + permits
              + ") exceed the capacity of band '"
              + band.getKeyLabel()
              + "' ("
              + band.getCapacity()
              + ")");
    }
    if (band.getAlgorithm() == RateLimitAlgorithm.TOKEN_BUCKET) {
      long windowMicros = toMicros(band);
      if (band.getCapacity() > 0 && windowMicros > IEEE_754_MAX_PRODUCT / band.getCapacity()) {
        throw new InvalidRuleConfigException(
            "TOKEN_BUCKET band '"
                + band.getKeyLabel()
                + "': capacity ("
                + band.getCapacity()
                + ") × window_micros ("
                + windowMicros
                + ") exceeds 2^53 = "
                + IEEE_754_MAX_PRODUCT
                + "; the Lua token-bucket arithmetic would lose precision. "
                + "Reduce capacity or window, or use SLIDING_WINDOW / FIXED_WINDOW.");
      }
    }
  }

  // KEYS[1..n] = bucketKeys
  // ARGV[1] = permits, ARGV[2] = max_bucket_ttl_seconds, then 5 values per band:
  //   capacity, window_micros, algorithm_code, buckets_or_zero, window_end_micros_or_zero
  // and, in check-only mode, a trailing "1"
  String[] keys = bucketKeys.toArray(new String[0]);
  String[] args = scriptArgs(permits, maxBucketTtlSeconds, bands);
  if (checkOnly) {
    args = Arrays.copyOf(args, args.length + 1);
    args[args.length - 1] = CHECK_ONLY_FLAG;
  }

  List<Long> result =
      executeScriptWithFallback(
          scripts.getTokenBucketConsumeSha(),
          scripts.getTokenBucketConsumeScript(),
          SCRIPT_NAME,
          keys,
          args);

  // [allowed, rejecting_band_index, min_remaining, micros_to_wait,
  //  reset_time_millis, limit, binding_band_index, redis_time_micros]
  if (result == null || result.size() != RESULT_SIZE) {
    throw new ScriptExecutionException(
        "Lua script returned invalid result: " + result, SCRIPT_NAME, null);
  }

  boolean allowed = result.get(0) == 1L;
  long remainingTokens = result.get(2);
  long nanosToWait = result.get(3) * NANOS_PER_MICRO;
  long resetTimeMillis = result.get(4);
  long limit = result.get(5);
  int bandIndex = (int) (result.get(6) - 1L);
  long redisTimeMicros = result.get(7);
  // ... 디버그 로그 후 new BucketState(allowed, remainingTokens, nanosToWait, resetTimeMillis,
  //                                   limit, bandIndex, redisTimeMicros) 반환
}

private static long toMicros(RateLimitBand band) {
  return Math.addExact(
      Math.multiplyExact(band.getWindow().getSeconds(), MICROS_PER_SECOND),
      band.getWindow().getNano() / 1_000L);
}
```

### ARGV 레이아웃

```
ARGV[1]                 permits
ARGV[2]                 max_bucket_ttl_seconds   (TOKEN_BUCKET / SLIDING_WINDOW TTL 상한)

대역 i (1부터), base = 2 + 5 * (i - 1):
ARGV[base + 1]          capacity
ARGV[base + 2]          window_micros            (1 ms 미만은 거부)
ARGV[base + 3]          알고리즘 코드: 1 TOKEN_BUCKET, 2 SLIDING_WINDOW, 3 FIXED_WINDOW
ARGV[base + 4]          SLIDING_WINDOW 서브 버킷 수 [2..60], 그 외 0
ARGV[base + 5]          달력 정렬 FIXED_WINDOW의 윈도 끝(epoch 마이크로초), 그 외 0

마지막 대역 뒤 (선택):
ARGV[3 + 5 * n]         "1" = check-only. 결정만 하고 허용이어도 아무것도 쓰지 않음
```

배열 크기는 `2 + 5 * bands.size()`이고, check-only 모드면 하나 더 붙습니다. 앞의 두 값(permits,
TTL 상한)은 대역 수와 무관하게 자리가 고정되어 있어서, 대역 i의 인덱스는 언제나
`2 + 5 * (i - 1)`부터 다섯 칸입니다. 환불 스크립트(`token_bucket_refund.lua`)도 같은 배열 모양을
쓰되 `ARGV[2]`에 TTL 상한 대신 소비 때 돌려받은 Redis 시각(`now_micros`, 결과 `[8]`)을 넣습니다.
Java 쪽은 두 스크립트가 같은 `scriptArgs(first, second, bands)`로 인자를 만듭니다.

환불 스크립트는 대역마다 실제로 돌려준 permits를 반환하고, 차감한 양보다 많이 돌려주거나 키를 새로
만들지 않습니다. 또 **모든 대역을 먼저 검증한 뒤에야** 환불을 시작합니다. Redis는 오류를 반환한
스크립트를 되돌리지 않으므로, 검증과 기록을 섞으면 잘못된 대역 앞의 대역만 환불된 채 남습니다.

### 마이크로초 vs 나노초

```java
private static final long MICROS_PER_SECOND = 1_000_000L;
private static final long NANOS_PER_MICRO = 1_000L;
```

Java 쪽 API는 나노초를 쓰지만(`BucketState.nanosToWaitForRefill()`), Redis에 넘기는 시간 단위는
**마이크로초**입니다. 스크립트가 돌려준 `micros_to_wait`에 `NANOS_PER_MICRO`를 곱해 나노초로 되돌립니다.

```java
long nanosToWait = result.get(3) * NANOS_PER_MICRO;
```

이유는 Lua 5.1의 수 표현입니다. [Lua 스크립트 절](#4-lua-스크립트-원자적-다중-대역-토큰-소비)의
정밀도 설명을 참고하세요.

`toMicros`가 `Math.multiplyExact` / `Math.addExact`를 쓰는 것도 같은 맥락입니다. 비정상적으로 큰
`Duration`이 조용히 오버플로해서 음수 윈도가 되는 대신 `ArithmeticException`으로 즉시 드러납니다.

### 1-based → 0-based 인덱스 변환

```java
int bandIndex = (int) (result.get(6) - 1L);
```

Lua는 1부터 세고 Java `List`는 0부터 셉니다. 스크립트는 Lua 관례대로 1-based를 돌려주고, 경계를
넘는 변환은 이 한 줄에서만 일어납니다.

호출자는 이 인덱스로 결과를 원래 `RateLimitBand`에 되돌려 매핑합니다.

```java
// RedisRateLimiter.java - 실제 코드
private static RateLimitBand bandAt(List<RateLimitBand> bands, int index) {
  return index >= 0 && index < bands.size() ? bands.get(index) : null;
}
```

### Redis를 건드리기 전에 실패시키는 검증

```java
for (RateLimitBand band : bands) {
  validateWindow(band);                       // 1 ms 미만 윈도·서브 버킷
  if (permits > band.getCapacity()) {
    throw new InvalidRuleConfigException(...);
  }
  if (band.getAlgorithm() == RateLimitAlgorithm.TOKEN_BUCKET) {
    // capacity × window_micros > 2^53 이면 Lua double 산술이 부정확해짐
  }
}
```

용량 10인 대역에 permits 20을 요청하면 **얼마를 기다려도** 통과할 수 없습니다. 이때 대기 시간을
돌려주면 클라이언트가 무한 재시도 루프에 빠집니다. 설정 오류는 설정 오류로 보고하는 것이 맞습니다.
1 ms 미만 윈도(또는 SLIDING_WINDOW 서브 버킷)와, TOKEN_BUCKET에서 `capacity × window_micros`가
2^53을 넘는 대역도 같은 `InvalidRuleConfigException`으로 Redis에 가기 전에 거부됩니다.

스크립트 쪽에도 같은 검사가 있어(`'permits exceed capacity'`, `'window must be at least 1 ms'`,
`'sliding window sub-bucket must be at least 1 ms'`) 스토어를 우회한 호출도 막습니다.

### 핵심 설계 원칙

| 원칙 | 설명 |
|-----|------|
| Redis TIME 사용 | `System.nanoTime()` 대신 Redis 서버 시간 (Clock Drift 방지) |
| 마이크로초 시간 기반 | Lua 5.1 double의 정확한 정수 범위(2^53) 안에 머무름 |
| 한 번의 호출 = 넘겨받은 모든 대역 | 2패스로 처리, 전부 차감 또는 전무. `RedisRateLimiter`는 한 슬롯에 모인 여러 규칙의 대역을 한 번에 넘깁니다 |
| 거부 시 토큰·카운터 미변경 | TOKEN_BUCKET·SLIDING_WINDOW 버킷의 TTL만 갱신 (만료가 없는 FIXED_WINDOW 카운터는 `PEXPIREAT`) |
| check-only | `check()`는 같은 결정을 하되 아무것도 차감하지 않음 |
| 환불 | `refund()`가 규칙 간 보상을 위해 `now_micros`로 차감한 상태만 되돌림 |
| binding 대역 보고 | 용량·리셋 시각을 HTTP 헤더에 쓸 수 있게 반환 |
| 버킷 TTL 상한 | `fluxgate.redis.max-bucket-ttl` (기본 7일). TOKEN_BUCKET·SLIDING_WINDOW에만 적용, FIXED_WINDOW는 윈도 끝에 만료 |

---

## 2. NOSCRIPT 에러 처리 및 스크립트 리로드

Redis가 재시작되면 캐시된 Lua 스크립트가 사라집니다. FluxGate는 이를 자동으로 처리합니다.

```java
// RedisTokenBucketStore.java - 실제 코드
/**
 * Executes the Lua script with NOSCRIPT error fallback.
 *
 * <p>This method handles the case where Redis has been restarted and the script cache is lost.
 * When EVALSHA fails with NOSCRIPT error, it falls back to:
 *
 * <ol>
 *   <li>Execute using EVAL (slower but works)
 *   <li>Reload the script into Redis cache for future calls - at most once at a time, and not
 *       again until a pause has passed (one second, doubled after every failed reload up to a
 *       minute), so a node that keeps answering NOSCRIPT, or a reload that keeps failing, does
 *       not turn every request into extra SCRIPT LOAD round trips
 * </ol>
 *
 * <p>Errors raised by the script itself (Lua {@code redis.error_reply}) arrive as a Lettuce
 * {@link RedisCommandExecutionException} and are translated into {@link
 * ScriptExecutionException}. A command timeout becomes a {@link FluxgateTimeoutException}, and
 * any other driver failure (connection refused or closed, cluster routing) a FluxGate {@link
 * RedisConnectionException}, so that callers only ever see FluxGate exceptions.
 *
 * @param sha the SHA the script is expected to have in Redis
 * @param script the script body, for the EVAL fallback
 * @param scriptName the script name, for error reporting
 * @param keys the keys for the script
 * @param args the arguments for the script
 * @return the script execution result
 */
private List<Long> executeScriptWithFallback(
    String sha, String script, String scriptName, String[] keys, String[] args) {
  try {
    // Try EVALSHA first (efficient, uses cached script)
    return connectionProvider.evalsha(sha, keys, args);
  } catch (RedisNoScriptException e) {
    // Script not in Redis cache (e.g. Redis was restarted)
    log.warn(
        "Lua script {} not found in Redis cache (NOSCRIPT). "
            + "Falling back to EVAL and reloading scripts. SHA: {}",
        scriptName,
        sha);

    // Fallback 1 - Execute using EVAL (slower but works immediately)
    List<Long> result;
    try {
      result = connectionProvider.eval(script, keys, args);
    } catch (RedisCommandExecutionException scriptError) {
      throw scriptFailed(scriptName, scriptError);
    } catch (RedisException driverError) {
      throw driverFailed(scriptName, driverError);
    }

    // Fallback 2 - Reload script for future calls (single, throttled)
    reloadScript();

    return result;
  } catch (RedisCommandExecutionException e) {
    throw scriptFailed(scriptName, e);
  } catch (RedisException e) {
    throw driverFailed(scriptName, e);
  }
}

private static ScriptExecutionException scriptFailed(
    String scriptName, RedisCommandExecutionException e) {
  return new ScriptExecutionException(
      "Lua script execution failed: " + e.getMessage(), scriptName, e);
}

/**
 * Translates a Lettuce failure that is not a script error into a FluxGate exception.
 *
 * <p>The connection is established when the store is created, so any failure here happened while
 * a command was in flight - even one Lettuce reports as a connection error, because a command it
 * queued while reconnecting may have reached Redis. It is therefore reported as a {@link
 * RedisConnectionException.Phase#COMMAND} failure, which the retry policy never retries: retrying
 * a consumption that Redis did execute would charge the request twice.
 */
private static RuntimeException driverFailed(String operation, RedisException e) {
  if (e instanceof RedisCommandTimeoutException) {
    return new FluxgateTimeoutException(
        "Redis command timed out running " + operation + ": " + e.getMessage(), e);
  }
  return new RedisConnectionException(
      "Redis command failed running " + operation + ": " + e.getMessage(),
      e,
      RedisConnectionException.Phase.COMMAND);
}

/**
 * Reloads the Lua scripts into the Redis script cache after a NOSCRIPT error.
 *
 * <p>Only one reload runs at a time, and a new one starts only once the pause since the last
 * attempt has passed: one second after a successful reload, doubled after each failed one up to a
 * minute. Until then the EVAL fallback keeps serving requests.
 */
private void reloadScript() {
  if (reloadAttempted && System.nanoTime() - lastReloadAttemptNanos < reloadIntervalNanos) {
    log.debug("Lua script was reloaded recently, not reloading again yet");
    return;
  }
  if (!reloadingLuaScript.compareAndSet(false, true)) {
    log.debug("Lua script is already being reloaded, skipping...");
    return;
  }

  try {
    lastReloadAttemptNanos = System.nanoTime();
    reloadAttempted = true;
    scripts.loadInto(connectionProvider);
    reloadIntervalNanos = MIN_RELOAD_INTERVAL_NANOS;
  } catch (RuntimeException e) {
    reloadIntervalNanos = Math.min(MAX_RELOAD_INTERVAL_NANOS, reloadIntervalNanos * 2);
    log.error(
        "Failed to reload Lua script, next attempt in {} ms at the earliest: {}",
        reloadIntervalNanos / 1_000_000L,
        e.getMessage(),
        e);
  } finally {
    reloadingLuaScript.set(false);
  }
}
```

### 0.4에서 달라진 세 가지

**(1) 드라이버 예외를 FluxGate 예외로 감쌈.** 스크립트가 `redis.error_reply("permits exceed capacity")`를
반환하면 Lettuce는 `RedisCommandExecutionException`을 던지고, 이것은 `ScriptExecutionException`이
됩니다. 명령 타임아웃은 `FluxgateTimeoutException`, 그 밖의 드라이버 실패(연결 거부·끊김, 클러스터
라우팅)는 `Phase.COMMAND`의 `RedisConnectionException`이 됩니다. 재시도 정책은 `COMMAND` 실패를
재시도하지 않습니다. Redis가 실제로 실행한 소비를 다시 보내면 요청이 두 번 차감되기 때문입니다.
`catch` 절이 EVAL 폴백 안과 바깥 **두 군데**인 것도 두 경로 모두 같은 번역을 거치게 하기 위함입니다.
소비·환불 두 스크립트가 같은 메서드를 쓰고, `scriptName`이 예외에 실립니다.

**(2) `scripts`는 인스턴스 필드.** 0.3.x는 `LuaScripts.getTokenBucketConsumeSha()`라는 static 호출로
SHA를 읽었습니다. 한 JVM에서 서로 다른 Redis를 쓰는 스토어가 둘 있으면 서로의 SHA를 덮어써
NOSCRIPT가 영구화될 수 있었습니다. 이제는 `this.scripts`입니다.

**(3) 재적재는 한 번에 하나, 그리고 간격을 둡니다.** 동시에 하나만 돌고(`AtomicBoolean`), 마지막
시도 뒤 일정 시간이 지나야 다음 시도가 시작됩니다. 성공 뒤에는 1초, 실패할 때마다 두 배로 최대
1분입니다. 계속 NOSCRIPT를 답하는 노드나 계속 실패하는 재적재가 모든 요청을 `SCRIPT LOAD` 왕복으로
만들지 않게 하기 위함입니다. 그동안은 EVAL 폴백이 요청을 처리합니다.

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
|  (느리지만 즉시)   |                 |  (스크립트 전송)  |
+-------------------+                 +------------------+
         |                                    |
         |  Success                           |
         | <--------------------------------- |
         |                                    |
         v                                    |
+-------------------+   SCRIPT LOAD   +------------------+
| reloadScript()    | --------------> |  Redis Server    |
| (AtomicBoolean)   |                 |  (캐시에 저장)    |
+-------------------+                 +------------------+
         |
         | compareAndSet(false, true)
         | -> 동시 리로드 방지
         v
   이후 호출은 다시 EVALSHA 사용
```

EVAL 폴백이 **먼저** 실행되고 리로드가 나중인 순서가 중요합니다. 요청은 즉시 처리되고, 캐시 복구는
그 다음 문제입니다. 순서가 반대면 리로드가 실패하는 동안 요청이 대기합니다.

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
| 리로드 완료 후 | finally에서 false로 복원 | 간격(1초, 실패 시 두 배·최대 1분)이 지난 뒤의 NOSCRIPT에서 리로드 가능 |

Redis 재시작 직후에는 수백 개 요청이 동시에 NOSCRIPT를 받습니다. 락 없이 두면 그만큼의
`SCRIPT LOAD`가 몰립니다. 리로드 실패가 `catch (RuntimeException)`에 갇혀 있는 것도 의도입니다.
EVAL 폴백으로 이미 요청은 처리되었으므로, 캐시 복구 실패가 요청 실패가 되면 안 됩니다.

---

## 3. LuaScriptRegistry: 스크립트 관리

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/script/
├── LuaScriptRegistry.java   # 0.4의 정식 구현
├── LuaScriptLoader.java     # @Deprecated 0.4.0
└── LuaScripts.java          # @Deprecated 0.4.0
```

```java
// LuaScriptRegistry.java - 실제 코드
/**
 * Holds the Lua scripts of one store together with the SHA each of them has on <em>its own</em>
 * Redis.
 *
 * <p>Script bodies are read from the classpath when the registry is created; the SHA is filled in
 * by {@link #loadInto(RedisConnectionProvider)}. Both live in instance state, so two stores
 * pointing at different Redis deployments in one JVM no longer overwrite each other's SHA the way
 * the former process-wide {@link LuaScripts} did.
 *
 * <p>Instances are thread-safe: the script body is immutable and the SHA is volatile.
 */
public final class LuaScriptRegistry {

  /** Classpath location of the multi-band token bucket script. */
  public static final String TOKEN_BUCKET_SCRIPT_PATH = "/lua/token_bucket_consume.lua";

  /** Classpath location of the script that refunds a consumption of a later-rejected request. */
  public static final String TOKEN_BUCKET_REFUND_SCRIPT_PATH = "/lua/token_bucket_refund.lua";

  private final String tokenBucketConsumeScript;
  private final String tokenBucketRefundScript;

  private volatile String tokenBucketConsumeSha;
  private volatile String tokenBucketRefundSha;

  /**
   * Reads every FluxGate Lua script from the classpath.
   *
   * @throws ScriptExecutionException if a script resource is missing or cannot be read
   */
  public LuaScriptRegistry() {
    this.tokenBucketConsumeScript = readScript(TOKEN_BUCKET_SCRIPT_PATH);
    this.tokenBucketRefundScript = readScript(TOKEN_BUCKET_REFUND_SCRIPT_PATH);
  }

  // ... getTokenBucketConsumeScript/Sha, setTokenBucketConsumeSha, getTokenBucketRefundScript/Sha ...

  /**
   * Uploads every script to the given Redis and remembers the SHAs it returns.
   *
   * <p>In cluster mode Lettuce broadcasts {@code SCRIPT LOAD} to all master nodes.
   *
   * @param connectionProvider the Redis connection provider (standalone or cluster)
   * @return the SHA1 hash of the token bucket consume script
   */
  public String loadInto(RedisConnectionProvider connectionProvider) {
    Objects.requireNonNull(connectionProvider, "connectionProvider must not be null");

    String sha = connectionProvider.scriptLoad(tokenBucketConsumeScript);
    String refundSha = connectionProvider.scriptLoad(tokenBucketRefundScript);
    this.tokenBucketConsumeSha = sha;
    this.tokenBucketRefundSha = refundSha;

    log.info(
        "Loaded token_bucket_consume.lua ({}) and token_bucket_refund.lua ({}) into Redis ({} mode)",
        sha,
        refundSha,
        connectionProvider.getMode());
    return sha;
  }

  /**
   * Checks whether the scripts have a known SHA.
   *
   * @return true if every script has been loaded into a Redis
   */
  public boolean isLoaded() {
    return tokenBucketConsumeSha != null && tokenBucketRefundSha != null;
  }

  private static String readScript(String resourcePath) {
    try (InputStream inputStream = LuaScriptRegistry.class.getResourceAsStream(resourcePath)) {
      if (inputStream == null) {
        throw new ScriptExecutionException(
            "Lua script not found on the classpath: " + resourcePath);
      }

      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
        return reader.lines().collect(Collectors.joining("\n"));
      }
    } catch (IOException e) {
      throw new ScriptExecutionException("Failed to read Lua script: " + resourcePath, e);
    }
  }
}
```

### final 본문 + volatile SHA

```java
private final String tokenBucketConsumeScript;   // 생성 시 classpath에서 읽고 불변
private final String tokenBucketRefundScript;
private volatile String tokenBucketConsumeSha;   // loadInto()가 채움
private volatile String tokenBucketRefundSha;
```

두 필드의 수식어가 다릅니다.

- **본문은 `final`**: 클래스패스 리소스는 프로세스 수명 동안 바뀌지 않습니다. 생성자에서 한 번 읽고
  끝입니다. 읽기 실패는 생성 시점에 `ScriptExecutionException`으로 드러납니다.
- **SHA는 `volatile`**: NOSCRIPT 복구가 새 SHA를 써넣고, 그 값이 다른 스레드에 **즉시 보여야**
  합니다. `volatile`이 없으면 일부 스레드가 낡은 SHA로 계속 NOSCRIPT를 맞습니다.

### 폐기된 static 슬롯

```java
// LuaScripts.java - 실제 코드
/**
 * @deprecated Use {@link LuaScriptRegistry} instead. The static slots here are process-wide, so two
 *     {@code RedisTokenBucketStore} instances pointing at different Redis deployments overwrite
 *     each other's SHA. Nothing inside FluxGate reads this class any more; it is kept only so that
 *     existing callers keep compiling and will be removed in a future release.
 */
@Deprecated(since = "0.4.0", forRemoval = true)
public final class LuaScripts {
```

```java
// LuaScriptLoader.java - 실제 코드
/**
 * @deprecated Use {@link LuaScriptRegistry} instead, or simply let {@code RedisTokenBucketStore}
 *     load its own scripts. Every method here writes to the process-wide {@link LuaScripts} slots,
 *     which no part of FluxGate reads any more; the class is kept only so that existing callers
 *     keep compiling and will be removed in a future release.
 */
@Deprecated(since = "0.4.0", forRemoval = true)
public final class LuaScriptLoader {
```

`LuaScriptLoader.loadScripts()`는 호환을 위해 내부에서 레지스트리를 만들어 쓰지만, 그 결과를
deprecated static 슬롯에도 복사합니다. **FluxGate 내부에서 그 슬롯을 읽는 코드는 없습니다.**

```java
// LuaScriptLoader.java - 실제 코드
public static void loadScripts(RedisConnectionProvider connectionProvider) throws IOException {
  LuaScriptRegistry registry = new LuaScriptRegistry();
  String sha = connectionProvider.scriptLoad(registry.getTokenBucketConsumeScript());

  LuaScripts.setTokenBucketConsumeScript(registry.getTokenBucketConsumeScript());
  LuaScripts.setTokenBucketConsumeSha(sha);
}
```

`throws IOException`이 남아 있는 것도 소스 호환 때문이며, Javadoc에 `never thrown`이라고 명시되어
있습니다.

### EVALSHA vs EVAL

| 명령어 | 동작 | 네트워크 비용 | 사용 시점 |
|-------|------|-------------|----------|
| EVALSHA | SHA로 캐시된 스크립트 실행 | 낮음 (40바이트 SHA만) | 정상 상황 |
| EVAL | 스크립트 전체를 전송하여 실행 | 높음 (전체 스크립트 전송) | NOSCRIPT 폴백 |

---

## 4. Lua 스크립트 (원자적 다중 대역 토큰 소비)

```
fluxgate-redis-ratelimiter/src/main/resources/lua/
├── token_bucket_consume.lua   소비(또는 check-only)
└── token_bucket_refund.lua    규칙 간 보상용 환불
```

### 헤더: 계약

```lua
--[[
Multi-Band Rate Limiter — TOKEN_BUCKET, SLIDING_WINDOW, FIXED_WINDOW

DESIGN NOTES:
1. Uses Redis TIME (not System.nanoTime()) — solves clock drift across nodes
2. Microsecond time base kept inside double precision — see PRECISION below
3. All bands of one rule are evaluated in two passes: check every band first,
   then either consume from every band or from none. A rejected request never
   drains a band that would have allowed it.
4. Counter state (SLIDING_WINDOW, FIXED_WINDOW) is never modified on rejection;
   TOKEN_BUCKET state is not modified either; only the TTLs of TB/SW buckets
   are refreshed so buckets that see nothing but rejections still expire on schedule.
5. TTL = min(max_bucket_ttl, max(1, ceil(window_seconds * 1.1))) for TOKEN_BUCKET
   and SLIDING_WINDOW. FIXED_WINDOW uses PEXPIREAT at the absolute window end, rounded
   UP to the millisecond, and always sets one so the counter does not outlive its window.
6. A FIXED_WINDOW counter is a hash {count, window_end_micros}: it records WHICH window it
   counts. The TTL alone cannot separate windows - Redis treats a key as expired only when
   now > expiry, so a call in the exact expiry millisecond (or an app clock that is ahead of
   Redis on a calendar boundary) would read the old count and carry it into the next window.
   The window identity is therefore compared explicitly:
     stored end <  requested end  -> a new window: the count restarts at 0
     stored end == requested end  -> the same window
     stored end >  requested end  -> the caller lags behind (clock skew); the newer stored
                                     window and its count win, so a lagging node cannot
                                     reset a period that has already started
7. A SLIDING_WINDOW field is named "<sub-bucket index>@<sub-bucket duration in micros>". An index
   only means something together with the duration it was computed with: after the window or
   the sub-bucket count changes, the old indices are scaled differently and would look like
   sub-buckets far in the future (or past) of the new geometry. Only fields of the current
   duration whose index lies in [current - buckets + 1, current] are counted; every other field
   (another geometry, a sub-bucket after the current one after a clock step back, the
   bare-index format of earlier 0.4 builds) is ignored, and removed on the next admitted
   request. A changed geometry therefore starts a fresh window instead of rejecting forever.
8. Windows below 1 ms, and sliding sub-buckets below 1 ms, are refused: they would divide by
   zero or produce indices beyond the exact integer range, and no Redis round trip resolves
   them anyway. Every index is written with string.format('%.0f'), never tostring, which would
   switch to exponent notation (and collide) for large values.
9. Check-only mode runs pass 1 and returns: nothing is consumed. It lets the Java caller ask a
   rule it did not charge how long it would make a request wait (see note 10), without the
   charge-and-refund that a real consumption would need. Like a rejection, it only refreshes
   TTLs when it rejects.
10. Pass 1 checks every band even after one rejected (still without writing), and reports the
    rejecting band whose wait is longest. Reporting the first one would hand out a Retry-After
    after which another band still rejects.
11. A rejecting SLIDING_WINDOW band waits until enough counted requests have left the window for
    the request to fit: its sub-buckets are walked oldest to newest, adding up their counts, and
    the first sub-bucket k after whose departure total - freed + permits <= capacity sets the
    wait to (k + buckets) * sub_duration - now. A burst inside one sub-bucket therefore waits
    almost a whole window, not just until the next sub-bucket starts.

PRECISION: Redis runs Lua 5.1, which has no integer type — every number is an
IEEE-754 double with an exact integer range of 2^53 (about 9.0e15). Microseconds
since the epoch are about 1.76e15, inside the exact range; nanoseconds (~1.76e18)
were not. Everything written to a hash therefore goes through
string.format('%.0f', v). The intermediate product capacity × window_micros can
exceed 2^53; the Java caller validates this for TOKEN_BUCKET bands (the
IEEE_754_MAX_PRODUCT check in RedisTokenBucketStore).

KEYS[1..n] = bucket keys, one per band of ONE rule, all in the same hash tag
  e.g. "fluxgate:bucket:{api-limits:per-ip:ip:192.168.1.100}:100-per-60s"

ARGV layout:
  ARGV[1]           = permits (number of tokens/requests to consume, usually 1)
  ARGV[2]           = max_bucket_ttl_seconds (upper bound on every TOKEN_BUCKET /
                      SLIDING_WINDOW bucket TTL; FIXED_WINDOW uses PEXPIREAT instead)

  Per band i (1-indexed), at base offset = 2 + 5 * (i - 1):
    ARGV[base + 1]  = capacity  (max tokens / requests allowed per window)
    ARGV[base + 2]  = window_micros  (window duration in microseconds)
    ARGV[base + 3]  = algorithm_code
                        1 = TOKEN_BUCKET   (continuous refill; hash {tokens, last_refill_micros})
                        2 = SLIDING_WINDOW (N sub-buckets; hash {"<index>@<sub_dur>": count},
                                            see note 7)
                        3 = FIXED_WINDOW   (tumbling counter; hash {count, window_end_micros}
                                            with PEXPIREAT)
    ARGV[base + 4]  = buckets_or_zero
                        SLIDING_WINDOW: number of sub-buckets [2..60]
                        others: 0
    ARGV[base + 5]  = window_end_micros_or_zero
                        FIXED_WINDOW with calendar alignment: absolute end of the current
                          period in microseconds since epoch, computed by Java
                        FIXED_WINDOW without calendar alignment: 0 — Lua derives from now
                        TOKEN_BUCKET / SLIDING_WINDOW: 0

  Optional, after the last band:
    ARGV[3 + 5 * band_count] = "1" for check-only mode: the decision is taken exactly as for a
                               consumption, but no token, counter or sub-bucket is written on
                               allow either (see note 9). Any other value, or no value, consumes.

  Total ARGV length: 2 + 5 * band_count, or 3 + 5 * band_count with the check-only flag

Returns array of 8 integers:
  [1] allowed                 1 if all bands allowed, 0 otherwise
  [2] rejecting_band_index    1-based index of the rejecting band with the LONGEST wait (the
                              first of them on a tie), 0 when allowed - see note 10
  [3] min_remaining           binding band's tokens/capacity left after consumption (allow;
                              before it in check-only mode) or the rejecting band's (reject)
  [4] micros_to_wait          microseconds until the rejecting band can retry, 0 when allowed;
                              the longest wait of all rejecting bands, so a retry after it is
                              not refused again by another band that also rejected
  [5] reset_time_millis       epoch millis when the binding band resets:
                                TOKEN_BUCKET:   when the bucket is FULL again (after consumption)
                                SLIDING_WINDOW: end of the current sub-bucket cycle, rounded up
                                                to the millisecond
                                FIXED_WINDOW:   end of the current window
  [6] limit                   capacity of the binding band
  [7] binding_band_index      1-based index of the binding band (= rejecting_band_index on reject)
  [8] now_micros              the Redis TIME this decision was taken at; token_bucket_refund.lua
                              uses it to find the sliding sub-bucket / fixed window it charged

Errors (redis.error_reply):
  'at least one bucket key is required'
  'expected N arguments for M band(s)'   — ARGV length mismatch
  'permits must be positive'
  'max bucket ttl must be >= 1 second'
  'capacity must be positive'
  'window must be positive'
  'window must be at least 1 ms'
  'sliding window sub-bucket must be at least 1 ms'
  'permits exceed capacity'              — band can never serve; Java pre-validates for TOKEN_BUCKET
  'buckets must be >= 2 for SLIDING_WINDOW'
  'unknown algorithm code: N'
]]
```

### 정밀도: 왜 마이크로초인가

0.3.x 스크립트는 나노초를 썼고, **그것이 버그였습니다.**

| 값 | 크기 | Lua 5.1 double(2^53 ≈ 9.0e15)에서 |
|---|------|----------------------------------|
| epoch 마이크로초 | 약 1.76e15 | 정확히 표현 가능 ✓ |
| epoch 나노초 | 약 1.76e18 | **범위 밖** ✗ |

Redis의 Lua 5.1에는 정수 타입이 없습니다. 모든 수가 IEEE-754 double이고, 정확한 정수 범위는
2^53입니다. 나노초 타임스탬프는 그 범위를 넘고, `redis.call`이 Lua 기본 `%.14g` 포맷으로
직렬화하면서 **`"1.76e+18"`이라는 문자열로 저장**되었습니다. 다시 읽으면 정밀도가 날아간
값입니다.

두 가지 대응이 함께 들어갔습니다.

1. 시간 단위를 마이크로초로 낮춤 (1.76e15는 안전 범위)
2. 해시에 쓰는 **모든** 값을 `string.format('%.0f', v)`로 통과시킴

```lua
redis.call('HMSET', KEYS[i],
    'tokens',             string.format('%.0f', remaining),
    'last_refill_micros', string.format('%.0f', tb_refills[i]))
```

0.3.x 문서와 주석의 **"정수 연산만 사용(integer arithmetic only)"** 은 사실이 아니었습니다.
Lua 5.1에는 정수가 없습니다. 정확히 말하면 "double의 정확한 정수 범위 안에 머무르는 산술"입니다.

중간 곱 `elapsed * capacity`는 두 항이 모두 크면 여전히 2^53을 넘을 수 있습니다. 헤더가 제시하는
안전 조건은 `capacity × window_micros ≤ 2^53`입니다. 이후의 나눗셈이 토큰 오차를 1e-9 아래로
유지하지만, 극단적 설정에서는 이 한계를 의식해야 합니다.

### 필드 이름이 바뀐 이유와 업그레이드 영향

해시 필드가 `last_refill_nanos` → `last_refill_micros`로 바뀌었습니다. 스크립트는 구 필드를
**읽지 않습니다.**

```lua
-- Missing bucket (or a 0.3.x bucket whose 'last_refill_micros' is absent):
-- start full, which allows the initial burst.
if cur_tokens == nil or last_refill == nil then
    cur_tokens  = capacity
    last_refill = now_micros
end
```

0.3.x 버킷은 `last_refill_micros`가 없으므로 이 분기에 들어가 **가득 찬 상태로 한 번 재초기화**됩니다.
업그레이드 시 일회성 quota 리셋이 일어난다는 뜻입니다. 이것이 의도된 마이그레이션 동작입니다.
잘못된 단위의 타임스탬프를 해석하려 시도하면 훨씬 이상한 결과가 나옵니다.

### 인자 파싱과 검증

```lua
local band_count = #KEYS
if band_count < 1 then
    return redis.error_reply("at least one bucket key is required")
end
if #ARGV ~= 2 + 5 * band_count and #ARGV ~= 3 + 5 * band_count then
    return redis.error_reply(
        "expected " .. (2 + 5 * band_count) .. " arguments for " .. band_count .. " band(s)")
end
local check_only = #ARGV == 3 + 5 * band_count and ARGV[#ARGV] == '1'

local permits = tonumber(ARGV[1])
if permits == nil or permits <= 0 then
    return redis.error_reply("permits must be positive")
end

local max_ttl_seconds = tonumber(ARGV[2])
if max_ttl_seconds == nil or max_ttl_seconds < 1 then
    return redis.error_reply("max bucket ttl must be >= 1 second")
end

local capacities    = {}
local windows       = {}
local algorithms    = {}
local bucket_counts = {}   -- sub-bucket count for SLIDING_WINDOW
local win_ends_in   = {}   -- supplied window end for FIXED_WINDOW (0 = derive)

for i = 1, band_count do
    local base      = 2 + 5 * (i - 1)
    local capacity  = tonumber(ARGV[base + 1])
    local win_micros = tonumber(ARGV[base + 2])
    local alg       = tonumber(ARGV[base + 3]) or ALG_TOKEN_BUCKET
    local buckets   = tonumber(ARGV[base + 4]) or 0
    local win_end   = tonumber(ARGV[base + 5]) or 0

    if capacity == nil or capacity <= 0 then
        return redis.error_reply("capacity must be positive")
    end
    if win_micros == nil or win_micros <= 0 then
        return redis.error_reply("window must be positive")
    end
    if win_micros < 1000 then
        return redis.error_reply("window must be at least 1 ms")
    end
    if permits > capacity then
        return redis.error_reply("permits exceed capacity")
    end
    if alg == ALG_SLIDING_WINDOW and buckets < 2 then
        return redis.error_reply("buckets must be >= 2 for SLIDING_WINDOW")
    end
    if alg == ALG_SLIDING_WINDOW and math.floor(win_micros / buckets) < 1000 then
        return redis.error_reply("sliding window sub-bucket must be at least 1 ms")
    end

    capacities[i]    = capacity
    windows[i]       = win_micros
    algorithms[i]    = alg
    bucket_counts[i] = buckets
    win_ends_in[i]   = win_end
end
```

인자 개수를 `2 + 5 * band_count`(check-only 플래그가 있으면 `3 + 5 * band_count`)로 검증하는 것이
첫 방어선입니다. 개수가 맞지 않으면 인덱스가 어긋나 엉뚱한 값을 capacity로 읽게 되므로, 조용히 잘못
동작하기 전에 거부합니다. 1 ms 미만 윈도와 1 ms 미만 서브 버킷은 0 나눗셈이나 정확한 정수 범위를
벗어나는 인덱스를 만들기 때문에 거부합니다. 환불 스크립트도 같은 조건을 같은 메시지로 거부합니다.

### Redis TIME과 TTL

```lua
local time_info  = redis.call('TIME')
-- time_info[1] = seconds since epoch, time_info[2] = microseconds within the second
local now_micros = tonumber(time_info[1]) * 1000000 + tonumber(time_info[2])

-- TTL in whole seconds, capped at max_ttl_seconds (for TOKEN_BUCKET and SLIDING_WINDOW).
-- min=1s so a sub-second window still gets a bucket that expires.
local function ttl_for_window(win_micros)
    return math.min(max_ttl_seconds, math.max(1, math.ceil(win_micros / 1000000 * 1.1)))
end
```

TTL 공식은 다음과 같습니다.

```
TTL = min(max_bucket_ttl, max(1, ceil(window_seconds * 1.1)))
```

| 요소 | 이유 |
|-----|------|
| `window * 1.1` | 시계 오차를 감당할 10% 여유 |
| `max(1, ...)` | 서브초 윈도가 TTL 0(= 즉시 만료)이 되는 것을 방지 |
| `min(max_ttl, ...)` | 호출자가 준 상한. 위조 가능한 키가 Redis를 몇 주씩 점유하는 것을 방지 |

상한은 **하드코딩 24시간이 아니고, 없는 것도 아닙니다.** 호출자가 `ARGV[2]`로 넘기며 기본값은
`fluxgate.redis.max-bucket-ttl`의 7일입니다. 스크립트가 1초 미만 상한을 거부하는 이유는, 0이나 음수가
들어오면 모든 버킷이 즉시 만료되어 Rate Limiting이 사실상 사라지기 때문입니다.

상한이 윈도보다 짧으면 규칙이 실제로 강제하는 한도가 달라집니다. `RedisRateLimiter`가 그 상황을
규칙마다 한 번 WARN으로 알립니다.

이 상한은 TOKEN_BUCKET과 SLIDING_WINDOW에만 걸립니다. FIXED_WINDOW 카운터는 `PEXPIREAT`로 윈도 끝에
정확히 만료되므로 상한과 무관합니다.

### Pass 1: 모든 대역을 읽고 확인만 한다

Pass 1은 대역마다 상태를 읽고 요청을 서비스할 수 있는지 확인만 합니다. 쓰기는 하지 않습니다. 부족한
대역을 만나도 **즉시 반환하지 않고** `reject()`로 기록한 뒤 다음 대역을 계속 확인합니다(헤더 note 10).

```lua
-- TOKEN_BUCKET
local tb_tokens  = {}   -- token count after refill
local tb_refills = {}   -- next refill timestamp (micros)

-- SLIDING_WINDOW
local sw_sub_dur = {}   -- sub-bucket duration (micros)
local sw_cur_sub = {}   -- current sub-bucket index
local sw_sums    = {}   -- sum of active request counts
local sw_raw     = {}   -- raw HGETALL output, reused in pass 2 to avoid a second round-trip

-- FIXED_WINDOW
local fw_counts  = {}   -- current counter value
local fw_ends    = {}   -- resolved window end (micros)

-- The rejecting band with the longest wait so far (note 10): its result array, or nil.
local rejection = nil
local function reject(i, remaining, wait, reset_millis)
    if rejection == nil or wait > rejection[4] then
        rejection = {0, i, remaining, wait, reset_millis, capacities[i], i, now_micros}
    end
end
```

TOKEN_BUCKET 분기:

```lua
for i = 1, band_count do
    local capacity   = capacities[i]
    local win_micros = windows[i]
    local alg        = algorithms[i]

    -- ---- TOKEN_BUCKET ----
    if alg == ALG_TOKEN_BUCKET then
        local data       = redis.call('HMGET', KEYS[i], 'tokens', 'last_refill_micros')
        local cur_tokens = tonumber(data[1])
        local last_refill = tonumber(data[2])

        -- Missing bucket (or a 0.3.x bucket whose 'last_refill_micros' is absent):
        -- start full, which allows the initial burst.
        if cur_tokens == nil or last_refill == nil then
            cur_tokens  = capacity
            last_refill = now_micros
        end

        -- math.max handles a clock that moved backwards; math.min caps at one window so
        -- elapsed * capacity stays inside the 2^53 safe range (the Java caller validates this).
        local elapsed = math.min(math.max(0, now_micros - last_refill), win_micros)

        -- Only whole tokens are credited, and the timestamp advances only by the time those
        -- tokens cost, so the sub-token remainder is carried into the next call.
        local to_add      = math.floor(elapsed * capacity / win_micros)
        local next_refill = last_refill
        if to_add > 0 then
            next_refill = last_refill + math.floor(to_add * win_micros / capacity)
        end
        local refilled = math.min(capacity, cur_tokens + to_add)
        if refilled >= capacity then
            -- Bucket is full; no deficit to carry.
            next_refill = now_micros
        end

        tb_tokens[i]  = refilled
        tb_refills[i] = next_refill

        if refilled < permits then
            local tokens_needed = permits - refilled
            local wait          = math.ceil(tokens_needed * win_micros / capacity)
            local deficit       = capacity - refilled
            local full_micros   = deficit > 0 and math.ceil(deficit * win_micros / capacity) or 0
            local reset_millis  = math.floor((now_micros + full_micros) / 1000)
            reject(i, refilled, wait, reset_millis)
        end
    ...
```

SLIDING_WINDOW 분기는 `HGETALL`로 현재 기하(`<index>@<sub_dur>`)의 서브 버킷만 합산하고(note 7),
거부할 때는 가장 오래된 서브 버킷부터 카운트를 더해 요청이 들어갈 만큼 비는 첫 서브 버킷 k를 찾습니다(note 11).

```lua
        if total + permits > capacity then
            local remaining = capacity - total
            -- micros_to_wait (note 11): sub-bucket k stops counting at (k + buckets) * sub_dur, and
            -- the sub-buckets leave oldest first. Wait for the first one whose departure frees
            -- enough: total - freed + permits <= capacity. permits <= capacity, so the newest
            -- one (at the latest cur_sub, i.e. a full window) always frees enough.
            table.sort(counted, function(a, b) return a[1] < b[1] end)
            local free_at = cur_sub
            local freed   = 0
            for _, entry in ipairs(counted) do
                freed = freed + entry[2]
                if total - freed + permits <= capacity then
                    free_at = entry[1]
                    break
                end
            end
            local wait = math.max(0, (free_at + buckets) * sub_dur - now_micros)
            -- reset_time_millis = when everything counted now has left the window, rounded UP to
            -- the millisecond (sub_dur need not be a whole millisecond): never before the retry
            -- above is allowed.
            local reset_millis = math.ceil((cur_sub + buckets) * sub_dur / 1000)
            reject(i, remaining, wait, reset_millis)
        end
```

FIXED_WINDOW 분기는 `{count, window_end_micros}`를 읽어 같은 윈도인지 확인하고(note 6), 거부할 때는 TTL이
없는 카운터(`PTTL == -1`)에만 `PEXPIREAT`을 겁니다.

```lua
        if count + permits > capacity then
            -- A counter must never be left without a TTL, or it would stay resident for good.
            if redis.call('PTTL', KEYS[i]) == -1 then
                redis.call('PEXPIREAT', KEYS[i], string.format('%.0f', fixed_window_expire_millis(win_end)))
            end
            local remaining    = capacity - count
            local wait         = math.max(0, win_end - now_micros)
            local reset_millis = math.floor(win_end / 1000)
            reject(i, remaining, wait, reset_millis)
        end
```

Pass 1이 끝나면 거부가 하나라도 있었을 때 TTL만 갱신하고 대기가 가장 긴 거부를 반환합니다.

```lua
if rejection ~= nil then
    refresh_ttls()
    return rejection
end
```

```lua
-- Refresh TTLs for TOKEN_BUCKET and SLIDING_WINDOW keys on the reject path.
-- EXPIRE is a no-op on keys that do not exist yet; FIXED_WINDOW keys are not touched
-- because PEXPIREAT (an absolute timestamp) must not be overridden with a relative one.
local function refresh_ttls()
    for j = 1, band_count do
        if algorithms[j] == ALG_TOKEN_BUCKET or algorithms[j] == ALG_SLIDING_WINDOW then
            redis.call('EXPIRE', KEYS[j], ttl_for_window(windows[j]))
        end
    end
end
```

#### 잔여분 이월: 0.4의 정확성 수정

이 부분이 미묘하지만 중요합니다.

```lua
local to_add      = math.floor(elapsed * capacity / win_micros)
local next_refill = last_refill
if to_add > 0 then
    next_refill = last_refill + math.floor(to_add * win_micros / capacity)
end
```

0.3.x는 성공 시 `last_refill`을 **현재 시각으로** 갱신했습니다. `math.floor`로 버려진 1토큰 미만의
잔여 시간이 매 호출마다 사라집니다.

```
용량 100, 윈도 60초 (토큰 1개 = 600ms)

0.3.x: 매 500ms마다 요청
  → to_add = floor(500ms / 600ms) = 0
  → last_refill = now  ← 500ms가 버려짐
  → 영원히 리필되지 않습니다

0.4: 매 500ms마다 요청
  → to_add = 0 → next_refill = last_refill (그대로)
  → 다음 호출에서 1000ms 경과 → 1토큰 리필, 타임스탬프는 600ms만 전진
  → 남은 400ms는 다음 호출로 이월
```

즉 0.3.x는 **상시 과소 허용**이었습니다. 설정한 한도보다 적게 통과시켰고, 요청 간격이 토큰 비용보다
짧을수록 심해졌습니다. 타임스탬프를 "그 토큰들이 소요한 시간만큼만" 전진시키는 것이 수정입니다.

버킷이 가득 차면 이월할 부족분이 없으므로 `now_micros`로 맞춥니다.

#### 첫 거부에서 멈추지 않는 이유

첫 번째로 거부한 대역만 보고하면 그 `Retry-After` 뒤에 재시도해도 다른 대역이 여전히 거부할 수
있습니다. 그래서 Pass 1은 모든 대역을 끝까지 확인하고 **대기가 가장 긴** 거부(동률이면 앞선 대역)를
돌려줍니다. 어떤 대역도 쓰지 않으므로 끝까지 확인해도 비용은 읽기뿐입니다.

#### 거부 시 TTL만 갱신

거부 경로가 아무 상태도 안 쓰는 것이 아니라 **만료 시각은 갱신합니다.** 헤더 note 4가 이유를 설명합니다.

> only the TTLs of TB/SW buckets are refreshed so buckets that see nothing but rejections still
> expire on schedule.

거부만 계속 받는 버킷의 TTL을 갱신하지 않으면, 마지막으로 허용된 요청 시점의 TTL로 만료됩니다.
공격 트래픽이 계속 들어오는 버킷이 만료 직전 상태로 방치되는 셈입니다.

`refresh_ttls()`는 거부한 대역뿐 아니라 **모든** TOKEN_BUCKET·SLIDING_WINDOW 대역을 돕니다. 앞서 확인을
통과한 대역들의 버킷도 실재하고, 그들의 TTL도 함께 연장되어야 대역 간 만료 시점이 어긋나지 않습니다.
FIXED_WINDOW 키는 건너뜁니다. 윈도 끝의 절대 시각(`PEXPIREAT`)을 상대 TTL로 덮으면 안 되기 때문입니다.

`EXPIRE`는 존재하지 않는 키에 no-op이므로, 아직 만들어지지 않은 버킷에 대해서도 안전합니다.

#### micros_to_wait vs reset_time_millis

거부 경로가 두 개의 시간을 계산합니다. 같은 것이 아닙니다. TOKEN_BUCKET 기준으로:

| 값 | 의미 | 계산 |
|---|------|------|
| `micros_to_wait` (`wait`) | **요청 1개**를 처리할 만큼 리필될 때까지 | `ceil(tokens_needed * window / capacity)` |
| `reset_time_millis` (`reset_millis`) | 버킷이 **가득 찰** 시각 | `floor((now + ceil(deficit * window / capacity)) / 1000)` |

```
용량 100, 윈도 60초, 현재 토큰 0, permits 1

wait        = ceil(1 * 60,000,000 / 100)   = 600,000 μs  = 0.6초
full_micros = ceil(100 * 60,000,000 / 100) = 60,000,000 μs = 60초
```

전자가 `Retry-After`가 되고, 후자가 `RateLimit-Reset`이 됩니다. 두 헤더가 다른 질문에 답하는 이유이며,
허용 경로와 의미를 일치시킨 것이 0.4입니다. SLIDING_WINDOW의 리셋 시각은 지금 세어진 요청이 모두 윈도를
떠나는 시각을 ms로 **올림**한 값(서브 버킷 길이가 ms의 정수배가 아닐 수 있으므로), FIXED_WINDOW는 윈도
끝입니다.

### check-only 모드

`ARGV`의 마지막이 `"1"`이면 Pass 1만 하고 반환합니다. 거부라면 위와 똑같이 TTL만 갱신하고, 허용이라면
남은 양이 가장 적은 대역을 보고하되 **아무것도 차감하지 않습니다**(note 9). `RedisTokenBucketStore.check()`가
이 모드를 쓰며, `RedisRateLimiter`는 차감하지 않은 뒤 규칙들의 대기 시간을 알아내는 데 씁니다.

```lua
if check_only then
    -- Note 9: every band would serve the request; report the most restrictive one, unconsumed.
    local binding, binding_remaining = 1, nil
    for i = 1, band_count do
        local remaining
        if algorithms[i] == ALG_TOKEN_BUCKET then
            remaining = tb_tokens[i]
        elseif algorithms[i] == ALG_SLIDING_WINDOW then
            remaining = capacities[i] - sw_sums[i]
        else
            remaining = capacities[i] - fw_counts[i]
        end
        if binding_remaining == nil or remaining < binding_remaining then
            binding, binding_remaining = i, remaining
        end
    end
    return {1, 0, binding_remaining, 0, 0, capacities[binding], binding, now_micros}
end
```

### Pass 2: 전부 가능하므로 모두 기록

```lua
local binding           = 1
local binding_remaining = nil

for i = 1, band_count do
    local capacity   = capacities[i]
    local win_micros = windows[i]
    local alg        = algorithms[i]

    -- ---- TOKEN_BUCKET ----
    if alg == ALG_TOKEN_BUCKET then
        local remaining = tb_tokens[i] - permits
        redis.call('HMSET', KEYS[i],
            'tokens',             string.format('%.0f', remaining),
            'last_refill_micros', string.format('%.0f', tb_refills[i]))
        redis.call('EXPIRE', KEYS[i], ttl_for_window(win_micros))
        tb_tokens[i] = remaining   -- updated for reset_time_millis calculation below

        if binding_remaining == nil or remaining < binding_remaining then
            binding           = i
            binding_remaining = remaining
        end
    ...   -- SLIDING_WINDOW: 세지 않은 필드 HDEL + 현재 서브 버킷 HINCRBY + EXPIRE
    ...   -- FIXED_WINDOW:   HSET count, window_end_micros + PEXPIREAT
end
```

리셋 시각은 차감 **후**에 binding 대역의 알고리즘에 따라 계산합니다.

```lua
-- ========================================================================
-- Compute reset_time_millis for the binding band after consumption
-- ========================================================================
local binding_capacity = capacities[binding]
local binding_alg      = algorithms[binding]
local reset_millis

if binding_alg == ALG_TOKEN_BUCKET then
    -- Time until the binding bucket is completely full again (computed AFTER consumption).
    local remaining   = tb_tokens[binding]   -- already updated in pass 2
    local deficit     = binding_capacity - remaining
    local full_micros = deficit > 0 and math.ceil(deficit * windows[binding] / binding_capacity) or 0
    reset_millis = math.floor((now_micros + full_micros) / 1000)

elseif binding_alg == ALG_SLIDING_WINDOW then
    -- End of the current sub-bucket cycle (when all requests in current sub expire), rounded UP
    -- to the millisecond like the reject path.
    local cur_sub  = sw_cur_sub[binding]
    local buckets  = bucket_counts[binding]
    local sub_dur  = sw_sub_dur[binding]
    reset_millis = math.ceil((cur_sub + buckets) * sub_dur / 1000)

elseif binding_alg == ALG_FIXED_WINDOW then
    reset_millis = math.floor(fw_ends[binding] / 1000)
end

return {1, 0, binding_remaining, 0, reset_millis, binding_capacity, binding, now_micros}
```

2패스 구조가 다중 대역 규칙의 정확성을 만듭니다.

```
"초당 10개 AND 분당 100개" 규칙, 분당 대역이 소진된 상태

0.3.x (대역마다 개별 호출):
  초당 대역 tryConsume → 허용, 토큰 차감됨
  분당 대역 tryConsume → 거부
  → 요청은 429인데 초당 대역의 토큰은 사라졌습니다
  → 초당 한도가 실효적으로 10개보다 낮아집니다

0.4 (한 번의 호출, 2패스):
  Pass 1: 초당 대역 확인(OK) → 분당 대역 확인(부족, reject 기록) → 남은 대역도 확인
          → 아무것도 쓰지 않고 TTL만 갱신, 대기가 가장 긴 거부를 반환
  → 초당 대역의 토큰이 그대로 남습니다
```

`binding`은 차감 **후** 남은 양이 가장 적은 대역이고, 리셋 시각도 차감 후에 계산합니다.
차감 전에 계산하면 "이 요청이 없었다면 가득 찰 시각"을 알려주게 되어, 클라이언트가 실제보다
이른 시각을 믿습니다.

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
         |  - 넘겨받은 모든 대역을 2패스로                  |
         |  - 전부 차감 또는 전무                          |
         |  - Race Condition 없음                         |
         |  - 요청당 한 번의 왕복 (슬롯이 갈리면 규칙당)    |
         +------------------------------------------------+
```

규칙 사이는 키가 모두 한 슬롯에 들어갈 때(단독 Redis는 항상) 한 번의 호출로 원자적으로 평가되고,
클러스터에서 슬롯이 갈리면 규칙을 하나씩 차감하며 거부 시 환불하는 보상으로 처리됩니다. 이 보상은
원자적이지 않습니다. 자세한 내용은
[Redis RateLimiter 모듈](redis-ratelimiter.ko.md#규칙-간-한-번의-호출-아니면-보상)에 있습니다.

---

## 5. 버킷 삭제: SCAN + UNLINK

룰이 변경되면 관련 버킷을 지워 Rate Limit 상태를 리셋합니다.

```java
// RedisTokenBucketStore.java - 실제 코드
/**
 * Deletes all token buckets belonging to the given rule set.
 *
 * <p>This is used when rules are changed to reset rate limit state. The pattern comes from {@link
 * RedisRateLimiter#bucketKeyPattern(String)}, so it can only ever match bucket keys - never the
 * rule set definitions under {@code fluxgate:ruleset:*}.
 *
 * <p>Uses SCAN semantics to avoid blocking Redis on production-sized keyspaces, unlinks every
 * SCAN page as it arrives so memory stays bounded by the page size, and uses UNLINK where the
 * server supports it so that freeing the keys happens off the event loop.
 *
 * @param ruleSetId the rule set ID to match
 * @return the number of buckets deleted
 */
public long deleteBucketsByRuleSetId(String ruleSetId) {
  Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");

  String pattern = RedisRateLimiter.bucketKeyPattern(ruleSetId);
  log.debug("Deleting token buckets matching pattern: {}", pattern);

  long deleted = scanAndUnlink(pattern);
  if (deleted == 0) {
    log.debug("No token buckets found for ruleSetId: {}", ruleSetId);
    return 0;
  }

  log.info("Deleted {} token buckets for ruleSetId: {}", deleted, ruleSetId);
  return deleted;
}

/**
 * Deletes all token buckets (full reset).
 *
 * <p>This is used when a full reload is triggered to reset all rate limit state. The pattern is
 * {@code fluxgate:bucket:*}, which is disjoint from {@code fluxgate:ruleset:*} and {@code
 * fluxgate:rulesets} - a full reset can no longer destroy rule set definitions stored in Redis.
 *
 * <p>Uses SCAN semantics to avoid blocking Redis on production-sized keyspaces, and unlinks every
 * SCAN page as it arrives.
 *
 * @return the number of buckets deleted
 */
public long deleteAllBuckets() {
  String pattern = RedisRateLimiter.BUCKET_KEY_PREFIX + "*";
  log.debug("Deleting all token buckets matching pattern: {}", pattern);

  long deleted = scanAndUnlink(pattern);
  if (deleted == 0) {
    log.debug("No token buckets found");
    return 0;
  }

  log.info("Deleted {} token buckets (full reset)", deleted);
  return deleted;
}

/**
 * Unlinks the keys matching {@code pattern} page by page as SCAN returns them, so the keys of a
 * large rule set are never held in memory all at once. Lettuce failures are translated into
 * FluxGate exceptions as for the scripts (see {@code driverFailed}).
 */
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

### 0.4에서 고쳐진 세 가지

| 0.3.x | 문제 | 0.4 |
|-------|------|-----|
| `keys(pattern)` | 단일 스레드 Redis를 전체 키스페이스 스캔 동안 **블로킹** | `scanKeys(pattern, 1000, page -> ...)` — SCAN 커서, 페이지가 도착할 때마다 바로 삭제하므로 메모리는 페이지 크기로 제한 |
| `del(...)` 한 번에 전부 | 대량 삭제의 메모리 회수가 이벤트 루프에서 일어남 | `unlink(...)`, 500개씩 배치 |
| 전체 리셋 패턴 `fluxgate:*` | **룰셋 정의까지 삭제** | `fluxgate:bucket:*` — 정의 키와 교집합 없음 |

세 번째가 가장 위험했습니다. Redis에 룰셋을 저장한 배포에서 전체 리로드 한 번이 규칙 정의를 지웠습니다.
`fluxgate:bucket:` 접두사를 도입한 이유입니다.

```java
private static final long BUCKET_SCAN_COUNT = 1000L;   // SCAN COUNT 힌트
private static final int DELETE_BATCH_SIZE = 500;      // UNLINK 배치 크기
```

배치 크기의 의미: `UNLINK k1 k2 ... k100000`은 커맨드 하나가 거대해져 다시 이벤트 루프를 붙잡습니다.
500개씩 나누면 각 커맨드가 짧게 끝나고 다른 요청이 사이에 끼어들 수 있습니다.

`scanKeys`와 `unlink`는 모두 `RedisConnectionProvider`의 `default` 메서드이므로, 구형 서버나
커스텀 프로바이더에서는 `keys` / `del`로 자동 강등됩니다. Lettuce 실패는 스크립트와 같은 규칙으로
FluxGate 예외로 번역됩니다(`driverFailed`).

---

## 6. BucketState

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/store/
└── BucketState.java
```

토큰 소비 결과를 담는 불변 객체입니다.

```java
// BucketState.java - 실제 코드 (접근자는 한 줄로 줄이고 @return 태그는 생략)
/**
 * Represents the result of a token bucket consume operation.
 *
 * <p>The numbers describe the <em>binding</em> band of the call: the band that rejected the
 * request, or - when the request was allowed - the band left with the fewest tokens. {@link
 * #limit()} is that band's capacity and {@link #bandIndex()} its position in the band list that was
 * passed to {@link RedisTokenBucketStore#tryConsume(java.util.List, java.util.List, long)}, so the
 * caller can map the result back onto a {@code RateLimitBand} for HTTP headers. {@link
 * #redisTimeMicros()} is the Redis clock the decision was taken at, which a later refund of the
 * consumption needs. Unknown values are {@code -1}.
 */
public final class BucketState {

  /** Index reported when the binding band is unknown. */
  public static final int UNKNOWN_BAND_INDEX = -1;

  private final boolean consumed;
  private final long remainingTokens;
  private final long nanosToWaitForRefill;
  private final long resetTimeMillis;
  private final long limit;
  private final int bandIndex;
  private final long redisTimeMicros;

  public BucketState(
      boolean consumed,
      long remainingTokens,
      long nanosToWaitForRefill,
      long resetTimeMillis,
      long limit,
      int bandIndex,
      long redisTimeMicros) { ... }   // 4개·6개 인자 생성자는 나머지를 -1로 채움

  public static BucketState allowed(
      long remainingTokens, long resetTimeMillis, long limit, int bandIndex) {
    return new BucketState(true, remainingTokens, 0, resetTimeMillis, limit, bandIndex);
  }

  public static BucketState rejected(
      long remainingTokens, long nanosToWait, long resetTimeMillis, long limit, int bandIndex) {
    return new BucketState(false, remainingTokens, nanosToWait, resetTimeMillis, limit, bandIndex);
  }

  /** Whether the permits were successfully consumed. */
  public boolean consumed() { return consumed; }

  /** Number of tokens remaining in the bucket. */
  public long remainingTokens() { return remainingTokens; }

  /** Nanoseconds to wait until enough tokens are available. */
  public long nanosToWaitForRefill() { return nanosToWaitForRefill; }

  /**
   * Unix timestamp in milliseconds at which the binding band resets: TOKEN_BUCKET - full again;
   * SLIDING_WINDOW - everything counted now has left the window; FIXED_WINDOW - the window end.
   */
  public long resetTimeMillis() { return resetTimeMillis; }

  /** Capacity of the binding band, or {@code -1} if unknown. */
  public long limit() { return limit; }

  /**
   * Zero-based index of the binding band within the band list of the call, or {@link
   * #UNKNOWN_BAND_INDEX} if unknown.
   */
  public int bandIndex() { return bandIndex; }

  /**
   * Redis {@code TIME}, in microseconds since the epoch, at which the decision was taken, or {@code
   * -1} if unknown. It identifies the sliding sub-bucket and fixed window a consumption charged, so
   * {@link RedisTokenBucketStore#refund} can give back exactly that.
   */
  public long redisTimeMicros() { return redisTimeMicros; }
}
```

### BucketState 필드

| 필드 | 타입 | 설명 |
|-----|------|------|
| `consumed` | boolean | 토큰 소비 성공 여부 |
| `remainingTokens` | long | binding 대역에 남은 토큰 수 |
| `nanosToWaitForRefill` | long | 요청 1개를 처리할 만큼 리필되기까지 (나노초) |
| `resetTimeMillis` | long | binding 대역의 리셋 시각 (Unix ms). TOKEN_BUCKET은 **가득 찰** 시각, SLIDING_WINDOW는 세어진 요청이 모두 윈도를 떠나는 시각, FIXED_WINDOW는 윈도 끝 |
| `limit` | long | binding 대역의 **용량** (`-1`이면 알 수 없음) |
| `bandIndex` | int | 호출에 넘긴 대역 목록에서의 **0-based 인덱스** (`-1`이면 알 수 없음) |
| `redisTimeMicros` | long | 결정 시점의 Redis `TIME` (마이크로초). `refund()`가 차감한 서브 버킷·윈도를 찾는 데 사용 (`-1`이면 알 수 없음) |

### 0.4에서 추가된 limit과 bandIndex

0.3.x의 `BucketState`에는 `limit`과 `bandIndex`가 없었습니다. 그래서 다중 대역 규칙에서 **어느 대역이
결정을 만들었는지 알 수 없었고**, `X-RateLimit-Limit` 헤더에 쓸 용량도 없었습니다.

이제 호출자가 결과를 원래 `RateLimitBand`로 되돌릴 수 있습니다.

```java
// RedisRateLimiter.java - 실제 코드 (규칙별 호출 경로)
state = tokenBucketStore.tryConsume(call.bucketKeys, call.bands, permits);
...
RateLimitBand bindingBand = bandAt(call.bands, state.bandIndex());
```

그 대역의 라벨이 `RateLimitResult.bandLabel`을 거쳐 `RateLimitResponse.windowSeconds`로 이어지고,
최종적으로 `RateLimit-Policy: 100;w=60` 헤더가 됩니다.

### HTTP 헤더로 가는 경로

```
Lua 반환 8개 정수
   │  [3] min_remaining, [4] micros_to_wait, [5] reset_time_millis,
   │  [6] limit, [7] binding_band_index, [8] now_micros
   v
BucketState (nanosToWait = micros * 1000, bandIndex = lua index - 1)
   v
RateLimitResult  (+ matchedRule, policy, bandLabel)
   v
RateLimitResponse.from(result)  (+ windowSeconds = bandLabel로 찾은 대역의 윈도)
   v
RateLimitHeaderWriter
```

| 최종 필드 | legacy 헤더 | IETF 헤더 |
|----------|------------|----------|
| `limit` | `X-RateLimit-Limit` | `RateLimit-Limit` |
| `remainingTokens` | `X-RateLimit-Remaining` | `RateLimit-Remaining` |
| `resetTimeMillis` | `X-RateLimit-Reset` (epoch 초) | `RateLimit-Reset` (delta 초) |
| `limit` + `windowSeconds` | - | `RateLimit-Policy: <limit>;w=<window>` |
| `retryAfterMillis` | `Retry-After` (거부 시, 최소 1) | 같음 |

`-1`(알 수 없음)인 값은 헤더로 나가지 않습니다. `getRetryAfterSeconds()` 같은 헬퍼는
`BucketState`가 아니라 `RateLimitHeaderWriter.retryAfterSeconds(RateLimitResponse)`에 있습니다.
초 단위 변환은 HTTP 계층의 관심사이고, 저장소는 나노초까지의 사실만 전달합니다.

## MongoDB 규칙 저장소의 접근 제어 복사

접근 제어(`allowedIps`, `deniedIps`, `allowedKeys`, `deniedKeys`)는 규칙 세트 단위이지만 모든 규칙
문서에 저장됩니다. 도메인 `RateLimitRule`에는 들어가지 않으며 `findAccessControlByRuleSetId`로 읽습니다.
`save()`는 기존 문서의 값을 다시 쓰지 않고, 새 문서는 규칙 세트의 병합된 접근 제어를 복사하며, 다른
`ruleSetId`로 옮겨진 규칙은 새 규칙 세트의 목록을 가져옵니다(새 세트에 없으면 제거). 접근 제어를 쓰는
모든 경로(`saveAccessControl`, 새 규칙 삽입, `moveRule`)는 마커 필드 `aclUpdatedAt`도 함께 기록하며, 빈
목록은 필드를 제거합니다. 마커는 존재 여부만 의미가 있습니다. `saveAccessControl`과 `moveRule`은 서버
시각(`$currentDate`)을, 새 규칙 삽입은 클라이언트 시각을 기록합니다(`$setOnInsert`에서는 `$currentDate`를
쓸 수 없음).

읽을 때는 모든 사본을 병합합니다. 사본은 같은 규칙 세트에서 `aclUpdatedAt`이나 비어 있지 않은 목록을 가진
문서입니다. 거부 목록은 사본들의 합집합, 허용 목록은 교집합이며, 목록이 없는 사본은 빈 목록으로 칩니다.
마커도 목록도 없는 문서(0.3.x나 수동으로 쓴 문서)는 접근 제어가 없는 것으로 보고 무시합니다. 병합은
닫힌 쪽으로 실패합니다. 목록을 지우던 `saveAccessControl`이 중간에 끊기면 허용 목록은 비며, 회수한
목록이 되살아나지 않습니다.

**`saveAccessControl`과의 경합:** 새 문서(또는 옮겨진 문서)의 복사는 "읽기 후 별도 쓰기"입니다. 그 사이에
같은 규칙 세트에 `saveAccessControl(...)`이 실행되면 해당 문서 하나는 이전 목록을 유지하고, 병합 결과는
닫힌 쪽으로 실패합니다(허용 = 이전 ∩ 새 목록, 거부 = 이전 ∪ 새 목록, WARN 기록). 다음
`saveAccessControl(...)`이 모든 사본을 다시 씁니다. 규칙과 접근 제어를 동시에 편집한다면 규칙 저장 후
`saveAccessControl(...)`을 한 번 더 호출하세요.

---

## 관련 문서

- [Redis RateLimiter Module Deep Dive](redis-ratelimiter.ko.md) - 키 레이아웃, 연결 계층
- [RateLimiter Layer Deep Dive](ratelimiter-layer.ko.md) - 알고리즘 비교, Lua 단계별 분석
- [Hot Reload Deep Dive](hot-reload.ko.md) - 버킷 리셋을 촉발하는 리로드 경로
- [아키텍처 개요](../README.ko.md)
