# @RateLimit 애노테이션 가이드

`@RateLimit` 애노테이션은 Spring AOP를 사용해 개별 메서드나 클래스에 Rate Limiting을 적용합니다.
비용이 많이 드는 내보내기 엔드포인트에는 더 엄격한 제한을 두고, 일반 조회에는 기본 제한을 유지하는
등 **메서드 단위** 제어가 필요할 때 적합합니다. URL 패턴에 걸리는 모든 요청에 일괄 제한을 걸고
싶다면 서블릿 필터(`@EnableFluxgateFilter`)를 사용하세요.

## 목차

- [Aspect 활성화](#aspect-활성화)
- [애노테이션 속성](#애노테이션-속성)
- [예제](#예제)
- [거부 처리: `throwOnReject`와 `@RestControllerAdvice`](#거부-처리)
- [비웹 사용 (스케줄 작업, 메시지 리스너)](#비웹-사용)
- [필터 vs. Aspect 선택 기준](#필터-vs-aspect)

---

## Aspect 활성화

메인 애플리케이션 클래스나 `@Configuration` 클래스에 `@EnableFluxgateAspect`를 추가합니다:

```java
@SpringBootApplication
@EnableFluxgateAspect
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

`@EnableFluxgateAspect`는 `FluxgateAopAutoConfiguration`을 임포트하며, `RateLimitAspect` 빈을
등록합니다. Aspect는 `@RateLimit`이 붙은 모든 메서드를 가로채어 메서드 실행 전에 설정된 규칙을
적용합니다.

같은 클래스에 `@EnableFluxgateFilter`와 `@EnableFluxgateAspect`를 함께 사용하면 일괄 HTTP 필터와
메서드별 오버라이드를 조합할 수 있습니다.

**사전 요구사항** — 필터와 동일한 자동 설정 빈이 필요합니다:
- `FluxgateRateLimitHandler` 빈 (`RateLimiter` + `RateLimitRuleSetProvider` 존재 시 자동 등록)
- 선택적으로 요청 컨텍스트 보강을 위한 `RequestContextCustomizer` 빈

---

## 애노테이션 속성

### `ruleSetId` (String, 기본값 `""`)

적용할 룰셋의 ID. 빈 값이면 properties의 `fluxgate.ratelimit.default-rule-set-id`, 그 다음
`@EnableFluxgateAspect`의 `ruleSetId` 속성 순으로 폴백합니다.

```java
@RateLimit(ruleSetId = "export-rules")
@GetMapping("/api/export")
public ResponseEntity<byte[]> export() { ... }
```

### `permits` (long, 기본값 `1`)

이 호출이 소비할 토큰 수. `1` 미만 값은 `1`로 처리됩니다.

비용이 많이 드는 작업의 경우 단일 호출을 여러 건으로 계산하도록 `1`보다 큰 값을 사용합니다:

```java
@RateLimit(ruleSetId = "api-rules", permits = 10)
@PostMapping("/api/bulk-export")
public ResponseEntity<byte[]> bulkExport() { ... }
```

설정된 `FluxgateRateLimitHandler`가 가중 permits를 지원해야 합니다. 기본
`EngineBackedRateLimitHandler`는 `permits > 1`일 때만 3인수 `tryConsume` 오버로드를 호출합니다.
핸들러가 해당 오버로드를 오버라이드하지 않으면 `UnsupportedOperationException`이 발생합니다.

### `waitForRefill` (boolean, 기본값 `false`)

`true`이면 거부된 요청이 즉시 실패하는 대신 `maxWaitTimeMs` 밀리초까지 토큰이 보충되길 기다립니다
(스레드 차단).

`application.yml`에서 `fluxgate.ratelimit.wait-for-refill.enabled=true`가 필요하거나,
규칙의 `onLimitExceedPolicy`가 `WAIT_FOR_REFILL`이어야 합니다.

```java
@RateLimit(ruleSetId = "premium-rules", waitForRefill = true, maxWaitTimeMs = 3000)
@PostMapping("/api/submit")
public Response submit(@RequestParam String userId) { ... }
```

### `maxWaitTimeMs` (long, 기본값 `5000`)

`waitForRefill = true`일 때 스레드가 대기할 최대 시간(밀리초). `waitForRefill`이 `false`이면
효과 없음. `fluxgate.ratelimit.wait-for-refill.max-wait-time-ms` 프로퍼티가 전역 상한을 설정하고,
애노테이션 속성은 메서드별 설정입니다.

### `throwOnReject` (boolean, 기본값 `false`)

`false`(기본값)이면 Aspect가 필터와 동일한 `RateLimitResponseWriter`를 사용해 HTTP 429 응답을
직접 작성합니다. `true`이면 `RateLimitExceededException`을 던져서 `@ControllerAdvice`가 애플리케이션
자체 형식으로 오류를 렌더링하게 합니다.

서블릿 응답이 없는 경우(`@Scheduled` 메서드나 메시지 리스너 등)에는 이 플래그와 무관하게
예외가 항상 던져집니다. [비웹 사용](#비웹-사용)을 참고하세요.

### `maxConcurrentWaits` (int, 기본값 `100`) — deprecated

**0.4.0 이후 무시됩니다.** 대기 세마포어가 Aspect 범위로 이전되었기 때문입니다(호출별 세마포어는
아무것도 제한하지 않았습니다). 대신 `fluxgate.ratelimit.wait-for-refill.max-concurrent-waits`를
설정하세요.

---

## 예제

### 컨트롤러 메서드에 IP 기반 제한

```java
@RestController
public class ApiController {

    @RateLimit(ruleSetId = "api-rules")
    @GetMapping("/api/search")
    public List<Result> search(@RequestParam String query) {
        return searchService.search(query);
    }
}
```

### 클래스 레벨 애노테이션 (모든 public 메서드가 제한 공유)

```java
@RestController
@RateLimit(ruleSetId = "admin-rules")
@RequestMapping("/admin")
public class AdminController {

    @GetMapping("/users")
    public List<User> listUsers() { ... }   // rate-limited

    @PostMapping("/users")
    public User createUser(@RequestBody UserDto dto) { ... }  // rate-limited
}
```

### 비용 많은 작업에 가중 permits

```java
@RestController
public class ReportController {

    // 단일 내보내기가 쿼터 상 일반 요청 10건으로 계산
    @RateLimit(ruleSetId = "api-rules", permits = 10)
    @GetMapping("/api/report/full")
    public ResponseEntity<byte[]> fullReport() { ... }
}
```

---

## 거부 처리

### 기본 동작 (`throwOnReject = false`)

Aspect가 RFC 9457 problem body와 표준 Rate Limit 헤더를 서블릿 응답에 직접 기록합니다 — 필터와
동일한 형식:

```
HTTP/1.1 429
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: <epoch-seconds>
RateLimit-Limit: 100
RateLimit-Remaining: 0
RateLimit-Reset: 30
RateLimit-Policy: 100;w=60
Retry-After: 30
Content-Type: application/problem+json;charset=UTF-8

{"type":"about:blank","title":"Too Many Requests","status":429,
 "detail":"Rate limit exceeded, retry after 30 seconds","retryAfterMillis":30000}
```

### 커스텀 오류 형식 (`throwOnReject = true`)

애노테이션에 `throwOnReject = true`를 추가하고 `RateLimitExceededException`을 잡는
`@RestControllerAdvice`를 등록하세요. 아래 예제는
`fluxgate-samples/fluxgate-sample-standalone-java21`에서 가져온 것입니다:

```java
// 컨트롤러
@RateLimit(ruleSetId = "api-rules", throwOnReject = true)
@GetMapping("/api/data")
public Data getData() { ... }
```

```java
// Advice — 429를 애플리케이션 자체 형식으로 렌더링
import org.fluxgate.spring.aop.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice
public class RateLimitExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RateLimitExceptionHandler.class);

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimitExceeded(
            RateLimitExceededException e) {
        long retryAfterSeconds = Math.max(1L, e.getRetryAfterMillis() / 1000L);
        log.info("Rate limit exceeded, advising the client to retry after {}s",
                retryAfterSeconds);

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", String.valueOf(retryAfterSeconds))
            .body(Map.of(
                "error", "RATE_LIMITED",
                "message", "Rate limit exceeded",
                "retryAfterSeconds", retryAfterSeconds));
    }
}
```

`RateLimitExceededException`이 제공하는 정보:
- `getRetryAfterMillis()` — 다음 요청이 성공할 때까지 기다려야 할 밀리초
- `getRateLimitResponse()` — `getLimit()`, `getRemainingTokens()`, `getResetTimeMillis()`를 포함한 전체 `RateLimitResponse`

---

## 비웹 사용

`@RateLimit`이 서블릿 요청 밖에서 실행되는 메서드(`@Scheduled`, `@KafkaListener`, 일반 서비스
호출 등)에 붙어있으면 기록할 `HttpServletResponse`가 없습니다. 이 경우 `throwOnReject` 설정과
무관하게 Aspect가 항상 `RateLimitExceededException`을 던집니다.

```java
@Service
public class DataProcessingService {

    // @Scheduled 작업에서 실행 — 항상 거부 시 예외 발생
    @RateLimit(ruleSetId = "batch-rules")
    public void processNextBatch() {
        // ...
    }
}
```

```java
@Component
public class BatchScheduler {

    private final DataProcessingService service;

    @Scheduled(fixedRate = 1000)
    public void run() {
        try {
            service.processNextBatch();
        } catch (RateLimitExceededException e) {
            log.warn("Batch rate-limited, retry after {}ms", e.getRetryAfterMillis());
        }
    }
}
```

---

## 필터 vs. Aspect

| 기준 | 필터 (`@EnableFluxgateFilter`) | Aspect (`@EnableFluxgateAspect` + `@RateLimit`) |
|------|-------------------------------|--------------------------------------------------|
| 활성화 방식 | URL 패턴에 매칭되는 모든 요청 | `@RateLimit`이 붙은 메서드만 |
| 세분성 | URL 패턴 | 개별 메서드 |
| Spring Security | `filter-order`에 따라 Security 전후 실행 | 모든 Spring 인터셉터 이후 실행 — principal 사용 가능 |
| 비웹 | 해당 없음 | 지원 (`@Scheduled`, `@KafkaListener`, …) |
| 엔드포인트당 복수 제한 | 요청당 룰셋 하나만 | 메서드마다 다른 `ruleSetId` 지정 가능 |
| 가중 permits | 미지원 | `permits` 속성으로 지원 |
| 예외 전파 | 429를 직접 기록 | `throwOnReject`로 커스텀 렌더링 가능 |
| 주요 사용 사례 | API Gateway 스타일 일괄 제한 | 세밀한 작업별 쿼터 |

두 모드 모두 동일한 `FluxgateRateLimitHandler`, Rate Limit 헤더, 응답 body 형식을 사용하므로
전환해도 클라이언트 측에서는 동작 차이가 없습니다.
