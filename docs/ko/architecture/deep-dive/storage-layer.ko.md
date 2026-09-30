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
// RedisTokenBucketStore.java - 실제 코드
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
 *   <li>Token state is not modified on rejection; only the bucket TTLs are refreshed
 *   <li>Returns the binding band's capacity and reset time, for HTTP rate limit headers
 *   <li>Every bucket TTL is capped at {@link #DEFAULT_MAX_BUCKET_TTL} (or the configured {@code
 *       fluxgate.redis.max-bucket-ttl}), so a long window cannot keep forged identity keys resident
 *       in Redis for weeks
 * </ol>
 *
 * <p>Each store owns its own {@link LuaScriptRegistry}, so two stores pointing at different Redis
 * deployments in one JVM keep independent script SHAs.
 */
public class RedisTokenBucketStore {

  private static final String SCRIPT_NAME = "token_bucket_consume.lua";
  private static final long BUCKET_SCAN_COUNT = 1000L;
  private static final int DELETE_BATCH_SIZE = 500;
  private static final int RESULT_SIZE = 7;
  private static final long MICROS_PER_SECOND = 1_000_000L;
  private static final long NANOS_PER_MICRO = 1_000L;

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
// RedisTokenBucketStore.java - 실제 코드
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
 * @return BucketState describing the binding band: the one that rejected, or the one left with
 *     the fewest tokens
 * @throws InvalidRuleConfigException if {@code permits} exceeds the capacity of any band, which
 *     no amount of waiting could satisfy
 * @throws ScriptExecutionException if the Lua script fails or returns an unexpected result
 */
public BucketState tryConsume(List<String> bucketKeys, List<RateLimitBand> bands, long permits) {
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
  for (RateLimitBand band : bands) {
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
  }

  // KEYS[1..n] = bucketKeys
  // ARGV[1] = permits, then capacity / window_micros / reserved per band, and the
  // bucket TTL cap last so the per-band triplets keep their indices.
  String[] keys = bucketKeys.toArray(new String[0]);
  String[] args = new String[2 + 3 * bands.size()];
  args[0] = String.valueOf(permits);
  for (int i = 0; i < bands.size(); i++) {
    RateLimitBand band = bands.get(i);
    args[1 + 3 * i] = String.valueOf(band.getCapacity());
    args[2 + 3 * i] = String.valueOf(toMicros(band));
    args[3 + 3 * i] = "0";
  }
  args[args.length - 1] = String.valueOf(maxBucketTtlSeconds);

  List<Long> result = executeScriptWithFallback(keys, args);

  // [allowed, rejecting_band_index, min_remaining, micros_to_wait,
  //  reset_time_millis, limit, binding_band_index]
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

  if (allowed) {
    return BucketState.allowed(remainingTokens, resetTimeMillis, limit, bandIndex);
  }

  return BucketState.rejected(remainingTokens, nanosToWait, resetTimeMillis, limit, bandIndex);
}

private static long toMicros(RateLimitBand band) {
  return Math.addExact(
      Math.multiplyExact(band.getWindow().getSeconds(), MICROS_PER_SECOND),
      band.getWindow().getNano() / 1_000L);
}
```

### ARGV 레이아웃

```
ARGV[1]           permits
ARGV[2 + 3i]      대역 i+1의 capacity
ARGV[3 + 3i]      대역 i+1의 window_micros
ARGV[4 + 3i]      예약 (현재 "0")
ARGV[#ARGV]       max_bucket_ttl_seconds   ← 마지막 인자
```

배열 크기는 `2 + 3 * bands.size()`입니다. permits 1개 + 대역마다 3개 + TTL 상한 1개.

TTL 상한이 **맨 뒤**에 오는 것은 우연이 아닙니다.

```java
// ARGV[1] = permits, then capacity / window_micros / reserved per band, and the
// bucket TTL cap last so the per-band triplets keep their indices.
```

앞이나 중간에 끼워 넣으면 대역 3개 묶음(triplet)의 인덱스 계산식이 전부 바뀝니다. 뒤에 두면
`2 + 3 * (i - 1)` 같은 기존 산식이 그대로 유지되고, 스크립트는 `ARGV[#ARGV]`로 읽습니다.

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
  if (permits > band.getCapacity()) {
    throw new InvalidRuleConfigException(...);
  }
}
```

용량 10인 대역에 permits 20을 요청하면 **얼마를 기다려도** 통과할 수 없습니다. 이때 대기 시간을
돌려주면 클라이언트가 무한 재시도 루프에 빠집니다. 설정 오류는 설정 오류로 보고하는 것이 맞습니다.

스크립트 쪽에도 같은 검사가 있어(`'permits exceed capacity'`) 스토어를 우회한 호출도 막습니다.

### 핵심 설계 원칙

| 원칙 | 설명 |
|-----|------|
| Redis TIME 사용 | `System.nanoTime()` 대신 Redis 서버 시간 (Clock Drift 방지) |
| 마이크로초 시간 기반 | Lua 5.1 double의 정확한 정수 범위(2^53) 안에 머무름 |
| 한 규칙 = 한 번의 호출 | 모든 대역을 2패스로 처리, 전부 차감 또는 전무 |
| 거부 시 토큰 미변경 | 버킷의 TTL만 갱신 |
| binding 대역 보고 | 용량·리셋 시각을 HTTP 헤더에 쓸 수 있게 반환 |
| 버킷 TTL 상한 | `fluxgate.redis.max-bucket-ttl` (기본 7일) |

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
 *   <li>Reload the script into Redis cache for future calls
 * </ol>
 *
 * <p>Errors raised by the script itself (Lua {@code redis.error_reply}) arrive as a Lettuce
 * {@link RedisCommandExecutionException} and are translated into {@link ScriptExecutionException}
 * so that callers see a FluxGate exception rather than a driver one.
 */
private List<Long> executeScriptWithFallback(String[] keys, String[] args) {
  String sha = scripts.getTokenBucketConsumeSha();
  String script = scripts.getTokenBucketConsumeScript();

  try {
    // Try EVALSHA first (efficient, uses cached script)
    return connectionProvider.evalsha(sha, keys, args);
  } catch (RedisNoScriptException e) {
    // Script not in Redis cache (e.g. Redis was restarted)
    log.warn(
        "Lua script not found in Redis cache (NOSCRIPT). "
            + "Falling back to EVAL and reloading script. SHA: {}",
        sha);

    // Fallback 1 - Execute using EVAL (slower but works immediately)
    List<Long> result;
    try {
      result = connectionProvider.eval(script, keys, args);
    } catch (RedisCommandExecutionException scriptError) {
      throw scriptFailed(scriptError);
    }

    // Fallback 2 - Reload script for future calls (thread-safe with AtomicBoolean)
    reloadScript();

    return result;
  } catch (RedisCommandExecutionException e) {
    throw scriptFailed(e);
  }
}

private ScriptExecutionException scriptFailed(RedisCommandExecutionException e) {
  return new ScriptExecutionException(
      "Lua script execution failed: " + e.getMessage(), SCRIPT_NAME, e);
}

/**
 * Reloads the Lua script into Redis cache.
 *
 * <p>This is called after a NOSCRIPT error to restore the script cache. Uses AtomicBoolean to
 * prevent multiple concurrent reload attempts.
 */
private void reloadScript() {
  if (!reloadingLuaScript.compareAndSet(false, true)) {
    log.debug("Lua script is already being reloaded, skipping...");
    return;
  }

  try {
    scripts.loadInto(connectionProvider);
  } catch (RuntimeException e) {
    log.error("Failed to reload Lua script: {}", e.getMessage(), e);
  } finally {
    reloadingLuaScript.set(false);
  }
}
```

### 0.4에서 달라진 두 가지

**(1) 드라이버 예외를 FluxGate 예외로 감쌈.** 스크립트가 `redis.error_reply("permits exceed capacity")`를
반환하면 Lettuce는 `RedisCommandExecutionException`을 던집니다. 그대로 올려보내면 호출자가 Lettuce
타입에 의존하게 됩니다. `catch` 절이 **두 군데**(EVAL 폴백 안, 그리고 바깥)인 것도 두 경로 모두
같은 번역을 거치게 하기 위함입니다.

**(2) `scripts`는 인스턴스 필드.** 0.3.x는 `LuaScripts.getTokenBucketConsumeSha()`라는 static 호출로
SHA를 읽었습니다. 한 JVM에서 서로 다른 Redis를 쓰는 스토어가 둘 있으면 서로의 SHA를 덮어써
NOSCRIPT가 영구화될 수 있었습니다. 이제는 `this.scripts`입니다.

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
| 리로드 완료 후 | finally에서 false로 복원 | 다음 NOSCRIPT 시 리로드 가능 |

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

  private final String tokenBucketConsumeScript;

  private volatile String tokenBucketConsumeSha;

  /**
   * Reads every FluxGate Lua script from the classpath.
   *
   * @throws ScriptExecutionException if a script resource is missing or cannot be read
   */
  public LuaScriptRegistry() {
    this.tokenBucketConsumeScript = readScript(TOKEN_BUCKET_SCRIPT_PATH);
  }

  /**
   * Uploads every script to the given Redis and remembers the SHAs it returns.
   *
   * <p>In cluster mode Lettuce broadcasts {@code SCRIPT LOAD} to all master nodes.
   *
   * @return the SHA1 hash of the token bucket consume script
   */
  public String loadInto(RedisConnectionProvider connectionProvider) {
    Objects.requireNonNull(connectionProvider, "connectionProvider must not be null");

    String sha = connectionProvider.scriptLoad(tokenBucketConsumeScript);
    this.tokenBucketConsumeSha = sha;

    log.info(
        "Loaded token_bucket_consume.lua into Redis ({} mode) with SHA: {}",
        connectionProvider.getMode(),
        sha);
    return sha;
  }

  /** Checks whether the scripts have a known SHA. */
  public boolean isLoaded() {
    return tokenBucketConsumeSha != null;
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
private volatile String tokenBucketConsumeSha;   // loadInto()가 채움
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
  String sha = registry.loadInto(connectionProvider);

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
└── token_bucket_consume.lua
```

### 헤더: 계약

```lua
--[[
Multi-Band Token Bucket Rate Limiter

DESIGN NOTES:
1. Uses Redis TIME (not System.nanoTime()) - solves clock drift across nodes
2. Microsecond time base kept inside double precision - see below
3. All bands of one rule are evaluated in two passes: check every band first,
   then either consume from every band or from none. A rejected request never
   drains a band that would have allowed it.
4. State is never modified on rejection, apart from refreshing the TTL of the
   bands that already exist (see TTL below)
5. TTL = min(max_bucket_ttl, max(1, ceil(window_seconds * 1.1))). The cap comes
   from the caller (fluxgate.redis.max-bucket-ttl, 7 days by default): without
   one, every forged identity key leaves a Redis hash resident for the whole
   window, so a 30 day rule keeps 33 days of attacker-controlled keys alive

PRECISION: Redis runs Lua 5.1, which has no integer type - every number is an
IEEE-754 double with an exact integer range of 2^53 (about 9.0e15). Microseconds
since the epoch are about 1.76e15, so both the timestamps we store and the
timestamps we read back are exactly representable; nanoseconds (about 1.76e18)
were not, and `redis.call` serialised them as "1.76e+18" through Lua's default
%.14g. Everything written to a hash therefore goes through
string.format('%.0f', v). The intermediate product `elapsed * capacity` (and
the analogous `deficit * window_micros`) can exceed 2^53 if both operands are
large: keep capacity × window_micros ≤ 2^53 ≈ 9.0e15 to avoid precision loss
(e.g. capacity=10^6 and window ≤ ~104 days is fine; capacity=10^9 limits the
window to ~2.5 hours).

KEYS[1..n] = bucket keys, one per band of ONE rule, all in the same hash tag
  (e.g. "fluxgate:bucket:{api-limits:per-ip:ip:192.168.1.100}:100-per-60s")

ARGV[1]       = permits (number of tokens to consume, usually 1)
ARGV[2 + 3i]  = capacity of band i+1 (max tokens)
ARGV[3 + 3i]  = window_micros of band i+1 (window duration in microseconds)
ARGV[4 + 3i]  = reserved for future use, pass 0
ARGV[#ARGV]   = max_bucket_ttl_seconds, the upper bound on every bucket TTL
                (last argument, so the per-band triplets keep their indices)

Hash fields per bucket: 'tokens', 'last_refill_micros'. A bucket written by
FluxGate 0.3.x carries 'last_refill_nanos' instead; that field is ignored, so
such a bucket is re-initialised once (a one-off quota reset on upgrade).

Returns array of 7 integers:
  [1] allowed                 1 if all bands allowed, 0 otherwise
  [2] rejecting_band_index    1-based index of the first band that rejected, 0 when allowed
  [3] min_remaining           binding band's tokens after consumption (allow) /
                              rejecting band's tokens (reject)
  [4] micros_to_wait          microseconds until the rejecting band can serve the
                              request, 0 when allowed
  [5] reset_time_millis       epoch millis at which the binding band's bucket is
                              full again (computed AFTER consumption on allow)
  [6] limit                   capacity of the binding band
  [7] binding_band_index      1-based index of the binding band (equals
                              rejecting_band_index on reject)

Errors: 'permits exceed capacity' when a band can never serve the request.
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
    'tokens', string.format('%.0f', remaining),
    'last_refill_micros', string.format('%.0f', refill_micros[i])
)
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
-- Missing bucket (or a 0.3.x bucket, whose 'last_refill_micros' is absent):
-- start full, which allows the initial burst.
if current_tokens == nil or last_refill_micros == nil then
    current_tokens = capacity
    last_refill_micros = now_micros
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
if #ARGV ~= 2 + 3 * band_count then
    return redis.error_reply("expected " .. (2 + 3 * band_count) .. " arguments for " .. band_count .. " band(s)")
end

local permits = tonumber(ARGV[1])
if permits == nil or permits <= 0 then
    return redis.error_reply("permits must be positive")
end

local capacities = {}
local windows = {}
for i = 1, band_count do
    local capacity = tonumber(ARGV[2 + 3 * (i - 1)])
    local window_micros = tonumber(ARGV[3 + 3 * (i - 1)])

    if capacity == nil or capacity <= 0 then
        return redis.error_reply("capacity must be positive")
    end
    if window_micros == nil or window_micros <= 0 then
        return redis.error_reply("window must be positive")
    end
    if permits > capacity then
        return redis.error_reply("permits exceed capacity")
    end

    capacities[i] = capacity
    windows[i] = window_micros
end
```

인자 개수를 `2 + 3 * band_count`로 검증하는 것이 첫 방어선입니다. 개수가 맞지 않으면 인덱스가
어긋나 엉뚱한 값을 capacity로 읽게 되므로, 조용히 잘못 동작하기 전에 거부합니다.

### Redis TIME과 TTL

```lua
local time_info = redis.call('TIME')
-- time_info[1] = seconds since epoch, time_info[2] = microseconds within the second
local now_micros = tonumber(time_info[1]) * 1000000 + tonumber(time_info[2])

-- TTL in whole seconds: the window plus a 10% margin for clock skew, never below 1s
-- and never above the cap the caller passed. An uncapped TTL meets a forgeable
-- identity scope badly: each forged key then occupies memory for a whole window.
local max_ttl_seconds = tonumber(ARGV[#ARGV])
if max_ttl_seconds == nil or max_ttl_seconds < 1 then
    return redis.error_reply("max bucket ttl must be >= 1 second")
end

local function ttl_seconds(window_micros)
    return math.min(max_ttl_seconds, math.max(1, math.ceil(window_micros / 1000000 * 1.1)))
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

상한은 **하드코딩 24시간이 아니고, 없는 것도 아닙니다.** 호출자가 `ARGV[#ARGV]`로 넘기며 기본값은
`fluxgate.redis.max-bucket-ttl`의 7일입니다. 스크립트가 1초 미만 상한을 거부하는 이유는, 0이나 음수가
들어오면 모든 버킷이 즉시 만료되어 Rate Limiting이 사실상 사라지기 때문입니다.

상한이 윈도보다 짧으면 규칙이 실제로 강제하는 한도가 달라집니다. `RedisRateLimiter`가 그 상황을
규칙마다 한 번 WARN으로 알립니다.

### Pass 1: 리필하고 전부 가능한지 확인

```lua
local tokens = {}
local refill_micros = {}

for i = 1, band_count do
    local capacity = capacities[i]
    local window_micros = windows[i]

    local bucket_data = redis.call('HMGET', KEYS[i], 'tokens', 'last_refill_micros')
    local current_tokens = tonumber(bucket_data[1])
    local last_refill_micros = tonumber(bucket_data[2])

    -- Missing bucket (or a 0.3.x bucket, whose 'last_refill_micros' is absent):
    -- start full, which allows the initial burst.
    if current_tokens == nil or last_refill_micros == nil then
        current_tokens = capacity
        last_refill_micros = now_micros
    end

    -- math.max handles a clock that moved backwards (e.g. after a Redis restart);
    -- math.min caps the elapsed time at one window, beyond which the bucket is full
    -- anyway, and bounds the elapsed * capacity product (see the PRECISION note in
    -- the header for the limits).
    local elapsed_micros = math.min(math.max(0, now_micros - last_refill_micros), window_micros)

    -- Only whole tokens are credited, and the timestamp advances only by the time
    -- those tokens cost, so the sub-token remainder is carried into the next call
    -- instead of being dropped (which would systematically under-allow).
    local tokens_to_add = math.floor(elapsed_micros * capacity / window_micros)
    local next_refill_micros = last_refill_micros
    if tokens_to_add > 0 then
        next_refill_micros = last_refill_micros + math.floor(tokens_to_add * window_micros / capacity)
    end

    local refilled = math.min(capacity, current_tokens + tokens_to_add)
    if refilled >= capacity then
        -- Bucket is full: there is no deficit left to carry.
        next_refill_micros = now_micros
    end

    tokens[i] = refilled
    refill_micros[i] = next_refill_micros

    if refilled < permits then
        -- ================================================================
        -- REJECTED: no band is written, so the bands that would have allowed
        -- the request keep their tokens. Only the TTLs are refreshed, so a
        -- bucket that sees nothing but rejections still expires on schedule
        -- instead of living on with the TTL of its last allowed request.
        -- EXPIRE is a no-op on a bucket that does not exist yet.
        -- ================================================================
        for j = 1, band_count do
            redis.call('EXPIRE', KEYS[j], ttl_seconds(windows[j]))
        end

        local tokens_needed = permits - refilled
        local micros_to_wait = math.ceil(tokens_needed * window_micros / capacity)

        -- reset_time_millis = epoch millis when the bucket is FULL again, matching the allow-path
        -- semantics. micros_to_wait is only the retry delay; the bucket is full only after the
        -- entire deficit (capacity - refilled) has been refilled.
        local deficit = capacity - refilled
        local micros_until_full = 0
        if deficit > 0 then
            micros_until_full = math.ceil(deficit * window_micros / capacity)
        end
        local reset_time_millis = math.floor((now_micros + micros_until_full) / 1000)

        return {0, i, refilled, micros_to_wait, reset_time_millis, capacity, i}
    end
end
```

#### 잔여분 이월: 0.4의 정확성 수정

이 부분이 미묘하지만 중요합니다.

```lua
local tokens_to_add = math.floor(elapsed_micros * capacity / window_micros)
local next_refill_micros = last_refill_micros
if tokens_to_add > 0 then
    next_refill_micros = last_refill_micros + math.floor(tokens_to_add * window_micros / capacity)
end
```

0.3.x는 성공 시 `last_refill`을 **현재 시각으로** 갱신했습니다. `math.floor`로 버려진 1토큰 미만의
잔여 시간이 매 호출마다 사라집니다.

```
용량 100, 윈도 60초 (토큰 1개 = 600ms)

0.3.x: 매 500ms마다 요청
  → tokens_to_add = floor(500ms / 600ms) = 0
  → last_refill = now  ← 500ms가 버려짐
  → 영원히 리필되지 않습니다

0.4: 매 500ms마다 요청
  → tokens_to_add = 0 → next_refill_micros = last_refill_micros (그대로)
  → 다음 호출에서 1000ms 경과 → 1토큰 리필, 타임스탬프는 600ms만 전진
  → 남은 400ms는 다음 호출로 이월
```

즉 0.3.x는 **상시 과소 허용**이었습니다. 설정한 한도보다 적게 통과시켰고, 요청 간격이 토큰 비용보다
짧을수록 심해졌습니다. 타임스탬프를 "그 토큰들이 소요한 시간만큼만" 전진시키는 것이 수정입니다.

버킷이 가득 차면 이월할 부족분이 없으므로 `now_micros`로 맞춥니다.

#### 거부 시 TTL만 갱신

거부 경로가 아무 상태도 안 쓰는 것이 아니라 **TTL은 갱신합니다.** 주석이 이유를 설명합니다.

> a bucket that sees nothing but rejections still expires on schedule instead of living on with the
> TTL of its last allowed request.

거부만 계속 받는 버킷의 TTL을 갱신하지 않으면, 마지막으로 허용된 요청 시점의 TTL로 만료됩니다.
공격 트래픽이 계속 들어오는 버킷이 만료 직전 상태로 방치되는 셈입니다.

`EXPIRE`는 존재하지 않는 키에 no-op이므로, 아직 만들어지지 않은 버킷에 대해서도 안전합니다.

#### 거부 시에도 모든 대역의 TTL을 갱신

```lua
for j = 1, band_count do
    redis.call('EXPIRE', KEYS[j], ttl_seconds(windows[j]))
end
```

거부한 대역 `i`뿐 아니라 **`1..band_count` 전부**를 돕니다. 거부 때문에 평가가 멈췄어도 앞선 대역들의
버킷은 실재하고, 그들의 TTL도 함께 연장되어야 대역 간 만료 시점이 어긋나지 않습니다.

#### micros_to_wait vs reset_time_millis

거부 경로가 두 개의 시간을 계산합니다. 같은 것이 아닙니다.

| 값 | 의미 | 계산 |
|---|------|------|
| `micros_to_wait` | **요청 1개**를 처리할 만큼 리필될 때까지 | `ceil(tokens_needed * window / capacity)` |
| `reset_time_millis` | 버킷이 **가득 찰** 때까지 | `ceil(deficit * window / capacity)` + 현재 |

```
용량 100, 윈도 60초, 현재 토큰 0, permits 1

micros_to_wait     = ceil(1 * 60,000,000 / 100)   = 600,000 μs  = 0.6초
micros_until_full  = ceil(100 * 60,000,000 / 100) = 60,000,000 μs = 60초
```

전자가 `Retry-After`가 되고, 후자가 `RateLimit-Reset`이 됩니다. 두 헤더가 다른 질문에 답하는 이유이며,
허용 경로와 의미를 일치시킨 것이 0.4입니다.

### Pass 2: 전부 가능하므로 모두 차감

```lua
local binding = 1

for i = 1, band_count do
    local remaining = tokens[i] - permits

    redis.call('HMSET', KEYS[i],
        'tokens', string.format('%.0f', remaining),
        'last_refill_micros', string.format('%.0f', refill_micros[i])
    )
    redis.call('EXPIRE', KEYS[i], ttl_seconds(windows[i]))

    tokens[i] = remaining
    if remaining < tokens[binding] then
        binding = i
    end
end

-- Reset time is computed AFTER consumption, so the caller is told when the bucket
-- is really full again rather than when it would have been without this request.
local binding_capacity = capacities[binding]
local deficit = binding_capacity - tokens[binding]
local micros_until_full = 0
if deficit > 0 then
    micros_until_full = math.ceil(deficit * windows[binding] / binding_capacity)
end
local reset_time_millis = math.floor((now_micros + micros_until_full) / 1000)

return {1, 0, tokens[binding], 0, reset_time_millis, binding_capacity, binding}
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
  Pass 1: 초당 대역 확인(OK) → 분당 대역 확인(부족) → 즉시 반환, 아무것도 쓰지 않음
  → 초당 대역의 토큰이 그대로 남습니다
```

`binding`은 차감 **후** 토큰이 가장 적게 남은 대역이고, 리셋 시각도 차감 후에 계산합니다.
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
         |  - 한 규칙의 모든 대역을 2패스로                 |
         |  - 전부 차감 또는 전무                          |
         |  - Race Condition 없음                         |
         |  - 규칙당 한 번의 네트워크 왕복                  |
         +------------------------------------------------+
```

한계도 분명합니다. **원자성은 한 규칙 안에서만** 성립합니다. 규칙마다 키가 다르고, 클러스터에서
다른 키는 다른 슬롯에 살기 때문입니다. 자세한 내용은
[Redis RateLimiter 모듈](redis-ratelimiter.ko.md#규칙-간에는-여전히-원자적이지-않습니다)에 있습니다.

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
 * <p>Uses SCAN semantics to avoid blocking Redis on production-sized keyspaces, and UNLINK where
 * the server supports it so that freeing the keys happens off the event loop.
 *
 * @return the number of buckets deleted
 */
public long deleteBucketsByRuleSetId(String ruleSetId) {
  Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");

  String pattern = RedisRateLimiter.bucketKeyPattern(ruleSetId);
  log.debug("Deleting token buckets matching pattern: {}", pattern);

  long deleted = deleteInBatches(connectionProvider.scanKeys(pattern, BUCKET_SCAN_COUNT));
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
 * <p>Uses SCAN semantics to avoid blocking Redis on production-sized keyspaces.
 */
public long deleteAllBuckets() {
  String pattern = RedisRateLimiter.BUCKET_KEY_PREFIX + "*";
  log.debug("Deleting all token buckets matching pattern: {}", pattern);

  long deleted = deleteInBatches(connectionProvider.scanKeys(pattern, BUCKET_SCAN_COUNT));
  if (deleted == 0) {
    log.debug("No token buckets found");
    return 0;
  }

  log.info("Deleted {} token buckets (full reset)", deleted);
  return deleted;
}

private long deleteInBatches(List<String> keys) {
  if (keys == null || keys.isEmpty()) {
    return 0;
  }

  List<String> all = new ArrayList<>(keys);
  long deleted = 0;
  for (int start = 0; start < all.size(); start += DELETE_BATCH_SIZE) {
    int end = Math.min(start + DELETE_BATCH_SIZE, all.size());
    List<String> batch = all.subList(start, end);
    deleted += connectionProvider.unlink(batch.toArray(new String[0]));
  }
  return deleted;
}
```

### 0.4에서 고쳐진 세 가지

| 0.3.x | 문제 | 0.4 |
|-------|------|-----|
| `keys(pattern)` | 단일 스레드 Redis를 전체 키스페이스 스캔 동안 **블로킹** | `scanKeys(pattern, 1000)` — SCAN 커서, 배치 1000 |
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
커스텀 프로바이더에서는 `keys` / `del`로 자동 강등됩니다.

---

## 6. BucketState

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/store/
└── BucketState.java
```

토큰 소비 결과를 담는 불변 객체입니다.

```java
// BucketState.java - 실제 코드
/**
 * Represents the result of a token bucket consume operation.
 *
 * <p>The numbers describe the <em>binding</em> band of the call: the band that rejected the
 * request, or - when the request was allowed - the band left with the fewest tokens. {@link
 * #limit()} is that band's capacity and {@link #bandIndex()} its position in the band list that was
 * passed to {@link RedisTokenBucketStore#tryConsume(java.util.List, java.util.List, long)}, so the
 * caller can map the result back onto a {@code RateLimitBand} for HTTP headers. Unknown values are
 * {@code -1}.
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

  /** Unix timestamp in milliseconds when bucket will be full again. */
  public long resetTimeMillis() { return resetTimeMillis; }

  /** Capacity of the binding band, or {@code -1} if unknown. */
  public long limit() { return limit; }

  /**
   * Zero-based index of the binding band within the band list of the call, or {@link
   * #UNKNOWN_BAND_INDEX} if unknown.
   */
  public int bandIndex() { return bandIndex; }
}
```

### BucketState 필드

| 필드 | 타입 | 설명 |
|-----|------|------|
| `consumed` | boolean | 토큰 소비 성공 여부 |
| `remainingTokens` | long | binding 대역에 남은 토큰 수 |
| `nanosToWaitForRefill` | long | 요청 1개를 처리할 만큼 리필되기까지 (나노초) |
| `resetTimeMillis` | long | binding 대역 버킷이 **가득 찰** 시각 (Unix ms) |
| `limit` | long | binding 대역의 **용량** (`-1`이면 알 수 없음) |
| `bandIndex` | int | 호출에 넘긴 대역 목록에서의 **0-based 인덱스** (`-1`이면 알 수 없음) |

### 0.4에서 추가된 limit과 bandIndex

0.3.x의 `BucketState`에는 `limit`과 `bandIndex`가 없었습니다. 그래서 다중 대역 규칙에서 **어느 대역이
결정을 만들었는지 알 수 없었고**, `X-RateLimit-Limit` 헤더에 쓸 용량도 없었습니다.

이제 호출자가 결과를 원래 `RateLimitBand`로 되돌릴 수 있습니다.

```java
// RedisRateLimiter.java - 실제 코드
BucketState state = tokenBucketStore.tryConsume(bucketKeys, bands, permits);
RateLimitBand bindingBand = bandAt(bands, state.bandIndex());
```

그 대역의 라벨이 `RateLimitResult.bandLabel`을 거쳐 `RateLimitResponse.windowSeconds`로 이어지고,
최종적으로 `RateLimit-Policy: 100;w=60` 헤더가 됩니다.

### HTTP 헤더로 가는 경로

```
Lua 반환 7개 정수
   │  [3] min_remaining, [4] micros_to_wait, [5] reset_time_millis,
   │  [6] limit, [7] binding_band_index
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

---

## 관련 문서

- [Redis RateLimiter Module Deep Dive](redis-ratelimiter.ko.md) - 키 레이아웃, 연결 계층
- [RateLimiter Layer Deep Dive](ratelimiter-layer.ko.md) - 알고리즘 비교, Lua 단계별 분석
- [Hot Reload Deep Dive](hot-reload.ko.md) - 버킷 리셋을 촉발하는 리로드 경로
- [아키텍처 개요](../README.ko.md)
