# Handler Layer Deep Dive

이 문서는 FluxGate의 Handler Layer를 **실제 소스코드**와 함께 상세히 설명합니다.

[< 아키텍처 개요로 돌아가기](../README.ko.md)

---

## 목차

1. [FluxgateRateLimitHandler 인터페이스](#1-fluxgateratelimithandler-인터페이스)
2. [RateLimitResponse](#2-ratelimitresponse)
3. [HttpRateLimitHandler (HTTP API 모드)](#3-httpratelimithandler-http-api-모드)
4. [RedisRateLimitHandler (직접 Redis 모드)](#4-redisratelimithandler-직접-redis-모드)

---

## 1. FluxgateRateLimitHandler 인터페이스

```
fluxgate-core/src/main/java/org/fluxgate/core/handler/
└── FluxgateRateLimitHandler.java
```

Rate Limit 처리를 위한 핵심 인터페이스입니다. 다양한 구현 전략을 지원합니다.

```java
// FluxgateRateLimitHandler.java - 실제 코드
public interface FluxgateRateLimitHandler {

  /**
   * Rate Limit 버킷에서 토큰 소비를 시도합니다.
   *
   * @param context 클라이언트 정보를 담은 요청 컨텍스트 (IP, userId, endpoint 등)
   * @param ruleSetId 적용할 규칙 세트 ID
   * @return 허용 여부와 메타데이터를 담은 응답
   */
  RateLimitResponse tryConsume(RequestContext context, String ruleSetId);

  /**
   * 항상 요청을 허용하는 기본 핸들러.
   * 핸들러가 설정되지 않았을 때 폴백으로 사용됩니다.
   */
  FluxgateRateLimitHandler ALLOW_ALL = (context, ruleSetId) -> RateLimitResponse.allowed(-1, 0);
}
```

### 구현 전략

| 구현 | 설명 | 사용 시나리오 |
|-----|------|-------------|
| `HttpRateLimitHandler` | 외부 FluxGate API 서버 호출 | 분산 배포, 마이크로서비스 |
| `RedisRateLimitHandler` | Redis에 직접 접근 | 단일 서비스, 낮은 레이턴시 필요 |
| `ALLOW_ALL` | 항상 허용 (폴백) | 테스트, 설정 누락 시 |

---

## 2. RateLimitResponse

```
fluxgate-core/src/main/java/org/fluxgate/core/handler/
└── RateLimitResponse.java
```

Rate Limit 체크 결과를 담는 불변 객체입니다.

```java
// RateLimitResponse.java - 실제 코드
public final class RateLimitResponse {

  private final boolean allowed;
  private final long remainingTokens;
  private final long retryAfterMillis;
  private final OnLimitExceedPolicy onLimitExceedPolicy;

  private RateLimitResponse(
      boolean allowed,
      long remainingTokens,
      long retryAfterMillis,
      OnLimitExceedPolicy onLimitExceedPolicy) {
    this.allowed = allowed;
    this.remainingTokens = remainingTokens;
    this.retryAfterMillis = retryAfterMillis;
    this.onLimitExceedPolicy = onLimitExceedPolicy;
  }

  /** 허용 응답 생성 */
  public static RateLimitResponse allowed(long remainingTokens, long retryAfterMillis) {
    return new RateLimitResponse(true, remainingTokens, retryAfterMillis, null);
  }

  /** 거부 응답 생성 (기본 REJECT_REQUEST 정책) */
  public static RateLimitResponse rejected(long retryAfterMillis) {
    return new RateLimitResponse(false, 0, retryAfterMillis, OnLimitExceedPolicy.REJECT_REQUEST);
  }

  /** 거부 응답 생성 (특정 정책 지정) */
  public static RateLimitResponse rejected(long retryAfterMillis, OnLimitExceedPolicy policy) {
    return new RateLimitResponse(false, 0, retryAfterMillis, policy);
  }

  public boolean isAllowed() { return allowed; }
  public long getRemainingTokens() { return remainingTokens; }
  public long getRetryAfterMillis() { return retryAfterMillis; }
  public OnLimitExceedPolicy getOnLimitExceedPolicy() { return onLimitExceedPolicy; }

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
| `remainingTokens` | long | 남은 토큰 수 (-1이면 알 수 없음) |
| `retryAfterMillis` | long | 재시도까지 대기 시간 (ms) |
| `onLimitExceedPolicy` | OnLimitExceedPolicy | 한도 초과 시 정책 |

### OnLimitExceedPolicy

| 정책 | 설명 |
|-----|------|
| `REJECT_REQUEST` | 즉시 429 응답 반환 |
| `WAIT_FOR_REFILL` | 토큰 리필까지 대기 후 재시도 |

---

## 3. HttpRateLimitHandler (HTTP API 모드)

```
fluxgate-samples/fluxgate-sample-filter/src/main/java/.../handler/
└── HttpRateLimitHandler.java
```

원격 FluxGate API 서버를 호출하는 핸들러입니다.

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
|  HttpRateLimit    |         |  Redis + RateLimiter      |
|  Handler          |         |                           |
+-------------------+         +---------------------------+
```

### 설정

```yaml
fluxgate:
  api:
    url: http://localhost:8080  # FluxGate API 서버 URL
```

---

## 4. RedisRateLimitHandler (직접 Redis 모드)

```
fluxgate-samples/fluxgate-sample-filter/src/main/java/.../handler/
└── RedisRateLimitHandler.java
```

Redis에 직접 접근하여 Rate Limiting을 수행하는 핸들러입니다.

```java
// RedisRateLimitHandler.java - 샘플 코드
@Component
public class RedisRateLimitHandler implements FluxgateRateLimitHandler {

  private final RateLimiter rateLimiter;
  private final RateLimitRuleSetProvider ruleSetProvider;

  public RedisRateLimitHandler(
      RateLimiter rateLimiter,
      RateLimitRuleSetProvider ruleSetProvider) {
    this.rateLimiter = rateLimiter;
    this.ruleSetProvider = ruleSetProvider;
    log.info("RedisRateLimitHandler initialized with direct Redis access");
  }

  @Override
  public RateLimitResponse tryConsume(RequestContext context, String ruleSetId) {
    // (1) RuleSet 조회
    Optional<RateLimitRuleSet> ruleSetOpt = ruleSetProvider.findById(ruleSetId);

    if (ruleSetOpt.isEmpty()) {
      log.warn("RuleSet not found: {}, allowing request", ruleSetId);
      return RateLimitResponse.allowed(-1, 0);
    }

    RateLimitRuleSet ruleSet = ruleSetOpt.get();

    // (2) Rate Limiting 실행
    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    // (3) RateLimitResponse로 변환
    if (result.isAllowed()) {
      return RateLimitResponse.allowed(
          result.getRemainingTokens(),
          result.getNanosToWaitForRefill() / 1_000_000
      );
    } else {
      return RateLimitResponse.rejected(
          result.getNanosToWaitForRefill() / 1_000_000
      );
    }
  }
}
```

### 사용 시나리오

```
+-------------------+         +---------------------------+
|  Application      |         |  Redis                    |
|                   | ------> |  (Port 6379)              |
|  RedisRateLimit   |  직접   |                           |
|  Handler          |  연결   |  Token Bucket Store       |
+-------------------+         +---------------------------+
```

### 설정

```yaml
fluxgate:
  redis:
    enabled: true
    uri: redis://localhost:6379
```

---

## Handler 선택 가이드

| 상황 | 추천 Handler | 이유 |
|-----|-------------|------|
| 마이크로서비스 분산 배포 | `HttpRateLimitHandler` | 중앙 집중식 Rate Limiting |
| 단일 서비스 | `RedisRateLimitHandler` | 낮은 레이턴시, 네트워크 홉 감소 |
| 개발/테스트 | `ALLOW_ALL` | Rate Limiting 비활성화 |

---

## 관련 문서

- [Filter Layer Deep Dive](filter-layer.ko.md)
- [Engine Layer Deep Dive](engine-layer.ko.md)
- [아키텍처 개요](../README.ko.md)
