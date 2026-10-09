package org.fluxgate.sample.standalone.java11.advice;

import java.util.Map;
import org.fluxgate.spring.aop.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders {@link RateLimitExceededException} as a 429 in the application's own error format.
 *
 * <p>The AOP aspect throws instead of writing the response itself when the annotated method uses
 * {@code @RateLimit(throwOnReject = true)}, or when it runs outside a servlet request (a scheduled
 * task, a message listener). Without an advice like this one, Spring would turn the rejection into
 * a 500.
 */
@RestControllerAdvice
public class RateLimitExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(RateLimitExceptionHandler.class);

  @ExceptionHandler(RateLimitExceededException.class)
  public ResponseEntity<Map<String, Object>> handleRateLimitExceeded(RateLimitExceededException e) {
    long retryAfterSeconds = Math.max(1L, e.getRetryAfterMillis() / 1000L);
    log.info("Rate limit exceeded, advising the client to retry after {}s", retryAfterSeconds);

    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header("Retry-After", String.valueOf(retryAfterSeconds))
        .body(
            Map.of(
                "error", "RATE_LIMITED",
                "message", "Rate limit exceeded",
                "retryAfterSeconds", retryAfterSeconds));
  }
}
