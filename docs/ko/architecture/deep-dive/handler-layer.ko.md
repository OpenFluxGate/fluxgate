# Handler Layer Deep Dive

> 이 문서는 FluxGate **0.4**의 코드를 기준으로 합니다. 0.3.x에서 무엇이 바뀌었는지는
> [0.4 마이그레이션](../../operations/migration-0.4.ko.md)에, 전체 계층을 한 문서로 훑는 서술은
> [아키텍처 Deep Dive](../../../ARCHITECTURE_DEEP_DIVE.ko.md)에 있습니다.

이 문서는 FluxGate의 Handler Layer를 **실제 소스코드**와 함께 상세히 설명합니다.

[< 아키텍처 개요로 돌아가기](../README.ko.md)

---

## 목차

1. [FluxgateRateLimitHandler 인터페이스](#1-fluxgateratelimithandler-인터페이스)
2. [RateLimitResponse](#2-ratelimitresponse)
3. [EngineBackedRateLimitHandler (라이브러리 기본 구현)](#3-enginebackedratelimithandler-라이브러리-기본-구현)
4. [MissingRuleSetProviderRateLimitHandler (설정 누락 감지용)](#4-missingrulesetproviderratelimithandler-설정-누락-감지용)
5. [자체 핸들러: HTTP API 모드 (샘플)](#5-자체-핸들러-http-api-모드-샘플)
6. [Handler 선택 가이드](#6-handler-선택-가이드)

---

## 1. FluxgateRateLimitHandler 인터페이스

```
fluxgate-core/src/main/java/org/fluxgate/core/handler/
└── FluxgateRateLimitHandler.java
```

Rate Limit 처리를 위한 핵심 인터페이스입니다. 필터와 AOP 애스펙트는 모두 이 인터페이스만 보고,
그 뒤에 Redis가 있는지 원격 API가 있는지는 신경 쓰지 않습니다.

```java
// FluxgateRateLimitHandler.java - 실제 코드
public interface FluxgateRateLimitHandler {

  /**
   * Attempts to consume a token from the rate limit bucket.
   *
   * @param context Request context containing client info (IP, userId, endpoint, etc.)
   * @param ruleSetId The rule set ID to apply
   * @return Rate limit response containing allowed status and metadata
   */
  RateLimitResponse tryConsume(RequestContext context, String ruleSetId);

  /**
   * Attempts to consume the given number of permits from the rate limit bucket.
   *
   * <p>The default implementation delegates to {@link #tryConsume(RequestContext, String)} when a
   * single permit is requested and rejects anything heavier. Library handlers backed by a store
   * that supports weighted consumption override this form and implement the 2-arg method in terms
   * of it.
   *
   * @throws UnsupportedOperationException if more than one permit is requested and this handler
   *     does not support weighted permits
   */
  default RateLimitResponse tryConsume(RequestContext context, String ruleSetId, long permits) {
    if (permits == 1) {
      return tryConsume(context, ruleSetId);
    }
    throw new UnsupportedOperationException(
        "Weighted permits are not supported by " + getClass().getName());
  }

  /**
   * Default handler that always allows requests. Used as fallback when no handler is configured.
   */
  FluxgateRateLimitHandler ALLOW_ALL = (context, ruleSetId) -> RateLimitResponse.allowed(-1, 0);
}
```

### 가중 permits (3-arg 오버로드)

`permits`가 1보다 큰 요청은 3-arg 형태로만 전달됩니다. 기본 구현이 `UnsupportedOperationException`을
던지는 것은 **의도된 설계**입니다. 가중 소비를 지원하지 않는 핸들러가 `permits=10`을 조용히 1로
취급하면, 비싼 API가 값싼 API와 같은 비용으로 통과합니다. 필터는 이 계약을 존중해 permits가 1일 때만
2-arg 형태를 호출합니다.

```java
// FluxgateRateLimitFilter.java - 실제 코드
private RateLimitResponse tryConsume(RequestContext context, long permits) {
  // The 3-arg form is optional for handlers, so only use it for a weighted request.
  return permits == SINGLE_PERMIT
      ? handler.tryConsume(context, ruleSetId)
      : handler.tryConsume(context, ruleSetId, permits);
}
```

permits는 `fluxgate.ratelimit.cost-header`로 설정한 요청 헤더에서 읽습니다. 값이 없거나 파싱에
실패하면 1이고, `fluxgate.ratelimit.max-cost`로 상한이 걸립니다.

### 구현 전략

| 구현 | 위치 | 설명 | 사용 시나리오 |
|-----|------|------|-------------|
| `EngineBackedRateLimitHandler` | 스타터 (라이브러리 기본) | `RateLimitEngine`에 위임 | 기본값. `RateLimiter` + `RateLimitRuleSetProvider` 빈이 있으면 자동 등록 |
| `MissingRuleSetProviderRateLimitHandler` | 스타터 | 룰 소스가 없다는 사실을 한 번 로그로 알리고 `failure-behavior` 적용 | 설정 누락 감지 |
| `HttpRateLimitHandler` | 샘플 모듈 | 외부 FluxGate API 서버 호출 | 중앙 집중식 Rate Limiting |
| `ALLOW_ALL` | 인터페이스 상수 | 항상 허용 | 테스트, 핸들러 미설정 폴백 |

> **0.4 변경점.** 0.3.x 문서는 `RedisRateLimitHandler`를 직접 Redis 모드의 기본 구현으로 소개했지만,
> 그런 클래스는 존재하지 않습니다. 라이브러리 기본 구현은 `EngineBackedRateLimitHandler`이고,
> Redis 접근은 그 뒤의 `RateLimitEngine` → `RateLimiter` → `RedisTokenBucketStore` 경로가 담당합니다.

---

## 2. RateLimitResponse

```
fluxgate-core/src/main/java/org/fluxgate/core/handler/
└── RateLimitResponse.java
```

Rate Limit 체크 결과를 담는 불변 객체입니다. HTTP 헤더를 만들기에 충분한 정보를 모두 들고 있습니다.

```java
// RateLimitResponse.java - 실제 코드 (필드와 팩토리)
public final class RateLimitResponse {

  private final boolean allowed;
  private final long remainingTokens;
  private final long retryAfterMillis;
  private final OnLimitExceedPolicy onLimitExceedPolicy;
  private final long limit;
  private final long resetTimeMillis;
  private final long windowSeconds;
  private final String bandLabel;

  /** 허용 응답 (limit/reset/window/band는 모두 -1 또는 null = 알 수 없음) */
  public static RateLimitResponse allowed(long remainingTokens, long retryAfterMillis) { ... }

  /** 거부 응답 (기본 REJECT_REQUEST 정책) */
  public static RateLimitResponse rejected(long retryAfterMillis) { ... }

  /** 거부 응답 (정책 지정) */
  public static RateLimitResponse rejected(long retryAfterMillis, OnLimitExceedPolicy policy) { ... }

  /**
   * Converts a {@link RateLimitResult} into a response.
   *
   * <p>The wait time is converted from nanoseconds to milliseconds <b>rounding up</b>, so a sub-
   * millisecond wait never becomes {@code Retry-After: 0} and sends the client into a busy loop.
   * {@code 0} stays {@code 0} and {@code -1} (unknown) stays {@code -1}. Policy, limit, reset time
   * and the real remaining tokens are carried over unchanged.
   *
   * <p>The band window is derived by matching {@link RateLimitResult#getBandLabel()} against the
   * bands of the matched rule, so an HTTP layer can emit {@code RateLimit-Policy:
   * <limit>;w=<window>} without consulting the rule itself. It is {@code -1} when the band cannot
   * be identified.
   */
  public static RateLimitResponse from(RateLimitResult result) {
    Objects.requireNonNull(result, "result must not be null");
    return new RateLimitResponse(
        result.isAllowed(),
        result.getRemainingTokens(),
        nanosToMillisCeil(result.getNanosToWaitForRefill()),
        result.getPolicy(),
        result.getLimit(),
        result.getResetTimeMillis(),
        resolveWindowSeconds(result),
        result.getBandLabel());
  }

  public boolean isAllowed() { return allowed; }
  public long getRemainingTokens() { return remainingTokens; }
  public long getRetryAfterMillis() { return retryAfterMillis; }
  public OnLimitExceedPolicy getOnLimitExceedPolicy() { return onLimitExceedPolicy; }
  public long getLimit() { return limit; }
  public long getResetTimeMillis() { return resetTimeMillis; }
  public long getWindowSeconds() { return windowSeconds; }
  public String getBandLabel() { return bandLabel; }

  /** WAIT_FOR_REFILL 정책인지 확인 */
  public boolean shouldWaitForRefill() {
    return !allowed && onLimitExceedPolicy == OnLimitExceedPolicy.WAIT_FOR_REFILL;
  }
}
```

### RateLimitResponse 필드

| 필드 | 타입 | 설명 |
|-----|------|------|
| `allowed` | boolean | 요청 허용 여부 |
| `remainingTokens` | long | 남은 토큰 수 (`-1`이면 알 수 없음) |
| `retryAfterMillis` | long | 재시도까지 대기 시간 (ms). 나노초에서 **올림** 변환 |
| `onLimitExceedPolicy` | OnLimitExceedPolicy | 한도 초과 시 정책 (허용 시 null) |
| `limit` | long | 결정을 만든 **binding 대역**의 용량 (`-1`이면 알 수 없음) |
| `resetTimeMillis` | long | binding 대역의 리셋 시각 (epoch 밀리초). TOKEN_BUCKET은 **다시 가득 찰** 시각, SLIDING_WINDOW는 세어진 요청이 모두 윈도를 떠나는 시각, FIXED_WINDOW는 윈도 끝. 인메모리 limiter는 알고리즘과 관계없이 가득 찰 때까지를 추정 (`-1`이면 알 수 없음) |
| `windowSeconds` | long | `limit`에 대응하는 윈도 길이(초). `RateLimit-Policy`용 (`-1`이면 알 수 없음) |
| `bandLabel` | String | binding 대역의 라벨 (`RateLimitBand.getKeyLabel()`) |

> `-1`은 "모름"입니다. `RateLimitHeaderWriter`는 `-1`인 값의 헤더를 **아예 쓰지 않습니다.**
> 잘못된 숫자를 내보내는 것보다 헤더가 없는 편이 클라이언트에게 정직하기 때문입니다.

### binding 대역이란

한 규칙이 여러 대역(초당 10개 AND 분당 100개 …)을 가질 때, 응답이 보고하는 숫자는 **binding 대역**
하나의 것입니다.

- **허용**: 소비 후 토큰이 **가장 적게 남은** 대역
- **거부**: 실제로 **거부한** 대역

가장 여유 있는 대역의 숫자를 내보내면 클라이언트는 실제보다 훨씬 넉넉한 quota를 믿고 달려들게 됩니다.

### OnLimitExceedPolicy

| 정책 | 설명 |
|-----|------|
| `REJECT_REQUEST` | 즉시 429 응답 반환 |
| `WAIT_FOR_REFILL` | 토큰 리필까지 대기 후 1회 재시도 (필터에서 `wait-for-refill.enabled=true`일 때만) |

### HTTP 헤더 매핑

`RateLimitHeaderWriter`(스타터)가 두 계열을 독립적으로 켤 수 있습니다.

| 응답 필드 | legacy 헤더 | IETF 헤더 |
|----------|------------|----------|
| `limit` | `X-RateLimit-Limit` | `RateLimit-Limit` |
| `remainingTokens` | `X-RateLimit-Remaining` | `RateLimit-Remaining` |
| `resetTimeMillis` | `X-RateLimit-Reset` (epoch **초**) | `RateLimit-Reset` (지금부터의 **delta 초**) |
| `limit` + `windowSeconds` | - | `RateLimit-Policy: <limit>;w=<window>` |
| `retryAfterMillis` | `Retry-After` (거부 시에만, 최소 1) | 같음 |

두 `Reset` 헤더는 모두 binding 대역의 **리셋 시점**(TOKEN_BUCKET은 다시 가득 찰 때, SLIDING_WINDOW는
세어진 요청이 모두 윈도를 떠날 때, FIXED_WINDOW는 윈도 끝)을 가리킵니다. `Retry-After`는 "다음 요청이
통과할 수 있을 때까지의 대기"이므로 보통 `Reset`보다 훨씬 짧습니다. 둘은 다른 질문에 답하며,
헤더 라이터도 서로 다른 값에서 계산합니다.

```java
// RateLimitHeaderWriter.java - 실제 코드
if (resetTimeMillis >= 0) {
  long deltaMillis = Math.max(0L, resetTimeMillis - System.currentTimeMillis());
  response.setHeader(
      Headers.STANDARD_RATE_LIMIT_RESET, Long.toString(ceilDiv(deltaMillis, 1000L)));
}
// A zero limit or a sub-second window would render as "0;w=..." or "...;w=0", neither of
// which is a quota a client can act on, so the header is omitted instead.
if (limit > 0 && windowSeconds > 0) {
  response.setHeader(Headers.STANDARD_RATE_LIMIT_POLICY, limit + ";w=" + windowSeconds);
}
```

```java
// RateLimitHeaderWriter.java - 실제 코드
public static long retryAfterSeconds(RateLimitResponse result) {
  long millis = result.getRetryAfterMillis();
  if (millis <= 0) {
    return 1L;
  }
  return Math.max(1L, ceilDiv(millis, 1000L));
}
```

`Retry-After: 0`은 클라이언트를 busy loop로 밀어넣습니다. Rate Limiter가 흘려보내려던 바로 그
트래픽이므로, 올림 처리해 최소 1초를 보장합니다.

---

## 3. EngineBackedRateLimitHandler (라이브러리 기본 구현)

```
fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/handler/
└── EngineBackedRateLimitHandler.java
```

스타터가 등록하는 **공식 기본 핸들러**입니다. 스타터 진입점(`FluxgateRateLimitHandler`)과 코어
SPI(`RateLimitRuleSetProvider` + `RateLimiter`)를 잇는 어댑터이며, 사용자 코드는 한 줄도 필요하지
않습니다.

```java
// EngineBackedRateLimitHandler.java - 실제 코드
public class EngineBackedRateLimitHandler implements FluxgateRateLimitHandler {

  private static final Logger log = LoggerFactory.getLogger(EngineBackedRateLimitHandler.class);

  /** Key prefix {@code RateLimitEngine} uses for an unknown rule set under DENY. */
  private static final String MISSING_RULE_SET_PREFIX = "missing-rule-set:";

  private final RateLimitEngine engine;
  private final Set<String> warnedRuleSetIds = ConcurrentHashMap.newKeySet();

  /**
   * Creates a handler delegating to the given engine.
   *
   * @param engine the engine that resolves rule sets and consumes permits
   */
  public EngineBackedRateLimitHandler(RateLimitEngine engine) {
    this.engine = Objects.requireNonNull(engine, "engine must not be null");
    log.info("EngineBackedRateLimitHandler initialized");
  }

  @Override
  public RateLimitResponse tryConsume(RequestContext context, String ruleSetId) {
    return tryConsume(context, ruleSetId, 1L);
  }

  @Override
  public RateLimitResponse tryConsume(RequestContext context, String ruleSetId, long permits) {
    RateLimitResult result;
    try {
      result = engine.check(ruleSetId, context, permits);
    } catch (MissingRateLimitKeyException e) {
      logConfigurationProblem(
          ruleSetId, "no rate limit key could be resolved, rejecting the request", e);
      return RateLimitResponse.rejected(0L);
    } catch (InvalidRuleConfigException e) {
      // A request cost no band can hold is the client's error (HTTP 429), not a broken rule set.
      PermitsExceedCapacityException tooCostly = PermitsExceedCapacityException.from(e, permits);
      if (tooCostly != null) {
        log.debug("Rule set '{}': {}", ruleSetId, tooCostly.getMessage());
        throw tooCostly;
      }
      logConfigurationProblem(ruleSetId, "the rule configuration is invalid", e);
      throw new RateLimiterUnavailableException(
          "Rule set '" + ruleSetId + "' has an invalid configuration",
          RateLimiterUnavailableException.UNKNOWN_RETRY_AFTER,
          e);
    }
    if (!result.isAllowed() && isMissingRuleSet(result)) {
      logConfigurationProblem(
          ruleSetId, "no such rule set (missing-rule-behavior=DENY), rejecting the request", null);
      throw new RateLimiterUnavailableException("Rule set '" + ruleSetId + "' is not configured");
    }
    return RateLimitResponse.from(result);
  }

  /** The engine reports an unknown rule set under DENY with this synthetic key. */
  private static boolean isMissingRuleSet(RateLimitResult result) {
    RateLimitKey key = result.getKey();
    return key != null && key.value() != null && key.value().startsWith(MISSING_RULE_SET_PREFIX);
  }

  /** Logs a configuration problem at WARN the first time it is seen, then at DEBUG. */
  private void logConfigurationProblem(String ruleSetId, String what, RuntimeException e) {
    String detail = e != null ? e.getMessage() : "";
    if (warnedRuleSetIds.add(ruleSetId)) {
      log.warn("Rule set '{}': {}: {}", ruleSetId, what, detail);
    } else {
      log.debug("Rule set '{}': {}: {}", ruleSetId, what, detail);
    }
  }
}
```

### 이 클래스가 하는 일은 네 가지뿐입니다

1. `engine.check(...)` 호출 — 룰셋 조회, 키 해석, 토큰 소비는 전부 아래 계층의 일입니다
2. `RateLimitResult` → `RateLimitResponse` 변환 (`RateLimitResponse.from`)
3. **설정 오류를 응답 종류로 번역** — 클라이언트의 문제는 429, 운영자의 문제는 503
4. 그 오류를 룰셋 id마다 **한 번만** WARN으로, 이후에는 DEBUG로 기록

3번과 4번이 중요한 이유:

| 상황 | 원인 | 동작 |
|------|------|------|
| `MissingRateLimitKeyException` | `missing-key-behavior=REJECT`인데 스코프가 요구하는 값이 없음 | `rejected(0L)` — 대기 시간 0, HTTP 429 |
| `InvalidRuleConfigException` 중 permits가 대역 용량보다 큼 | 가중치 요청의 비용이 어떤 대역에도 들어가지 않음(호출자 오류) | `PermitsExceedCapacityException`을 던짐(DEBUG 로그만) → HTTP 429 |
| 그 밖의 `InvalidRuleConfigException` | 룰셋 자체를 만들 수 없음 | `RateLimiterUnavailableException`을 던짐 → HTTP 503 |
| 룰셋 없음 (`missing-rule-behavior=DENY`) | 엔진이 `missing-rule-set:` 합성 키로 거부 | `RateLimiterUnavailableException`을 던짐 → HTTP 503 |

키가 없는 거부는 **대기 시간이 0**입니다. 부족한 것은 토큰이 아니라 헤더이므로 기다린다고 해결되지
않습니다. `Retry-After`는 헤더 라이터에서 최소 1초로 올라가지만, 이 값이 "리필까지의 시간"이 아니라는
사실은 유지됩니다. 룰셋을 만들 수 없거나 없는 경우는 한도 초과가 아니라 설정 문제이므로 429가 아니라
503입니다. `failure-behavior=ALLOW`여도 마찬가지입니다. 그 설정은 리미터 장애를 다루지 설정 오류를
다루지 않습니다.

`warnedRuleSetIds`가 없다면 잘못 설정된 룰셋 하나가 **요청마다** WARN을 찍어 로그를 채웁니다.
핫 패스에서 로그 볼륨은 그 자체로 장애 요인입니다.

### 자동 등록 조건

```java
// FluxgateRateLimiterAutoConfiguration.java - 실제 코드
@Bean
@ConditionalOnMissingBean(FluxgateRateLimitHandler.class)
@ConditionalOnBean(RateLimitEngine.class)
public FluxgateRateLimitHandler fluxgateRateLimitHandler(RateLimitEngine engine) {
  log.info("Creating EngineBackedRateLimitHandler as the default FluxgateRateLimitHandler");
  return new EngineBackedRateLimitHandler(engine);
}
```

`RateLimitEngine` 자체는 `RateLimiter`와 `RateLimitRuleSetProvider`가 모두 있을 때만 만들어집니다.

```java
// FluxgateRateLimiterAutoConfiguration.java - 실제 코드
@Bean
@ConditionalOnMissingBean(RateLimitEngine.class)
@ConditionalOnBean({RateLimiter.class, RateLimitRuleSetProvider.class})
public RateLimitEngine fluxgateRateLimitEngine(
    RateLimitRuleSetProvider ruleSetProvider,
    RateLimiter rateLimiter,
    ObjectProvider<PathPatternMatcher> pathMatcherProvider) {
  OnMissingRuleSetStrategy strategy =
      properties.getRatelimit().isDenyWhenRuleMissing()
          ? OnMissingRuleSetStrategy.DENY
          : OnMissingRuleSetStrategy.ALLOW;
  // ...
}
```

`@ConditionalOnMissingBean(FluxgateRateLimitHandler.class)`이므로, 직접 `FluxgateRateLimitHandler`
빈을 정의하면 기본 핸들러는 등록되지 않고 여러분의 구현이 쓰입니다.

### 핸들러 뒤에 놓이는 RateLimiter 체인

`EngineBackedRateLimitHandler`가 보는 `RateLimiter`는 보통 맨 앞의 원시 구현이 아니라 데코레이터
체인입니다.

```
EngineBackedRateLimitHandler
        │
        v
   RateLimitEngine
        │  (fluxgate.ratelimit.missing-rule-behavior → ALLOW / DENY)
        v
   ResilientRateLimiter        @Primary
        │  재시도 + 서킷 브레이커(fluxgate.resilience), 실패 시 강등
        ├─ fallback.mode=IN_MEMORY → Bucket4jRateLimiter (인스턴스 로컬)
        └─ 아니면 failure-behavior: ALLOW=무제한 통과 / DENY=거부
        v
   LazyRedisRateLimiter        (fluxgate.redis.enabled=true)
        │  최초 1회 연결 시도, 실패하면 백그라운드 재연결
        v
   RedisRateLimiter → RedisTokenBucketStore → Lua
```

0.3.x에서는 재시도·서킷 브레이커 설정이 Rate Limiting 경로에 **연결되어 있지 않았습니다.**
0.4에서는 `ResilientRateLimiter`가 `@Primary`로 등록되어 모든 호출이
`ResilientExecutor.executeWithFallback`을 통과합니다.

```java
// ResilientRateLimiter.java - 실제 코드
@Override
public RateLimitResult tryConsume(
    RequestContext context, RateLimitRuleSet ruleSet, long permits) {
  return execute(context, ruleSet, permits, null);
}

// PathPatternMatcher를 받는 인자 4개짜리 오버로드는 execute(context, ruleSet, permits, pathMatcher)를 호출합니다

/** Runs the primary limiter through the executor; a null matcher uses the 3-arg overload. */
private RateLimitResult execute(
    RequestContext context,
    RateLimitRuleSet ruleSet,
    long permits,
    PathPatternMatcher pathMatcher) {
  // A cost that can never fit is the client's error: reject it before the breaker sees the call.
  checkPermitsFitCapacity(context, ruleSet, permits, pathMatcher);

  // Remembers the failure so the fallback can tag the metric with its cause; an open circuit
  // never runs the action, which is why the reference can still be empty in the fallback.
  AtomicReference<Throwable> failure = new AtomicReference<>();
  // A client or configuration error leaves the executor as an IgnoredCallException, which the
  // breaker records as neither a success nor a failure and never answers with the fallback.
  AtomicReference<RuntimeException> clientError = new AtomicReference<>();
  try {
    return executor.executeWithFallback(
        OPERATION,
        () -> {
          try {
            return consume(delegate, context, ruleSet, permits, pathMatcher);
          } catch (RuntimeException e) {
            if (isClientError(e)) {
              clientError.set(e);
              throw new IgnoredCallException(e);
            }
            failure.set(e);
            throw e;
          }
        },
        () -> {
          // a custom breaker that does not know IgnoredCallException falls back instead
          RuntimeException rejected = clientError.get();
          if (rejected != null) {
            throw rejected;
          }
          return degrade(context, ruleSet, permits, pathMatcher, failure.get());
        });
  } catch (IgnoredCallException e) {
    RuntimeException rejected = clientError.get();
    throw rejected != null ? rejected : e;
  }
}
```

강등 대상은 리미터의 장애뿐입니다. 클라이언트나 설정의 오류(`FluxgateConfigurationException`,
`IllegalArgumentException`)는 `IgnoredCallException`으로 감싸
실행기를 빠져나가므로, 재시도되지 않고 서킷 브레이커의 성공·실패 어느 쪽에도 집계되지 않으며 fallback으로
답하지도 않습니다. 같은 호출은 매번 같은 방식으로 실패하기 때문입니다. 대역 용량을 넘는 permits는
`checkPermitsFitCapacity`가 브레이커를 거치기 전에 미리 거부합니다.

강등은 조용히 일어나지 않습니다. 매번 `fluxgate.limiter.failures` 카운터가
`fallback_in_memory` / `fail_open` / `fail_closed` 태그와 함께 증가하고 로그가 남습니다.

---

## 4. MissingRuleSetProviderRateLimitHandler (설정 누락 감지용)

```
fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/handler/
└── MissingRuleSetProviderRateLimitHandler.java
```

`fluxgate.redis.enabled=true`만 켜고 룰 소스(MongoDB 어댑터 등)를 붙이지 않으면 `RateLimiter`는
생기지만 `RateLimitRuleSetProvider`가 없어 `RateLimitEngine`과 기본 핸들러가 **둘 다 건너뛰어집니다.**

```java
// FluxgateRateLimiterAutoConfiguration.java - 실제 코드 주석
/**
 * Registers a clearly named stopgap handler when a limiter exists but no rule source does.
 *
 * <p>{@code fluxgate.redis.enabled=true} without the MongoDB adapter produces a {@link
 * RateLimiter} and no {@link RateLimitRuleSetProvider}, so {@link #fluxgateRateLimitEngine} and
 * the handler above are both skipped and the filter fell through to its own no-handler path. That
 * path blamed the missing {@code RateLimiter} - the one bean that did exist - and then silently
 * allowed or rejected everything. {@link MissingRuleSetProviderRateLimitHandler} logs the real
 * cause once and applies {@code failure-behavior} explicitly.
 */
```

즉 이 핸들러의 존재 이유는 **오진 방지**입니다. 이전에는 "RateLimiter가 없습니다"라는 잘못된 원인을
가리키며 전부 통과/전부 거부했습니다.

---

## 5. 자체 핸들러: HTTP API 모드 (샘플)

```
fluxgate-samples/fluxgate-sample-filter/src/main/java/org/fluxgate/sample/filter/handler/
└── HttpRateLimitHandler.java
```

> 이것은 **샘플 모듈의 코드**이며 라이브러리에 포함되지 않습니다. 중앙 Rate Limit 서비스를 두는
> 구성을 어떻게 만드는지 보여주기 위한 예시입니다.

```java
// HttpRateLimitHandler.java - 샘플 코드
@Component
public class HttpRateLimitHandler implements FluxgateRateLimitHandler {

  private final RestClient restClient;
  private final String apiUrl;

  public HttpRateLimitHandler(@Value("${fluxgate.api.url:http://localhost:8080}") String apiUrl) {
    this.apiUrl = apiUrl;
    this.restClient = RestClient.builder().baseUrl(apiUrl).build();
    log.info("HttpRateLimitHandler initialized with API URL: {}", apiUrl);
  }

  @Override
  public RateLimitResponse tryConsume(RequestContext context, String ruleSetId) {
    try {
      RateLimitApiResponse response = restClient
          .post()
          .uri("/api/ratelimit/check")
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of(
              "ruleSetId", ruleSetId,
              "clientIp", context.getClientIp() != null ? context.getClientIp() : "",
              "userId", context.getUserId() != null ? context.getUserId() : "",
              "apiKey", context.getApiKey() != null ? context.getApiKey() : "",
              "endpoint", context.getEndpoint() != null ? context.getEndpoint() : "",
              "method", context.getMethod() != null ? context.getMethod() : ""))
          .retrieve()
          .body(RateLimitApiResponse.class);

      if (response == null) {
        log.warn("Empty response from FluxGate API, allowing request");
        return RateLimitResponse.allowed(-1, 0);
      }

      if (response.allowed) {
        return RateLimitResponse.allowed(response.remaining, 0);
      } else {
        return RateLimitResponse.rejected(response.retryAfterMs);
      }

    } catch (Exception e) {
      log.error("Failed to call FluxGate API at {}: {}", apiUrl, e.getMessage());
      // Fail open: API 호출 실패 시 요청 허용
      return RateLimitResponse.allowed(-1, 0);
    }
  }
}
```

### 사용 시나리오

```
+-------------------+         +---------------------------+
|  API Gateway      |  HTTP   |  Rate Limit Service       |
|  (Port 8080)      | ------> |  (Port 8082)              |
|                   |         |                           |
|  HttpRateLimit    |         |  EngineBacked + Redis     |
|  Handler          |         |                           |
+-------------------+         +---------------------------+
```

### 설정

```yaml
fluxgate:
  api:
    url: http://localhost:8080  # FluxGate API 서버 URL
```

### 자체 핸들러를 쓸 때 주의할 점

샘플 코드는 `allowed(-1, 0)` / `rejected(retryAfterMs)`만 쓰기 때문에 `limit`, `resetTimeMillis`,
`windowSeconds`가 전부 `-1`입니다. 그러면 `X-RateLimit-Limit`, `RateLimit-Reset`,
`RateLimit-Policy` 헤더가 아예 나가지 않습니다. 원격 API가 그 값들을 돌려준다면
`RateLimitResponse.builder()`로 채워 넣으세요.

```java
// RateLimitResponse.java - 실제 코드 주석
/**
 * Creates a new builder.
 *
 * <p>Prefer the factories above for the common cases; the builder exists for callers that need to
 * set the band window or label explicitly, such as an HTTP layer assembling headers.
 */
public static Builder builder() { ... }
```

또한 샘플은 API 장애 시 **fail-open**으로 고정되어 있습니다. 라이브러리 경로에서는 그 결정이
`fluxgate.ratelimit.failure-behavior`(그리고 `fallback.mode`)로 설정 가능하고, 강등이 일어날 때마다
`fluxgate.limiter.failures` 메트릭이 올라갑니다. 자체 핸들러를 쓰면 그 관측성을 스스로 만들어야
합니다.

---

## 6. Handler 선택 가이드

| 상황 | 추천 Handler | 이유 |
|-----|-------------|------|
| 대부분의 경우 | `EngineBackedRateLimitHandler` (자동) | 별도 코드 없이 resilience·메트릭·헤더가 모두 연결됨 |
| Rate Limit을 중앙 서비스로 분리 | 자체 HTTP 핸들러 | 서비스 간 정책 통일. 단, 헤더/관측성 직접 구현 |
| 개발/테스트에서 Rate Limiting 끄기 | `fluxgate.ratelimit.enabled=false` | 핸들러를 `ALLOW_ALL`로 바꾸는 것보다 의도가 명확함 |

분산 환경에서 "인스턴스마다 Redis에 직접" vs "중앙 API 호출"은 핸들러 선택이 아니라 **배치** 문제입니다.
전자가 기본이고, 네트워크 홉이 하나 적으며, `RedisRateLimiter`가 이미 노드 간 공유 한도를 보장합니다.

---

## 관련 문서

- [Filter Layer Deep Dive](filter-layer.ko.md)
- [Engine Layer Deep Dive](engine-layer.ko.md)
- [RateLimiter Layer Deep Dive](ratelimiter-layer.ko.md)
- [아키텍처 개요](../README.ko.md)
