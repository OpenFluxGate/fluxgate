package org.fluxgate.sample.standalone.advice;

import java.util.Map;
import org.fluxgate.spring.aop.RateLimitExceededException;
import org.fluxgate.spring.aop.RateLimitExceededExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders {@link RateLimitExceededException} in the application's own error format: 429 when the
 * limit is exceeded, 503 when rate limiting is unavailable ({@link
 * RateLimitExceededException#isServiceUnavailable()}).
 *
 * <p>The AOP aspect throws instead of writing the response itself when the annotated method uses
 * {@code @RateLimit(throwOnReject = true)}, or when it runs outside a servlet request (a scheduled
 * task, a message listener). The starter maps the exception to the same 429 or 503 with its own
 * problem document; this advice is ordered just before it to show a custom body. An unordered
 * advice would run after the starter's and never see the exception.
 */
@RestControllerAdvice
@Order(RateLimitExceededExceptionHandler.ORDER - 1)
public class RateLimitExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(RateLimitExceptionHandler.class);

  @ExceptionHandler(RateLimitExceededException.class)
  public ResponseEntity<Map<String, Object>> handleRateLimitExceeded(RateLimitExceededException e) {
    boolean unavailable = e.isServiceUnavailable();
    HttpStatus status = unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.TOO_MANY_REQUESTS;
    long retryAfterMillis = e.getRetryAfterMillis();
    // -1 when rate limiting is unavailable without a known wait: no Retry-After then
    long retryAfterSeconds = retryAfterMillis > 0 ? (retryAfterMillis + 999) / 1000 : -1;
    log.info("Rate limit response {}, retry after {}s", status.value(), retryAfterSeconds);

    ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
    if (retryAfterSeconds > 0) {
      response.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
    }
    return response.body(
        Map.of(
            "error", unavailable ? "RATE_LIMITING_UNAVAILABLE" : "RATE_LIMITED",
            "message", unavailable ? "Rate limiting is unavailable" : "Rate limit exceeded",
            "retryAfterSeconds", retryAfterSeconds));
  }
}
