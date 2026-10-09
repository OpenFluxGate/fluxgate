# @RateLimit Annotation Guide

The `@RateLimit` annotation rate-limits individual methods or classes using Spring AOP.
It is the right choice when you need per-method control — for example to set a tighter
limit on an expensive export endpoint while keeping the default limit on ordinary reads.
Use the servlet filter (`@EnableFluxgateFilter`) when you want a blanket limit on all
requests to a URL pattern without touching the controllers.

## Contents

- [Enabling the aspect](#enabling-the-aspect)
- [Annotation attributes](#annotation-attributes)
- [Examples](#examples)
- [Handling rejections: `throwOnReject` and `@RestControllerAdvice`](#handling-rejections)
- [Non-web usage (scheduled tasks, message listeners)](#non-web-usage)
- [Filter vs. aspect decision table](#filter-vs-aspect)

---

## Enabling the aspect

Add `@EnableFluxgateAspect` to your main application class or any `@Configuration` class:

```java
@SpringBootApplication
@EnableFluxgateAspect
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

`@EnableFluxgateAspect` imports `FluxgateAopAutoConfiguration`, which registers the
`RateLimitAspect` bean. The aspect intercepts all methods annotated with `@RateLimit` and
applies the configured rule before the method body executes.

You can combine `@EnableFluxgateFilter` and `@EnableFluxgateAspect` on the same class if
you want both a blanket HTTP filter and per-method overrides.

**Prerequisites** — the same auto-configured beans the filter uses:
- A `FluxgateRateLimitHandler` bean (auto-registered when a `RateLimiter` + a
  `RateLimitRuleSetProvider` are present)
- Optionally a `RequestContextCustomizer` bean for request-context enrichment

---

## Annotation attributes

### `ruleSetId` (String, default `""`)

The ID of the rule set to apply. When blank, the aspect falls back to
`fluxgate.ratelimit.default-rule-set-id` from properties, then to the `ruleSetId`
attribute of `@EnableFluxgateAspect` (if any).

```java
@RateLimit(ruleSetId = "export-rules")
@GetMapping("/api/export")
public ResponseEntity<byte[]> export() { ... }
```

### `permits` (long, default `1`)

Number of tokens this invocation consumes. Values below `1` are treated as `1`.

Use a value above `1` for expensive operations so a single call counts as several against
the quota — for example a bulk export that costs as much as 10 ordinary reads:

```java
@RateLimit(ruleSetId = "api-rules", permits = 10)
@PostMapping("/api/bulk-export")
public ResponseEntity<byte[]> bulkExport() { ... }
```

The configured `FluxgateRateLimitHandler` must support weighted permits; the default
`EngineBackedRateLimitHandler` delegates to the 3-argument `tryConsume` overload only when
`permits > 1`. If the handler does not override that overload it throws
`UnsupportedOperationException`.

### `waitForRefill` (boolean, default `false`)

When `true`, a rejected request waits (blocks the thread) up to `maxWaitTimeMs`
milliseconds for tokens to become available instead of failing immediately.

> **Waiting blocks a worker thread.** The aspect cannot hand an invocation back to the
> container, and the filter deliberately does not use Servlet async either (an `ASYNC`
> re-dispatch skips filters registered after FluxGate). Each waiting request holds a
> thread for the whole wait, bounded only by `max-wait-time-ms` and
> `max-concurrent-waits`. Prefer the default — reject with `429` and `Retry-After` and let
> the client back off — and keep waits short where you do enable them. Non-blocking
> waiting is a reactive (WebFlux) concern.

Requires `fluxgate.ratelimit.wait-for-refill.enabled=true` in `application.yml`, or the
rule's `onLimitExceedPolicy` must be `WAIT_FOR_REFILL`.

```java
@RateLimit(ruleSetId = "premium-rules", waitForRefill = true, maxWaitTimeMs = 3000)
@PostMapping("/api/submit")
public Response submit(@RequestParam String userId) { ... }
```

### `maxWaitTimeMs` (long, default `5000`)

Maximum time in milliseconds the thread may wait when `waitForRefill = true`. Has no
effect when `waitForRefill` is `false`. The property
`fluxgate.ratelimit.wait-for-refill.max-wait-time-ms` (default `5000`) is a global cap:
the effective limit is the **smaller** of the two, so the annotation can only shorten a
wait, never lengthen it.

### `throwOnReject` (boolean, default `false`)

When `false` (the default), the aspect writes the HTTP 429 response directly using the
same `RateLimitResponseWriter` as the filter. When `true`, it throws
`RateLimitExceededException` instead, giving your `@ControllerAdvice` the chance to render
the error in the application's own format.

The exception is always thrown (regardless of this flag) when the invocation has no
servlet response to write to — for example a `@Scheduled` method or a message listener.
See [Non-web usage](#non-web-usage).

### `maxConcurrentWaits` (int, default `100`) — deprecated

**Ignored since 0.4.0.** The wait semaphore was moved to an aspect-wide scope because a
per-invocation semaphore limited nothing at all. Configure
`fluxgate.ratelimit.wait-for-refill.max-concurrent-waits` instead.

---

## Examples

### Basic IP-based limit on a controller method

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

### Class-level annotation (all public methods share the limit)

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

### Weighted permits for expensive operations

```java
@RestController
public class ReportController {

    // A single export counts as 10 ordinary requests against the quota
    @RateLimit(ruleSetId = "api-rules", permits = 10)
    @GetMapping("/api/report/full")
    public ResponseEntity<byte[]> fullReport() { ... }
}
```

---

## Handling rejections

### Default behaviour (`throwOnReject = false`)

The aspect writes the RFC 9457 problem body and standard rate limit headers directly into
the servlet response — the same format as the filter:

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

### Custom error format (`throwOnReject = true`)

Add `throwOnReject = true` on the annotation and register a `@RestControllerAdvice` that
catches `RateLimitExceededException`. This lets you render the 429 in your own JSON
schema. The following example is taken from
`fluxgate-samples/fluxgate-sample-standalone-java21`:

```java
// controller
@RateLimit(ruleSetId = "api-rules", throwOnReject = true)
@GetMapping("/api/data")
public Data getData() { ... }
```

```java
// advice — renders the rejection in the application's own format
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

`RateLimitExceededException` exposes:
- `getRetryAfterMillis()` — milliseconds to wait before the next request is likely to
  succeed
- `getRateLimitResponse()` — the full `RateLimitResponse` from the handler, including
  `getLimit()`, `getRemainingTokens()`, `getResetTimeMillis()`

---

## Non-web usage

When `@RateLimit` is placed on a method that runs outside a servlet request — a
`@Scheduled` task, a `@KafkaListener`, or a plain service call — there is no
`HttpServletResponse` to write to. The aspect always throws `RateLimitExceededException`
in this case, regardless of the `throwOnReject` flag.

```java
@Service
public class DataProcessingService {

    // runs in a @Scheduled job — always throws on rejection
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

## Filter vs. aspect

| Criterion | Filter (`@EnableFluxgateFilter`) | Aspect (`@EnableFluxgateAspect` + `@RateLimit`) |
|-----------|----------------------------------|--------------------------------------------------|
| Activation | All requests matching URL patterns | Only methods annotated with `@RateLimit` |
| Granularity | URL pattern | Individual method |
| Spring Security | Runs before or after security depending on `filter-order` | Runs after all Spring interceptors; principal is available |
| Non-web | Not applicable | Supported (`@Scheduled`, `@KafkaListener`, …) |
| Multiple limits per endpoint | Only one rule set per request | Each method can have its own `ruleSetId` |
| Weighted permits | Not supported | Supported via `permits` attribute |
| Exception propagation | Writes 429 directly | Can throw `RateLimitExceededException` for custom rendering |
| Typical use case | API gateway-style blanket limiting | Fine-grained, per-operation quotas |

Both modes share the same `FluxgateRateLimitHandler`, rate limit headers, and response
body format, so switching between them does not change the client-visible behaviour.
