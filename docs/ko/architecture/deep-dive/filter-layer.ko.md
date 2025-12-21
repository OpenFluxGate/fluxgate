# Filter Layer Deep Dive

이 문서는 FluxGate의 Filter Layer를 **실제 소스코드**와 함께 상세히 설명합니다.

[< 아키텍처 개요로 돌아가기](../README.ko.md)

---

## 목차

1. [FluxgateRateLimitFilter](#1-fluxgateratelimitfilter)
2. [RequestContext](#2-requestcontext)
3. [RequestContextCustomizer](#3-requestcontextcustomizer)

---

## 1. FluxgateRateLimitFilter

HTTP 요청을 가로채고 Rate Limiting을 적용하는 진입점입니다.

```
fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/filter/
└── FluxgateRateLimitFilter.java
```

```java
// FluxgateRateLimitFilter.java - 실제 코드
public class FluxgateRateLimitFilter extends OncePerRequestFilter {

    private final FluxgateRateLimitHandler handler;
    private final String ruleSetId;
    private final String[] includePatterns;
    private final String[] excludePatterns;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    // WAIT_FOR_REFILL 설정
    private final boolean waitForRefillEnabled;
    private final long maxWaitTimeMs;
    private final Semaphore waitSemaphore;

    // RequestContext 커스터마이저
    private final RequestContextCustomizer contextCustomizer;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // (1) 제외 패턴 체크 (예: /health, /actuator/*)
        if (shouldExclude(path)) {
            log.debug("Path excluded from rate limiting: {}", path);
            filterChain.doFilter(request, response);
            return;
        }

        // (2) 포함 패턴 체크
        if (!shouldInclude(path)) {
            log.debug("Path not included in rate limiting: {}", path);
            filterChain.doFilter(request, response);
            return;
        }

        // (3) ruleSetId 유효성 체크
        if (!StringUtils.hasText(ruleSetId)) {
            log.warn("No rule set ID configured, skipping rate limiting");
            filterChain.doFilter(request, response);
            return;
        }

        // (4) RequestContext 빌드
        RequestContext context = buildRequestContext(request);

        try {
            // (5) Handler를 통해 Rate Limit 체크
            RateLimitResponse result = handler.tryConsume(context, ruleSetId);

            // (6) Rate Limit 헤더 추가
            addRateLimitHeaders(response, result);

            if (result.isAllowed()) {
                filterChain.doFilter(request, response);  // 허용 -> 다음 필터로
            } else if (shouldWaitForRefill(result)) {
                // WAIT_FOR_REFILL 정책: 토큰 리필 대기 후 재시도
                handleWaitForRefill(request, response, filterChain, context, result);
            } else {
                handleRateLimitExceeded(response, result);  // 거부 -> 429 응답
            }
        } catch (Exception e) {
            log.error("Error during rate limiting, allowing request", e);
            // Fail open: Rate Limiter 오류 시 요청 허용
            filterChain.doFilter(request, response);
        }
    }

    /** RequestContext 빌드 */
    private RequestContext buildRequestContext(HttpServletRequest request) {
        String clientIp = extractClientIp(request);
        String userId = request.getHeader(Headers.USER_ID);
        String apiKey = request.getHeader(Headers.API_KEY);

        // 기본 컨텍스트 빌더 생성
        RequestContext.Builder builder = RequestContext.builder()
                .clientIp(clientIp)
                .userId(userId)
                .apiKey(apiKey)
                .endpoint(request.getRequestURI())
                .method(request.getMethod());

        // HTTP 헤더 수집
        collectHeaders(builder, request);

        // 커스터마이저 적용 (사용자 정의 로직)
        builder = contextCustomizer.customize(builder, request);

        return builder.build();
    }

    /** 클라이언트 IP 추출 (X-Forwarded-For 지원) */
    private String extractClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader(Headers.X_FORWARDED_FOR);
        if (StringUtils.hasText(forwardedFor)) {
            // 여러 IP가 있을 수 있으므로 첫 번째 IP 사용
            String[] ips = forwardedFor.split(",");
            return ips[0].trim();
        }
        return request.getRemoteAddr();
    }
}
```

### 주요 특징

| 구성 요소                      | 역할                          |
|----------------------------|-----------------------------|
| `FluxgateRateLimitHandler` | Rate Limit 체크 위임            |
| `RequestContextCustomizer` | 사용자 정의 컨텍스트 커스터마이징          |
| `AntPathMatcher`           | URL 패턴 매칭 (include/exclude) |
| `Semaphore`                | WAIT_FOR_REFILL 동시 대기 요청 제한 |

### WAIT_FOR_REFILL 처리

```java
/** WAIT_FOR_REFILL 정책 처리 */
private void handleWaitForRefill(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain,
        RequestContext context,
        RateLimitResponse result) throws ServletException, IOException {

    long waitTimeMs = result.getRetryAfterMillis();

    // 최대 대기 시간 초과 체크
    if (waitTimeMs > maxWaitTimeMs) {
        log.info("Wait time {} ms exceeds max {} ms, rejecting", waitTimeMs, maxWaitTimeMs);
        handleRateLimitExceeded(response, result);
        return;
    }

    // Semaphore로 동시 대기 요청 수 제한
    if (!waitSemaphore.tryAcquire()) {
        log.info("Too many concurrent waits, rejecting");
        handleRateLimitExceeded(response, result);
        return;
    }

    try {
        // 토큰 리필 대기
        TimeUnit.MILLISECONDS.sleep(waitTimeMs);

        // 대기 후 재시도
        RateLimitResponse retryResult = handler.tryConsume(context, ruleSetId);
        addRateLimitHeaders(response, retryResult);

        if (retryResult.isAllowed()) {
            filterChain.doFilter(request, response);
        } else {
            handleRateLimitExceeded(response, retryResult);
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        handleRateLimitExceeded(response, result);
    } finally {
        waitSemaphore.release();
    }
}
```

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
semaphore.

trySetPermits(100);  // 클러스터 전체에서 100개
```

**옵션 2: WAIT_FOR_REFILL 비활성화 (권장)**

분산 환경에서는 `WAIT_FOR_REFILL` 대신 즉시 429 반환 후, 클라이언트가 `Retry-After` 헤더를 보고 재시도하도록 설계합니다.

```yaml
fluxgate:
  ratelimit:
    wait-for-refill:
      enabled: false  # 분산 환경에서는 비활성화 권장
```

**옵션 3: 현재 방식 유지 (Pod별 보호)**

각 Pod가 자신의 스레드 풀만 보호합니다. 전체 대기 수는 `maxConcurrentWaits * Pod 수`가 됩니다. 이 방식은 개별 Pod의 안정성은 보장하지만, 클러스터 전체의 동시 대기 요청 수는 제어하지
않습니다.

---

## 2. RequestContext

요청에 대한 모든 메타데이터를 담는 불변 객체입니다.

```
fluxgate-core/src/main/java/org/fluxgate/core/context/
└── RequestContext.java
```

```java
// RequestContext.java - 실제 코드
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
        this.headers = Collections.unmodifiableMap(new HashMap<>(builder.headers));
        this.attributes = Collections.unmodifiableMap(new HashMap<>(builder.attributes));
    }

    // Getter 메서드들...

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String clientIp;
        private String userId;
        private String apiKey;
        private String endpoint;
        private String method;
        private final Map<String, String> headers = new HashMap<>();
        private final Map<String, Object> attributes = new HashMap<>();

        public Builder clientIp(String clientIp) {
            this.clientIp = clientIp;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder header(String name, String value) {
            if (value != null) {
                this.headers.put(name, value);
            }
            return this;
        }

        public Builder attribute(String key, Object value) {
            this.attributes.put(key, value);
            return this;
        }

        // Getter 메서드들 (RequestContextCustomizer에서 사용)
        public String getClientIp() {
            return clientIp;
        }

        public String getUserId() {
            return userId;
        }

        public String getHeader(String name) {
            return headers.get(name);
        }

        public Object getAttribute(String key) {
            return attributes.get(key);
        }

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
| `headers`    | Map    | HTTP 요청 헤더  |
| `attributes` | Map    | 사용자 정의 속성   |

---

## 3. RequestContextCustomizer

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
        String userId = request.getHeader("X-User-Id");
        if (userId != null) {
            builder.userId(userId);
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

            String userId = request.getHeader("X-User-Id");
            if (userId != null) {
                builder.userId(userId);
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

        String userId = request.getHeader("X-User-Id");
        if (userId != null) {
            builder.userId(userId);
        }

        // JWT 파싱 같은 복잡한 로직
        String token = request.getHeader("Authorization");
        if (token != null) {
            Claims claims = jwtParser.parse(token);
            builder.attribute("role", claims.getRole());
        }

        return builder;
    }
}
```

- 복잡한 로직이나 의존성 주입이 필요할 때 사용
- 테스트하기 쉬움

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
   |     builder.userId(request.getHeader   |
   |         ("X-User-Id"));                |
   |     return builder;                    |
   | };                                     |
   +----------------------------------------+
                     |
                     v
2. Spring이 Bean으로 등록
                     |
                     v
3. FluxgateRateLimitFilter가 주입받음
   +----------------------------------------+
   | public class FluxgateRateLimitFilter { |
   |     private final RequestContext       |
   |         Customizer contextCustomizer;  |
   | }                                      |
   +----------------------------------------+
                     |
                     v
4. HTTP 요청이 들어올 때마다 호출됨
   +----------------------------------------+
   | builder = contextCustomizer.customize( |
   |     builder, request);  // 당신 코드 실행 |
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

---

### 사용자 구현 예시 (전체 코드)

```java
// 사용자가 구현하는 커스터마이저
@Configuration
public class RateLimitConfig {

    /**
     * RequestContext 커스터마이저를 Bean으로 등록합니다.
     *
     * 이 Bean은 FluxgateRateLimitFilter에 자동 주입되어
     * 모든 HTTP 요청마다 호출됩니다.
     */
    @Bean
    public RequestContextCustomizer requestContextCustomizer() {
        return (builder, request) -> {

            // 1. 테넌트 ID 추출
            String tenantId = request.getHeader("X-Tenant-Id");
            if (tenantId != null) {
                builder.attribute("tenantId", tenantId);
            }

            // 2. Cloudflare 뒤에 있는 경우 실제 IP 추출
            String cfIp = request.getHeader("CF-Connecting-IP");
            if (cfIp != null) {
                builder.clientIp(cfIp);
            }

            // 3. 사용자 ID 추출
            String userId = request.getHeader("X-User-Id");
            if (userId != null) {
                builder.userId(userId);
            }

            return builder;
        };
    }
}
```

---

## 관련 문서

- [Handler Layer Deep Dive](handler-layer.ko.md)
- [아키텍처 개요](../README.ko.md)
