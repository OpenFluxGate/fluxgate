# Filter Layer Deep Dive

> 이 문서는 FluxGate **0.4**의 코드를 기준으로 합니다. 0.3.x에서 무엇이 바뀌었는지는
> [0.4 마이그레이션](../../operations/migration-0.4.ko.md)에 정리되어 있습니다.

이 문서는 FluxGate의 Filter Layer를 **실제 소스코드**와 함께 상세히 설명합니다.

[< 아키텍처 개요로 돌아가기](../README.ko.md)

---

## 목차

1. [FluxgateRateLimitFilter](#1-fluxgateratelimitfilter)
2. [RequestContextFactory와 클라이언트 IP](#2-requestcontextfactory와-클라이언트-ip)
3. [RequestContext](#3-requestcontext)
4. [RequestContextCustomizer](#4-requestcontextcustomizer)

---

## 1. FluxgateRateLimitFilter

HTTP 요청을 가로채고 Rate Limiting을 적용하는 진입점입니다.

```
fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/filter/
└── FluxgateRateLimitFilter.java
```

```java
// FluxgateRateLimitFilter.java - 실제 코드 (협력자 필드)
public class FluxgateRateLimitFilter extends OncePerRequestFilter {

  private final FluxgateRateLimitHandler handler;
  private final String ruleSetId;
  private final String[] includePatterns;
  private final String[] excludePatterns;
  private final boolean failOpenOnError;
  private final boolean denyWhenRuleMissing;
  private final AntPathMatcher pathMatcher;
  private final boolean logQueryString;
  private final String costHeader;
  private final long maxCost;

  // WAIT_FOR_REFILL 설정
  private final boolean waitForRefillEnabled;
  private final long maxWaitTimeMs;
  private final Semaphore waitSemaphore;

  private final RequestContextFactory contextFactory;
  private final RateLimitHeaderWriter headerWriter;
  private final RateLimitResponseWriter responseWriter;
  private final RateLimitDurationRecorder durationRecorder;
}
```

0.3.x 필터는 `RequestContextCustomizer`를 직접 들고 컨텍스트를 스스로 조립했고, 헤더도 스스로
붙였습니다. 0.4는 그 두 일을 전용 협력자에게 넘겼습니다.

| 협력자 | 역할 | 넘긴 이유 |
|-------|------|----------|
| `RequestContextFactory` | `RequestContext` 조립 (IP·신원·헤더·커스터마이저) | AOP 애스펙트와 **동일한** 컨텍스트를 쓰게 하려고 |
| `RateLimitHeaderWriter` | legacy + IETF 헤더 작성 | 필터와 애스펙트가 같은 정보를 광고하게 하려고 |
| `RateLimitResponseWriter` | 429 본문 작성 | 응답 형식을 교체 가능하게 |
| `RateLimitDurationRecorder` | 소요 시간 메트릭 | Micrometer 의존을 필터에서 분리 |

### doFilterInternal

```java
// FluxgateRateLimitFilter.java - 실제 코드

@Override
protected void doFilterInternal(
    HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
    throws ServletException, IOException {

  // M28/H-4: preserve MDC entries put in place by filters that ran before this one.
  Map<String, String> previousMdc = MDC.getCopyOfContextMap();
  long startTimeMs = System.currentTimeMillis();

  try {
    String path = RequestPathResolver.resolve(request);
    populateRequestMdc(request, path);

    if (shouldExclude(path)) {
      log.debug("Path excluded from rate limiting: {}", path);
      filterChain.doFilter(request, response);
      return;
    }

    if (!shouldInclude(path)) {
      log.debug("Path not included in rate limiting: {}", path);
      filterChain.doFilter(request, response);
      return;
    }

    if (!StringUtils.hasText(ruleSetId)) {
      if (denyWhenRuleMissing) {
        logMissingRuleSetId("rejecting request");
        rejectUnavailable(
            request, response, RateLimiterUnavailableException.UNKNOWN_RETRY_AFTER, startTimeMs);
      } else {
        logMissingRuleSetId("skipping rate limiting");
        filterChain.doFilter(request, response);
      }
      return;
    }

    RequestContext context = contextFactory.create(request, path);
    putIdentityMdc(context.getUserId(), context.getApiKey());
    Decision decision = decide(context, resolvePermits(request));

    // C2/C-1: the chain runs exactly once, outside the rate limiter's try/catch, so an exception
    // thrown by the application propagates instead of triggering a replay.
    if (decision.unavailable) {
      rejectUnavailable(request, response, decision.retryAfterMillis, startTimeMs);
      recordDuration(path, request.getMethod(), startTimeMs);
    } else if (decision.costExceeded != null) {
      rejectCostExceeded(request, response, decision.costExceeded, startTimeMs);
      recordDuration(path, request.getMethod(), startTimeMs);
    } else if (decision.allowed) {
      headerWriter.write(response, decision.result);
      filterChain.doFilter(request, response);
      MDC.put(MdcKeys.STATUS_CODE, String.valueOf(response.getStatus()));
      MDC.put(MdcKeys.DURATION_MS, String.valueOf(System.currentTimeMillis() - startTimeMs));
      log.debug("Request completed");
      recordDuration(path, request.getMethod(), startTimeMs);
    } else {
      reject(request, response, decision.result, startTimeMs);
      recordDuration(path, request.getMethod(), startTimeMs);
    }
  } finally {
    restoreMdc(previousMdc);
  }
}
```

### 0.4에서 고쳐진 것: 필터 체인이 정확히 한 번만 실행됩니다

주석 `C2/C-1`이 가리키는 수정입니다.

0.3.x는 `filterChain.doFilter(...)`가 **try 블록 안에** 있었습니다.

```java
// 0.3.x 구조 (문제)
try {
    RateLimitResponse result = handler.tryConsume(context, ruleSetId);
    addRateLimitHeaders(response, result);
    if (result.isAllowed()) {
        filterChain.doFilter(request, response);   // ← try 안에 있음
    } else { ... }
} catch (Exception e) {
    log.error("Error during rate limiting, allowing request", e);
    filterChain.doFilter(request, response);       // ← 두 번째 실행!
}
```

애플리케이션이 던진 예외가 이 `catch`에 잡히면, 필터는 그것을 **Rate Limiter 오류로 오해하고**
체인을 다시 실행했습니다. 컨트롤러가 두 번 호출되고, 이미 커밋된 응답에 또 쓰려다 깨집니다.

0.4는 Rate Limiter 호출만 `decide()` 안에서 감싸고, 체인은 그 밖에서 정확히 한 번 실행합니다.

```java
// FluxgateRateLimitFilter.java - 실제 코드
/**
 * Runs the rate limiter and decides whether the request may proceed.
 *
 * <p>Only the handler call and the bookkeeping around it are guarded, so a limiter failure is
 * distinguishable from an application failure.
 */
private Decision decide(RequestContext context, long permits) {
  try {
    RateLimitResponse result = tryConsume(context, permits);

    MDC.put(MdcKeys.RATE_LIMIT_ALLOWED, String.valueOf(result.isAllowed()));
    MDC.put(MdcKeys.REMAINING_TOKENS, String.valueOf(result.getRemainingTokens()));

    if (!result.isAllowed() && waitForRefillEnabled && result.shouldWaitForRefill()) {
      result = waitForRefill(context, permits, result);
    }

    if (result.isAllowed()) {
      return Decision.allowed(result);
    }

    MDC.put(MdcKeys.RETRY_AFTER_MS, String.valueOf(result.getRetryAfterMillis()));
    return Decision.rejected(result);
  } catch (PermitsExceedCapacityException e) {
    // The client asked for more than a band can ever hold: its error, not a limiter failure, so
    // neither failure-behavior nor a 503 applies.
    MDC.put(MdcKeys.ERROR, e.getClass().getSimpleName());
    log.debug("Request cost exceeds the rule capacity, rejecting: {}", e.getMessage());
    return Decision.costExceeded(e);
  } catch (RateLimiterUnavailableException e) {
    // failure-behavior / missing-rule-behavior already decided to reject; the cause was logged
    // where it happened.
    MDC.put(MdcKeys.ERROR, e.getClass().getSimpleName());
    MDC.put(MdcKeys.ERROR_MESSAGE, LogSanitizer.sanitize(e.getMessage()));
    log.debug("Rate limiting unavailable, rejecting request: {}", e.getMessage());
    return Decision.unavailable(e.getRetryAfterMillis());
  } catch (Exception e) {
    MDC.put(MdcKeys.ERROR, e.getClass().getSimpleName());
    MDC.put(MdcKeys.ERROR_MESSAGE, LogSanitizer.sanitize(e.getMessage()));
    if (failOpenOnError) {
      log.error("Error during rate limiting, allowing request", e);
      return Decision.allowed(null);
    }
    log.error("Error during rate limiting, rejecting request", e);
    return Decision.unavailable(RateLimiterUnavailableException.UNKNOWN_RETRY_AFTER);
  }
}
```

### fail-open이 더 이상 하드코딩이 아닙니다

0.3.x는 Rate Limiter 오류 시 **무조건 통과**였습니다(`// Fail open`). 0.4는 `failOpenOnError`로
결정합니다.

```yaml
fluxgate:
  ratelimit:
    failure-behavior: DENY    # 기본값 DENY = fail-closed, ALLOW = fail-open
    missing-rule-behavior: DENY  # 기본값 DENY, ALLOW = 룰셋이 없으면 통과
```

기본값이 `DENY`(fail-closed)입니다. 0.3.x가 하드코딩한 fail-open과 **반대 방향**의 기본값이라는
점에 주의하세요. Rate Limit이 보안 통제인 배포에서는 "장애 시 전부 통과"가 받아들일 수 없는
선택이므로, 안전한 쪽을 기본으로 두고 `ALLOW`를 명시적 선택으로 만들었습니다.

`failureBehavior`는 `isAllowWhenLimiterFails()`를 통해 필터의 `failOpenOnError`와
`ResilientRateLimiter`의 `allowOnFailure`로 함께 전달됩니다. 즉 필터에서 잡히는 오류와
limiter 내부 강등이 같은 설정을 따릅니다.

`denyWhenRuleMissing`은 `ruleSetId`가 설정되지 않은 경우에도 적용됩니다. 설정 누락이 조용히
Rate Limiting 비활성화가 되지 않습니다.

`decide()`는 결과를 네 갈래로 나눕니다. 응답 코드가 다른 이유는 "누구의 문제인가"가 다르기 때문입니다.

| `Decision` | 원인 | 응답 |
|-----------|------|------|
| `allowed` | 허용 (또는 `failure-behavior=ALLOW`에서의 리미터 오류) | 체인 실행 |
| `rejected` | 한도 초과 | 429 + `Retry-After` |
| `costExceeded` | `PermitsExceedCapacityException`: 요청 비용이 어떤 대역에도 들어가지 않음(클라이언트 오류) | 429 |
| `unavailable` | `RateLimiterUnavailableException`(설정 오류·룰셋 없음·이미 강등이 거부로 결정), `failure-behavior=DENY`에서의 리미터 오류, `ruleSetId` 미설정 + `missing-rule-behavior=DENY` | 503 |

### 가중 permits (cost header)

```java
// FluxgateRateLimitFilter.java - 실제 코드
private RateLimitResponse tryConsume(RequestContext context, long permits) {
  // The 3-arg form is optional for handlers, so only use it for a weighted request.
  return permits == SINGLE_PERMIT
      ? handler.tryConsume(context, ruleSetId)
      : handler.tryConsume(context, ruleSetId, permits);
}

/**
 * Resolves how many permits this request costs.
 *
 * @return the permit count, always at least 1 and never above the configured maximum
 */
private long resolvePermits(HttpServletRequest request) {
  if (!StringUtils.hasText(costHeader)) {
    return SINGLE_PERMIT;
  }
  String raw = request.getHeader(costHeader);
  if (!StringUtils.hasText(raw)) {
    return SINGLE_PERMIT;
  }
  try {
    long cost = Long.parseLong(raw.trim());
    return cost < SINGLE_PERMIT ? SINGLE_PERMIT : Math.min(cost, maxCost);
  } catch (NumberFormatException e) {
    log.debug("Ignoring unparseable {} header", costHeader);
    return SINGLE_PERMIT;
  }
}
```

```yaml
fluxgate:
  ratelimit:
    cost-header: X-Request-Cost   # 설정하지 않으면 항상 1
    max-cost: 1000
```

비싼 API가 값싼 API보다 더 많은 토큰을 소비하게 하는 장치입니다. 헤더 값은 **클라이언트 입력**이므로
세 겹으로 방어합니다.

| 입력 | 결과 |
|-----|------|
| 헤더 없음 / 빈 값 | 1 |
| 파싱 실패 | 1 (DEBUG 로그, 예외 없음) |
| 1 미만 (`0`, `-5`) | 1 |
| `max-cost` 초과 | `max-cost`로 클램프 |

`cost-header`를 설정하는 순간 클라이언트가 자기 요청 비용을 신고하게 되므로, 비용을 **낮게**
신고하는 것은 하한 1로, **높게** 신고하는 것은 상한으로 막습니다. 신뢰할 수 없는 클라이언트에게는
게이트웨이가 헤더를 덮어쓰도록 구성하세요.

### MDC: 요청 정보의 구조화 로깅

```java
// FluxgateRateLimitFilter.java - 실제 코드
/**
 * Populates the MDC with request information.
 *
 * <p>Every value that originates from the request is sanitized first: a header containing CRLF
 * would otherwise forge log lines, and an oversized one would flood the log.
 */
private void populateRequestMdc(HttpServletRequest request, String path) {
  String traceId = LogSanitizer.sanitize(request.getHeader(Headers.TRACE_ID), 128);
  if (traceId == null || traceId.isBlank()) {
    traceId = UUID.randomUUID().toString();
  }

  MDC.put(MdcKeys.TRACE_ID, traceId);
  MDC.put(MdcKeys.RULE_SET_ID, ruleSetId);

  MDC.put(MdcKeys.METHOD, LogSanitizer.sanitize(request.getMethod(), 16));
  MDC.put(MdcKeys.ENDPOINT, LogSanitizer.sanitize(path));
  MDC.put(MdcKeys.CLIENT_IP, LogSanitizer.sanitize(contextFactory.extractClientIp(request), 45));
  MDC.put(MdcKeys.PROTOCOL, LogSanitizer.sanitize(request.getProtocol(), 16));
  MDC.put(MdcKeys.SERVER_PORT, String.valueOf(request.getServerPort()));

  if (logQueryString) {
    Optional.ofNullable(request.getQueryString())
        .ifPresent(v -> MDC.put(MdcKeys.QUERY_STRING, LogSanitizer.sanitize(v)));
  }
  Optional.ofNullable(request.getHeader(USER_AGENT_HEADER))
      .ifPresent(v -> MDC.put(MdcKeys.USER_AGENT, LogSanitizer.sanitize(v)));
  Optional.ofNullable(request.getHeader(REFERER_HEADER))
      .ifPresent(v -> MDC.put(MdcKeys.REFERER, LogSanitizer.sanitize(v)));

  // C-4: identity headers are client input. Log them as the caller's identity only when the
  // identity source actually reads them; otherwise the resolved identity is added once the
  // context exists (putResolvedIdentityMdc).
  if (contextFactory.usesIdentityHeaders()) {
    putIdentityMdc(
        request.getHeader(contextFactory.getUserIdHeader()),
        request.getHeader(contextFactory.getApiKeyHeader()));
  }
}

/** Records the identity the limiter actually used, such as the authenticated principal. */
private static void putIdentityMdc(String userId, String apiKey) {
  Optional.ofNullable(userId)
      .ifPresent(v -> MDC.put(MdcKeys.USER_ID, LogSanitizer.sanitize(v, 128)));
  Optional.ofNullable(apiKey)
      .ifPresent(v -> MDC.put(MdcKeys.API_KEY, maskSensitive(LogSanitizer.sanitize(v, 128))));
}
```

두 가지가 눈에 띕니다.

**(1) 모든 값이 `LogSanitizer`를 통과합니다.** 헤더에 CRLF가 들어 있으면 공격자가 **로그 줄을
위조**할 수 있습니다(log injection). 길이 제한은 로그 폭주를 막습니다.

**(2) API 키는 `maskSensitive`를 한 번 더 거칩니다.** 로그 파일이 자격 증명 저장소가 되지 않게
합니다.

**(3) 신원 헤더는 신원 출처가 실제로 그 헤더를 읽을 때만 기록합니다.** 헤더는 클라이언트 입력이므로,
인증 주체 등 다른 출처를 쓰는 배포에서는 컨텍스트가 만들어진 뒤 리미터가 실제로 쓴 신원을
`putIdentityMdc(context.getUserId(), context.getApiKey())`로 기록합니다.

### MDC 복원

```java
// M28/H-4: preserve MDC entries put in place by filters that ran before this one.
Map<String, String> previousMdc = MDC.getCopyOfContextMap();
...
} finally {
  restoreMdc(previousMdc);
}
```

```java
// FluxgateRateLimitFilter.java - 실제 코드
/** Restores the MDC to the state the request arrived with. */
private static void restoreMdc(Map<String, String> previousMdc) {
  MDC.clear();
  if (previousMdc != null && !previousMdc.isEmpty()) {
    MDC.setContextMap(previousMdc);
  }
}
```

MDC는 스레드 로컬이고 서블릿 컨테이너는 스레드를 재사용합니다. 정리하지 않으면 **다음 요청의 로그에
이전 요청의 traceId가 섞입니다.** 그냥 `MDC.clear()`만 하면 이 필터보다 먼저 실행된 필터가 넣은
항목까지 날아가므로, 진입 시점의 스냅샷으로 되돌립니다.

### 정규화 불가 경로는 제외하지 않습니다

```java
// FluxgateRateLimitFilter.java - 실제 코드
/**
 * Checks if a path should be excluded from rate limiting.
 *
 * <p>N-2: a path that could not be normalized is never excluded. Matching it against operator
 * patterns would decide the question with a string the operator never wrote.
 */
private boolean shouldExclude(String path) {
  if (RequestPathResolver.UNRESOLVABLE_PATH.equals(path)) {
    return false;
  }
  for (String pattern : excludePatterns) {
    if (pathMatcher.match(pattern, path)) {
      return true;
    }
  }
  return false;
}
```

`/api/../actuator/health` 같은 경로를 정규화할 수 없을 때, 그 문자열을 그대로 `excludePatterns`에
맞춰보면 운영자가 의도하지 않은 문자열로 제외 여부가 결정됩니다. 정규화 실패는 **제외하지 않음**
(= Rate Limiting 적용)으로 처리합니다. 보수적인 쪽이 안전합니다.

### 주요 특징

| 구성 요소 | 역할 |
|----------|------|
| `FluxgateRateLimitHandler` | Rate Limit 체크 위임 |
| `RequestContextFactory` | `RequestContext` 조립 (애스펙트와 공유) |
| `RateLimitHeaderWriter` | legacy `X-RateLimit-*` + IETF `RateLimit-*` 헤더 |
| `RateLimitResponseWriter` | 429 응답 본문 |
| `AntPathMatcher` | URL 패턴 매칭 (include/exclude) |
| `Semaphore` | WAIT_FOR_REFILL 동시 대기 요청 제한 |
| MDC + `LogSanitizer` | 구조화 로깅, log injection 방어 |

### WAIT_FOR_REFILL 처리

```java
// FluxgateRateLimitFilter.java - 실제 코드
/**
 * Applies the WAIT_FOR_REFILL policy: wait for the advertised delay, then retry once.
 *
 * <p>A non-blocking semaphore bounds how many worker threads may be parked at the same time; the
 * rest are rejected immediately, because parking every thread turns the rate limiter into a
 * denial of service amplifier.
 *
 * @return the final decision, either the retry result or the original rejection
 */
private RateLimitResponse waitForRefill(
    RequestContext context, long permits, RateLimitResponse result) {

  long waitTimeMs = result.getRetryAfterMillis();
  if (waitTimeMs > maxWaitTimeMs) {
    log.debug("Wait time {} ms exceeds max {} ms, rejecting", waitTimeMs, maxWaitTimeMs);
    return result;
  }

  if (!waitSemaphore.tryAcquire()) {
    log.debug("Too many concurrent waits, rejecting");
    return result;
  }

  try {
    log.debug("Waiting {} ms for token refill", waitTimeMs);
    TimeUnit.MILLISECONDS.sleep(Math.max(0L, waitTimeMs));

    RateLimitResponse retryResult = tryConsume(context, permits);
    if (!retryResult.isAllowed()) {
      log.debug("Request still rate limited after wait");
    }
    return retryResult;
  } catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    log.debug("Wait for token refill interrupted");
    return result;
  } finally {
    waitSemaphore.release();
  }
}
```

Javadoc의 표현이 정확합니다. **"parking every thread turns the rate limiter into a denial of
service amplifier."** 모든 워커 스레드를 재우면 Rate Limiter 자체가 DoS 증폭기가 됩니다.
그래서 `tryAcquire()`(논블로킹)이며, 세마포어를 얻지 못한 요청은 기다리지 않고 즉시 거부됩니다.

`재시도는 딱 한 번`입니다. 재시도 후에도 거부되면 그대로 거부를 반환합니다. 루프를 돌면 대기 시간이
누적되어 `maxWaitTimeMs`의 의미가 사라집니다.

### WAIT_FOR_REFILL의 분산 환경 한계

> **주의**: `Semaphore`는 JVM 로컬 자원입니다. Kubernetes MSA 환경에서는 아래와 같은 한계가 있습니다.

```
Pod A                    Pod B                    Pod C
+------------------+    +------------------+    +------------------+
| Semaphore(100)   |    | Semaphore(100)   |    | Semaphore(100)   |
| 현재 대기: 50    |    | 현재 대기: 50    |    | 현재 대기: 50    |
+------------------+    +------------------+    +------------------+
         |                       |                       |
         +-----------------------+-----------------------+
                                 |
                    총 대기 요청: 150개 (클러스터 수준 제어 불가)
```

| 관심사                | 담당        | 범위      |
|--------------------|-----------|---------|
| Rate Limit (토큰 버킷) | Redis     | 클러스터 공유 |
| 스레드 풀 보호           | Semaphore | Pod 로컬  |

**현재 Semaphore의 목적**: 클러스터 전체 대기 수 제한이 아니라, **개별 Pod의 스레드 풀 고갈 방지**입니다.

#### 분산 환경에서의 대안

**옵션 1: Redis 기반 분산 Semaphore**

```java
// Redisson 등 사용
RSemaphore semaphore = redisson.getSemaphore("fluxgate:wait-semaphore");
semaphore.trySetPermits(100);  // 클러스터 전체에서 100개
```

**옵션 2: WAIT_FOR_REFILL 비활성화 (권장)**

분산 환경에서는 `WAIT_FOR_REFILL` 대신 즉시 429를 반환하고, 클라이언트가 `Retry-After` 헤더를 보고
재시도하도록 설계합니다. 0.4의 `Retry-After`는 올림 처리되어 절대 `0`이 되지 않으므로, 클라이언트가
busy loop에 빠지지 않습니다.

```yaml
fluxgate:
  ratelimit:
    wait-for-refill:
      enabled: false  # 분산 환경에서는 비활성화 권장
```

**옵션 3: 현재 방식 유지 (Pod별 보호)**

각 Pod가 자신의 스레드 풀만 보호합니다. 전체 대기 수는 `maxConcurrentWaits * Pod 수`가 됩니다.
이 방식은 개별 Pod의 안정성은 보장하지만, 클러스터 전체의 동시 대기 요청 수는 제어하지 않습니다.

---

## 2. RequestContextFactory와 클라이언트 IP

```
fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/filter/
├── RequestContextFactory.java
├── IdentitySource.java
└── RequestContextCustomizer.java

fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/util/
├── ClientIpExtractor.java
└── TrustedProxies.java
```

```java
// RequestContextFactory.java - 실제 코드
/**
 * Builds the {@link RequestContext} handed to the rate limiter.
 *
 * <p>Filter and aspect share this factory so a rule set behaves identically in both modes. Before
 * it existed the aspect populated only the client IP, which silently demoted every {@code
 * PER_USER}, {@code PER_API_KEY} and {@code CUSTOM} scope to per-IP limiting.
 *
 * <p>Where the identity comes from is configurable through {@link IdentitySource}: the
 * authenticated principal, request headers, or the principal with a header fallback. Headers are
 * client input, so principal based resolution is what makes {@code PER_USER} limiting trustworthy.
 *
 * <p>Header collection is opt-in ({@code fluxgate.ratelimit.collect-headers}) and restricted to an
 * allow list, because the context is passed on to metrics recorders that may persist it. Credential
 * carrying headers are never copied, even when explicitly allow listed.
 */
public class RequestContextFactory {

  /**
   * Headers that are never copied into the context, whatever the allow list says.
   *
   * <p>N-9: every entry is a credential or a credential challenge. The context reaches metrics
   * recorders that persist it, so copying one of these writes a reusable secret to a database.
   */
  private static final Set<String> NEVER_COLLECTED =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  "authorization",
                  "authentication",
                  "www-authenticate",
                  "proxy-authenticate",
                  "proxy-authorization",
                  "cookie",
                  "set-cookie",
                  "x-api-key",
                  "x-auth-token",
                  "x-csrf-token",
                  "x-xsrf-token",
                  "x-amz-security-token")));

  public RequestContext create(HttpServletRequest request, String endpoint) {
    RequestContext.Builder builder =
        RequestContext.builder()
            .clientIp(extractClientIp(request))
            .userId(resolveUserId(request))
            .apiKey(resolveApiKey(request))
            .endpoint(endpoint)
            .method(request.getMethod());

    if (collectHeaders) {
      collectHeaders(builder, request);
    }

    return contextCustomizer.customize(builder, request).build();
  }

  /**
   * Extracts the client IP using the configured trust settings.
   *
   * @param request the HTTP request
   * @return the client IP address
   */
  public String extractClientIp(HttpServletRequest request) {
    return ClientIpExtractor.extract(request, clientIpHeader, trustClientIpHeader, trustedProxies);
  }
}
```

### 왜 팩토리로 분리했나: 애스펙트와 필터의 불일치

Javadoc의 첫 단락이 이유를 말합니다.

> Before it existed the aspect populated only the client IP, which silently demoted every
> {@code PER_USER}, {@code PER_API_KEY} and {@code CUSTOM} scope to per-IP limiting.

`@RateLimit` 애너테이션(AOP 모드)은 클라이언트 IP만 채웠습니다. 그래서 같은 룰셋을 필터에서는
`PER_USER`로, 애스펙트에서는 사실상 `PER_IP`로 강제했습니다. 설정은 하나인데 동작이 둘이었습니다.
이제 두 경로가 같은 팩토리를 씁니다.

### 신원의 출처: IdentitySource

```java
// RequestContextFactory.java - 실제 코드 Javadoc
/**
 * Resolves the user id according to the configured {@link IdentitySource}.
 *
 * <p>{@code PRINCIPAL} deliberately returns null for an unauthenticated request rather than
 * falling back to the header: a fallback would hand the choice of bucket straight back to the
 * caller, which is the bypass the setting exists to close.
 */
private String resolveUserId(HttpServletRequest request) { ... }
```

| `IdentitySource` | 신원 출처 | 신뢰도 |
|-----------------|----------|-------|
| `HEADERS` | `X-User-Id`, `X-API-Key` 헤더 | **클라이언트 입력** — 위조 가능 |
| `PRINCIPAL` | 인증된 principal | 신뢰 가능. 미인증 요청은 null |
| `PRINCIPAL_THEN_HEADERS` | principal 우선, 없으면 헤더 | 절충 |

```yaml
fluxgate:
  ratelimit:
    identity:
      source: PRINCIPAL           # PER_USER를 신뢰할 수 있게 만드는 설정
      user-id-header: X-User-Id   # source가 헤더를 볼 때만 쓰입니다
      api-key-header: X-API-Key
```

설정하지 않으면 **`PRINCIPAL`입니다** (0.4부터, 클래스패스와 무관).

```java
// FluxgateProperties.java - 실제 코드 Javadoc
/**
 * <p>Unset means {@code PRINCIPAL} (since 0.4): identity headers are ignored unless the
 * operator opts in with {@code HEADERS} or {@code PRINCIPAL_THEN_HEADERS}, which is logged as a
 * warning at startup. A request without an identity follows {@code missing-key-behavior}.
 */
private IdentitySource source;
```

헤더 신원은 신뢰할 수 있는 프록시가 헤더를 설정·제거하는 배포에서만 명시적으로 켭니다. 켜면 부팅 시
헤더 이름과 함께 WARN이 남고, 실제 적용된 값은 부팅 시 한 번 로그에 남습니다.

`PRINCIPAL`이 미인증 요청에 대해 **일부러 null을 반환**하는 것이 핵심입니다. 헤더로 폴백하면
호출자가 자기 버킷을 직접 고르게 되고, 그것이 바로 이 설정이 막으려는 우회입니다. null이 되면
`LimitScopeKeyResolver`의 `missing-key-behavior`가 결정합니다.

### 헤더 수집은 opt-in이고, 자격 증명은 절대 복사하지 않습니다

```yaml
fluxgate:
  ratelimit:
    collect-headers: false            # 기본값
    header-allowlist: [user-agent, referer]
```

`RequestContext`는 메트릭 레코더로 전달되고, 레코더는 그것을 **저장할 수 있습니다.** `Authorization`
헤더를 복사하면 재사용 가능한 비밀이 데이터베이스에 기록됩니다. 그래서 `NEVER_COLLECTED` 목록은
allowlist에 명시해도 무시됩니다.

### 클라이언트 IP 추출: 0.4의 보안 수정

0.3.x는 `X-Forwarded-For`를 **무조건 신뢰하고 첫 번째 IP를 취했습니다.**

```java
// 0.3.x 구조 (안전하지 않음)
String forwardedFor = request.getHeader(Headers.X_FORWARDED_FOR);
if (StringUtils.hasText(forwardedFor)) {
    String[] ips = forwardedFor.split(",");
    return ips[0].trim();        // ← 클라이언트가 직접 써 보낸 값
}
return request.getRemoteAddr();
```

`X-Forwarded-For`의 **왼쪽 끝**은 클라이언트가 직접 쓴 값입니다. 요청마다 다른 값을 넣으면
`PER_IP` 한도를 완전히 우회하면서, 동시에 위조한 값마다 Redis 버킷을 하나씩 만들어 메모리를
채울 수 있습니다.

0.3.x 호출 형태는 이제 `@Deprecated`입니다.

```java
// ClientIpExtractor.java - 실제 코드
/**
 * @deprecated unsafe: trusts {@code X-Forwarded-For} unconditionally, so any caller can rotate
 *     the header per request and bypass every {@code PER_IP} limit. Will be removed in the next
 *     major release. Use {@link #extract(HttpServletRequest, String, boolean, TrustedProxies)}
 *     and configure {@code fluxgate.ratelimit.trusted-proxies}.
 */
@Deprecated
public static String extract(HttpServletRequest request) {
  return extract(request, Headers.X_FORWARDED_FOR, true);
}
```

0.4가 쓰는 4-arg 형태의 선택 규칙:

```java
// ClientIpExtractor.java - 실제 코드
/**
 * Extracts the client IP address, honouring the forwarding header only for requests that arrived
 * through a trusted proxy.
 *
 * <p>Every line of the forwarding header is read ({@link HttpServletRequest#getHeaders}), in
 * order, and the hops are walked from the right (the hop closest to this server):
 *
 * <ul>
 *   <li>{@code trustClientIpHeader = false}: always use {@link
 *       HttpServletRequest#getRemoteAddr()}.
 *   <li>Non-empty {@code trustedProxies} that does not contain the remote address: the header is
 *       forged or the deployment is misconfigured, so use the remote address.
 *   <li>Non-empty {@code trustedProxies} containing the remote address: skip hops that are
 *       trusted proxies and return the first one that is not.
 *   <li>Empty {@code trustedProxies}: return the right-most hop (appended by the immediate proxy,
 *       harder to forge than the left-most client-supplied value). Nothing can be verified
 *       end-to-end, so the auto-configuration logs one startup WARN asking for a trusted-proxies
 *       list for multi-hop setups.
 * </ul>
 *
 * <p>R4: a hop is normalised before it is judged: {@code ip:port}, {@code [v6]} and {@code
 * [v6]:port} are reduced to the address. When the hop that would be returned is not a valid IPv4
 * or IPv6 literal of at most 45 characters, the extraction fails closed to the remote address
 * instead of skipping it, because everything to its left is client-controlled. The returned
 * address is canonical ({@link InetAddress#getHostAddress()}), so different spellings of one
 * address share one bucket key.
 *
 * @param request the HTTP request
 * @param clientIpHeader forwarding header to inspect when trusted
 * @param trustClientIpHeader whether forwarding headers are trusted
 * @param trustedProxies the proxies allowed to set the forwarding header (never null)
 * @return the client IP address
 */
public static String extract(
    HttpServletRequest request,
    String clientIpHeader,
    boolean trustClientIpHeader,
    TrustedProxies trustedProxies) {

  String remoteAddr = canonicalOrSelf(request.getRemoteAddr());
  if (!trustClientIpHeader) {
    return remoteAddr;
  }

  TrustedProxies proxies = trustedProxies != null ? trustedProxies : TrustedProxies.none();
  if (!proxies.isEmpty() && !proxies.contains(remoteAddr)) {
    return remoteAddr;
  }

  String headerName =
      StringUtils.hasText(clientIpHeader) ? clientIpHeader : Headers.X_FORWARDED_FOR;
  List<String> hops = forwardedHops(request, headerName);
  if (hops.isEmpty()) {
    return remoteAddr;
  }

  for (int i = hops.size() - 1; i >= 0; i--) {
    String candidate = canonicalHop(hops.get(i));
    if (candidate == null) {
      // Fail closed: an unparseable hop cannot be attributed, and every hop to its left is
      // under the client's control.
      return remoteAddr;
    }
    if (proxies.isEmpty() || !proxies.contains(candidate)) {
      return candidate;
    }
  }
  return remoteAddr;
}

/** Collects the comma-separated hops of every line of the header, in order. */
private static List<String> forwardedHops(HttpServletRequest request, String headerName) {
  List<String> hops = new ArrayList<>();
  Enumeration<String> lines = request.getHeaders(headerName);
  if (lines == null) {
    return hops;
  }
  while (lines.hasMoreElements()) {
    String line = lines.nextElement();
    if (line == null || line.trim().isEmpty()) {
      continue;
    }
    for (String hop : line.split(",", -1)) {
      hops.add(hop.trim());
    }
  }
  return hops;
}

// ... canonicalHop(): ip:port, [v6], [v6]:port를 정규화한 주소로 줄이고, IP 리터럴이 아니면 null
```

#### 오른쪽에서 왼쪽으로 걷는 이유

```
X-Forwarded-For: 1.2.3.4, 10.0.0.1, 10.0.0.2
                 └──────┘  └──────┘  └──────┘
                 클라이언트  프록시1   프록시2
                 가 쓴 값    이 추가   가 추가
                 (위조 가능) (신뢰)    (신뢰)
                    ↑                    ↑
              0.3.x가 취한 값       0.4가 시작하는 지점
```

각 프록시는 자기가 본 상대 주소를 **오른쪽에 덧붙입니다.** 따라서 오른쪽 끝은 바로 앞 프록시가
쓴 값이고, 왼쪽 끝은 클라이언트가 쓴 값입니다. 오른쪽에서 걸으며 **신뢰 목록에 없는 첫 항목**을
찾으면 그것이 실제 클라이언트입니다.

걷는 동안 지켜지는 세부:

- 헤더가 여러 줄로 와도 모든 줄을 순서대로 읽습니다(`request.getHeaders`). 첫 줄만 읽으면 뒤 줄에
  붙은 프록시 홉을 놓칩니다.
- 각 홉은 판정 전에 정규화됩니다. `ip:port`, `[v6]`, `[v6]:port`는 주소만 남기고, 결과는
  `InetAddress#getHostAddress()`의 정규형이라 같은 주소의 다른 표기가 한 버킷 키를 씁니다.
- 반환할 차례의 홉이 IP 리터럴이 아니면 **건너뛰지 않고 원격 주소로 닫힙니다(fail closed).** 그 왼쪽은
  모두 클라이언트가 쓴 값이므로, 건너뛰면 위조 값을 채택할 수 있습니다.

```yaml
fluxgate:
  ratelimit:
    trust-client-ip-header: true     # 기본값 false — 명시적으로 켜야 합니다
    client-ip-header: X-Forwarded-For
    trusted-proxies:
      - 10.0.0.0/8
      - 172.16.0.0/12
```

`trust-client-ip-header`의 기본값이 **false**입니다. 즉 아무 설정도 하지 않으면 FluxGate는
`getRemoteAddr()`만 봅니다. 프록시 뒤에 있다면 명시적으로 켜고, 그때 `trusted-proxies`도 함께
설정하세요. 기본값을 false로 둔 이유는 위조 가능한 헤더를 신뢰하는 것이 **선택**이어야 하기
때문입니다.

#### IP 리터럴 검증

모든 후보가 `TrustedProxies.isIpLiteral(candidate)`을 통과해야 합니다. 유효한 IPv4/IPv6 리터럴
(최대 45자)만 받아들이므로, 위조한 헤더로 **임의 문자열을 버킷 키에 주입할 수 없습니다.**
코어의 `KeyValueSanitizer`가 두 번째 방어선이지만, 그 전에 여기서 걸립니다.

#### trusted-proxies가 비었을 때

end-to-end 검증이 불가능하므로 **오른쪽 끝 유효 홉**을 취합니다. 왼쪽 끝보다 위조하기 어렵다는
상대적 개선일 뿐이므로, 자동 설정이 부팅 시 WARN을 한 번 남깁니다. 다중 홉 구성이라면
`trusted-proxies`를 설정하세요.

---

## 3. RequestContext

요청에 대한 모든 메타데이터를 담는 불변 객체입니다.

```
fluxgate-core/src/main/java/org/fluxgate/core/context/
└── RequestContext.java
```

```java
// RequestContext.java - 구조 요약 (접근자는 한 줄로 줄이고 Javadoc은 생략)
public final class RequestContext {

    private final String clientIp;    // 클라이언트 IP
    private final String userId;      // 사용자 ID (선택)
    private final String apiKey;      // API 키 (선택)
    private final String endpoint;    // 요청 경로: /api/users/123
    private final String method;      // HTTP 메서드: GET, POST, ...

    /** HTTP 요청 헤더 (예: User-Agent, Referer, X-Request-Id) */
    private final Map<String, String> headers;

    /** 사용자 정의 속성 */
    private final Map<String, Object> attributes;

    private RequestContext(Builder builder) {
        this.clientIp = builder.clientIp;
        this.userId = builder.userId;
        this.apiKey = builder.apiKey;
        this.endpoint = builder.endpoint;
        this.method = builder.method;
        Map<String, String> headerCopy = newHeaderMap();
        headerCopy.putAll(builder.headers);
        this.headers = Collections.unmodifiableMap(headerCopy);
        this.attributes = Collections.unmodifiableMap(new HashMap<>(builder.attributes));
    }

    /** Header names are case-insensitive; {@code CASE_INSENSITIVE_ORDER} is locale-independent. */
    private static Map<String, String> newHeaderMap() {
        return new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String clientIp;
        private String userId;
        private String apiKey;
        private String endpoint;
        private String method;
        private final Map<String, String> headers = newHeaderMap();
        private final Map<String, Object> attributes = new HashMap<>();

        public Builder clientIp(String clientIp) { this.clientIp = clientIp; return this; }
        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder endpoint(String endpoint) { this.endpoint = endpoint; return this; }
        public Builder method(String method) { this.method = method; return this; }

        public Builder header(String name, String value) { ... }
        public Builder headers(Map<String, String> headers) { ... }

        // Adds a custom attribute.
        public Builder attribute(String key, Object value) {
            this.attributes.put(key, value);
            return this;
        }

        // Adds multiple custom attributes at once.
        public Builder attributes(Map<String, Object> attributes) {
            if (attributes != null) {
                this.attributes.putAll(attributes);
            }
            return this;
        }

        // =========================================================================
        // Getters - for use in RequestContextCustomizer
        // =========================================================================

        public String getClientIp() { return clientIp; }
        public String getUserId() { return userId; }
        public String getApiKey() { return apiKey; }
        public String getEndpoint() { return endpoint; }
        public String getMethod() { return method; }
        public Map<String, String> getHeaders() { return headers; }
        public String getHeader(String name) { ... }
        public Object getAttribute(String key) { ... }

        public RequestContext build() {
            return new RequestContext(this);
        }
    }
}
```

### RequestContext 필드

| 필드           | 타입     | 설명          |
|--------------|--------|-------------|
| `clientIp`   | String | 클라이언트 IP 주소 |
| `userId`     | String | 사용자 ID (선택) |
| `apiKey`     | String | API 키 (선택)  |
| `endpoint`   | String | 요청 경로       |
| `method`     | String | HTTP 메서드    |
| `headers`    | Map    | HTTP 요청 헤더 (수집은 opt-in). 이름은 대소문자를 구분하지 않음: `getHeader("x-tier")`가 `X-Tier`로 넣은 헤더를 찾음 |
| `attributes` | Map    | 사용자 정의 속성   |

### 빌더에 getter가 있는 이유

주석이 명시합니다. **`Getters - for use in RequestContextCustomizer`.**

커스터마이저는 빌더를 받아 값을 덮어씁니다. "이미 채워진 값을 보고 판단"해야 할 때가 있으므로
빌더에 getter가 필요합니다.

```java
return (builder, request) -> {
    // 이미 해석된 IP가 사설망이면 다른 헤더를 신뢰
    if (builder.getClientIp() != null && builder.getClientIp().startsWith("10.")) {
        builder.clientIp(request.getHeader("CF-Connecting-IP"));
    }
    return builder;
};
```

`attributes`는 `CUSTOM` 스코프의 값이 오는 곳입니다. `LimitScopeKeyResolver`가
`attributes.get(rule.getKeyStrategyId())`로 읽습니다.

### 불변성

`build()`에서 두 Map을 `Collections.unmodifiableMap(new HashMap<>(...))`으로 복사합니다.
방어적 복사 + 읽기 전용 래핑입니다. 컨텍스트는 메트릭 레코더까지 전달되므로, 그 사이의 누구도
내용을 바꿀 수 없어야 합니다.

### HTTP 요청이 없는 호출

`@RateLimit`을 스케줄러나 메시지 리스너에 붙이면 HTTP 요청이 없습니다.

```java
// RequestContextFactory.java - 실제 코드
/**
 * Builds a minimal context for an invocation that has no HTTP request, such as a scheduled task
 * or a message listener calling a {@code @RateLimit} method.
 *
 * <p>Identity scopes have nothing to resolve here, so the key resolver falls back according to
 * {@code fluxgate.ratelimit.missing-key-behavior}.
 *
 * @param endpoint a stable identifier for the invocation, typically {@code Type.method}
 * @param method a stable identifier for the invocation kind, such as {@code INTERNAL}
 */
public RequestContext createForInvocation(String endpoint, String method) {
  return RequestContext.builder().endpoint(endpoint).method(method).build();
}
```

`clientIp`, `userId`, `apiKey`가 모두 null이므로, 신원 스코프는 `missing-key-behavior`에 따라
폴백하거나 거부됩니다. 이런 호출에는 `GLOBAL` 스코프 규칙이 자연스럽습니다.

---

## 4. RequestContextCustomizer

사용자가 구현하여 컨텍스트를 커스터마이징할 수 있는 인터페이스입니다.

```
fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/filter/
└── RequestContextCustomizer.java
```

```java
// RequestContextCustomizer.java - 실제 코드
@FunctionalInterface
public interface RequestContextCustomizer {

    /**
     * RequestContext 빌더를 커스터마이징합니다.
     *
     * 빌더에는 이미 요청에서 추출된 기본값이 채워져 있습니다.
     * 어떤 값이든 오버라이드하거나 커스텀 속성을 추가할 수 있습니다.
     *
     * @param builder 기본값이 채워진 빌더
     * @param request HTTP 요청
     * @return 커스터마이징된 빌더 (보통 같은 인스턴스)
     */
    RequestContext.Builder customize(RequestContext.Builder builder, HttpServletRequest request);

    /** 기본 no-op 커스터마이저 */
    static RequestContextCustomizer identity() {
        return (builder, request) -> builder;
    }

    /** 다른 커스터마이저와 체이닝 */
    default RequestContextCustomizer andThen(RequestContextCustomizer after) {
        return (builder, request) -> after.customize(this.customize(builder, request), request);
    }
}
```

### 0.4에서 달라진 위치: 필터가 아니라 팩토리가 호출합니다

0.3.x에서는 `FluxgateRateLimitFilter`가 커스터마이저를 직접 필드로 들고 있었습니다. 0.4에서는
`RequestContextFactory`가 들고 있고, **조립의 마지막 단계**로 호출합니다.

```java
// RequestContextFactory.java - 실제 코드
public RequestContext create(HttpServletRequest request, String endpoint) {
  RequestContext.Builder builder =
      RequestContext.builder()
          .clientIp(extractClientIp(request))
          .userId(resolveUserId(request))
          .apiKey(resolveApiKey(request))
          .endpoint(endpoint)
          .method(request.getMethod());

  if (collectHeaders) {
    collectHeaders(builder, request);
  }

  return contextCustomizer.customize(builder, request).build();
}
```

두 가지 결과가 따라옵니다.

**(1) 필터와 AOP 애스펙트가 같은 커스터마이저를 씁니다.** 0.3.x에서는 필터에만 적용되어,
`@RateLimit` 경로에서는 여러분의 커스터마이저가 실행되지 않았습니다.

**(2) 커스터마이저가 마지막(`customize(...)` → `build()`)이므로 무엇이든 덮어쓸 수 있습니다.**
`IdentitySource`나 `trusted-proxies` 설정이 정한 값도 최종적으로 여러분이 결정합니다.
반대로 말하면, `fluxgate.ratelimit.identity.source: PRINCIPAL`로 닫은 우회를 커스터마이저에서 헤더로 다시
열 수도 있으니 주의하세요.

```java
// 위험: identity.source=PRINCIPAL의 의도를 무너뜨립니다
return (builder, request) -> {
    builder.userId(request.getHeader("X-User-Id"));  // 클라이언트가 자기 버킷을 고름
    return builder;
};
```

### 등록

```java
@Bean
public RequestContextCustomizer requestContextCustomizer() { ... }
```

`RequestContextCustomizer` 빈이 있으면 자동 설정이 그것을 `RequestContextFactory`에 주입합니다.
없으면 `identity()`(no-op)가 쓰입니다.

---

### @FunctionalInterface란?

`RequestContextCustomizer` 코드를 보면 **구현부가 없습니다.** `customize()` 메서드의 본문이 없죠. 이게 정상입니다!

**인터페이스는 "계약서"입니다.** "이런 형태의 메서드를 구현해라"라고 약속만 정의한 것이지, 실제 동작은 **사용하는 쪽에서 구현**합니다.

`@FunctionalInterface`는 **추상 메서드가 딱 1개인 인터페이스**를 의미합니다. 이런 인터페이스는 **람다 표현식**으로 간결하게 구현할 수 있습니다.

---

### 세 가지 구현 방식 비교

같은 기능을 구현하는 세 가지 방법을 보여드립니다. **모두 동일하게 동작합니다.**

**방식 1: 람다 표현식 (가장 간결)**

```java
@Bean
public RequestContextCustomizer requestContextCustomizer() {
    return (builder, request) -> {
        String tenantId = request.getHeader("X-Tenant-Id");
        if (tenantId != null) {
            builder.attribute("tenantId", tenantId);
        }
        return builder;
    };
}
```

- `(builder, request) -> { ... }` 부분이 `customize()` 메서드의 구현부입니다
- 파라미터 타입은 컴파일러가 추론합니다

**방식 2: 익명 클래스 (람다의 원래 모습)**

```java
@Bean
public RequestContextCustomizer requestContextCustomizer() {
    return new RequestContextCustomizer() {
        @Override
        public RequestContext.Builder customize(
                RequestContext.Builder builder,
                HttpServletRequest request) {

            String tenantId = request.getHeader("X-Tenant-Id");
            if (tenantId != null) {
                builder.attribute("tenantId", tenantId);
            }
            return builder;
        }
    };
}
```

- 방식 1의 람다는 이 익명 클래스를 **축약한 문법**입니다
- Java 8 이전에는 이렇게 작성했습니다

**방식 3: 별도 클래스로 구현 (복잡한 로직에 적합)**

```java
// 별도 파일: TenantContextCustomizer.java
@Component
public class TenantContextCustomizer implements RequestContextCustomizer {

    private final JwtParser jwtParser;  // 의존성 주입 가능

    public TenantContextCustomizer(JwtParser jwtParser) {
        this.jwtParser = jwtParser;
    }

    @Override
    public RequestContext.Builder customize(
            RequestContext.Builder builder,
            HttpServletRequest request) {

        // JWT 파싱 같은 복잡한 로직
        String token = request.getHeader("Authorization");
        if (token != null) {
            Claims claims = jwtParser.parse(token);
            builder.attribute("tenantId", claims.getTenantId());
            builder.attribute("role", claims.getRole());
        }

        return builder;
    }
}
```

- 복잡한 로직이나 의존성 주입이 필요할 때 사용
- 테스트하기 쉬움

> JWT에서 신원을 꺼내는 경우, `userId`는 커스터마이저 대신
> `fluxgate.ratelimit.identity.source: PRINCIPAL`로 principal에서 받는 편이 안전합니다.
> 커스터마이저는 **추가 속성**(테넌트, 등급 등)을 채우는 데 쓰세요.

---

### 언제 어떤 방식을 쓰나요?

| 상황                          | 추천 방식  |
|-----------------------------|--------|
| 간단한 헤더 추출 (1~5줄)            | 람다 표현식 |
| 여러 곳에서 재사용                  | 별도 클래스 |
| 다른 Bean 주입 필요 (JwtParser 등) | 별도 클래스 |
| 단위 테스트 작성 필요                | 별도 클래스 |

---

### 전체 흐름 정리

```
1. 당신이 작성한 코드 (람다든 클래스든)
   +----------------------------------------+
   | return (builder, request) -> {         |
   |     builder.attribute("tenantId",      |
   |         request.getHeader              |
   |             ("X-Tenant-Id"));          |
   |     return builder;                    |
   | };                                     |
   +----------------------------------------+
                     |
                     v
2. Spring이 Bean으로 등록
                     |
                     v
3. RequestContextFactory가 주입받음
   +----------------------------------------+
   | public class RequestContextFactory {   |
   |     private final RequestContext       |
   |         Customizer contextCustomizer;  |
   | }                                      |
   +----------------------------------------+
                     |
                     v
4. 필터와 AOP 애스펙트가 같은 팩토리를 사용
   +----------------------------------------+
   | FluxgateRateLimitFilter                |
   |   → contextFactory.create(req, path)   |
   | RateLimitAspect                        |
   |   → contextFactory.create(req, path)   |
   +----------------------------------------+
                     |
                     v
5. 조립의 마지막 단계로 호출됨
   +----------------------------------------+
   | return contextCustomizer               |
   |     .customize(builder, request)       |
   |     .build();   // 당신 코드가 마지막    |
   +----------------------------------------+
```

---

### Strategy 패턴과의 관계

**거의 같습니다!** `@FunctionalInterface`는 Strategy 패턴을 간결하게 구현하는 방법입니다.

```
전통적인 Strategy 패턴:
- 인터페이스 정의
- 구현 클래스 여러 개 작성
- 클래스 파일이 늘어남

@FunctionalInterface + 람다:
- 인터페이스 정의
- 람다로 즉석에서 구현
- 코드가 간결해짐
```

본질은 같고, **표현 방식만 간결해진 것**입니다.

`andThen`은 여기서 한 걸음 더 나갑니다. 두 전략을 합성해 하나로 만듭니다.

```java
// RequestContextCustomizer.java - 실제 코드
default RequestContextCustomizer andThen(RequestContextCustomizer after) {
    return (builder, request) -> after.customize(this.customize(builder, request), request);
}
```

```java
@Bean
public RequestContextCustomizer requestContextCustomizer(
        TenantContextCustomizer tenant, GradeContextCustomizer grade) {
    return tenant.andThen(grade);   // 테넌트 먼저, 그 다음 등급
}
```

---

### 사용자 구현 예시 (전체 코드)

```java
// 사용자가 구현하는 커스터마이저
@Configuration
public class RateLimitConfig {

    /**
     * RequestContext 커스터마이저를 Bean으로 등록합니다.
     *
     * 이 Bean은 RequestContextFactory에 자동 주입되어,
     * 필터와 AOP 애스펙트 양쪽에서 모든 요청마다 호출됩니다.
     */
    @Bean
    public RequestContextCustomizer requestContextCustomizer() {
        return (builder, request) -> {

            // 1. 테넌트 ID를 CUSTOM 스코프용 속성으로 추가
            String tenantId = request.getHeader("X-Tenant-Id");
            if (tenantId != null) {
                builder.attribute("tenantId", tenantId);
            }

            // 2. 요금제 등급을 속성으로 추가 (규칙 선택이나 메트릭 태그에 활용)
            String grade = request.getHeader("X-Plan-Grade");
            if (grade != null) {
                builder.attribute("planGrade", grade);
            }

            return builder;
        };
    }
}
```

`tenantId` 속성을 쓰려면 규칙의 `keyStrategyId`를 그 이름으로 맞춥니다.

```java
RateLimitRule.builder("per-tenant")
    .scope(LimitScope.CUSTOM)
    .keyStrategyId("tenantId")      // attributes.get("tenantId")를 읽습니다
    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 1000).build())
    .build();
```

해석된 키는 `custom:` 접두사가 붙어 `custom:<tenantId>`가 되고, 다른 스코프의 버킷과 절대
겹치지 않습니다.

> **클라이언트 IP를 덮어쓰는 경우.** Cloudflare 뒤에 있다면 커스터마이저에서 `CF-Connecting-IP`를
> 읽는 대신 `fluxgate.ratelimit.client-ip-header: CF-Connecting-IP`와 `trusted-proxies`를
> 설정하세요. 그러면 `ClientIpExtractor`의 신뢰 검증과 IP 리터럴 검증을 함께 받습니다.
> 커스터마이저에서 `builder.clientIp(request.getHeader(...))`로 직접 넣으면 그 두 방어를
> 우회합니다.

---

## 관련 문서

- [Handler Layer Deep Dive](handler-layer.ko.md)
- [Engine Layer Deep Dive](engine-layer.ko.md) - KeyResolver, 스코프 접두사
- [RequestContext 커스터마이징](../../customization/request-context.ko.md)
- [아키텍처 개요](../README.ko.md)
