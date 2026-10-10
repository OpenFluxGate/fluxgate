package org.fluxgate.spring.aop;

import org.fluxgate.core.handler.RateLimitResponse;

/**
 * Thrown by {@link RateLimitAspect} instead of writing a 429 response directly.
 *
 * <p>Raised whenever the aspect does not write the response itself:
 *
 * <ul>
 *   <li>the intercepted method is not a Spring MVC request handler ({@code @RequestMapping} or a
 *       shortcut such as {@code @GetMapping}): a service called while a request is handled, a
 *       scheduled task, a message listener
 *   <li>the handler returns a primitive, which cannot be replaced by {@code null}
 *   <li>the method is annotated {@code @RateLimit(throwOnReject = true)}, letting a
 *       {@code @ControllerAdvice} render the error in the application's own format
 * </ul>
 *
 * <p>In a servlet web application {@link RateLimitExceededExceptionHandler} answers it by default
 * with HTTP 429, or 503 when {@link #isServiceUnavailable()} is set, plus {@code Retry-After} when
 * the delay is known. To use the application's own error format, handle it in a
 * {@code @ControllerAdvice}, which takes precedence:
 *
 * <pre>
 * {@code @ExceptionHandler(RateLimitExceededException.class)}
 * ResponseEntity&lt;ApiError&gt; handle(RateLimitExceededException e) {
 *   HttpStatus status =
 *       e.isServiceUnavailable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.TOO_MANY_REQUESTS;
 *   ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
 *   if (e.getRetryAfterMillis() &gt; 0) {
 *     response.header("Retry-After", String.valueOf((e.getRetryAfterMillis() + 999) / 1000));
 *   }
 *   return response.body(new ApiError(e.isServiceUnavailable() ? "RATE_LIMIT_UNAVAILABLE" : "RATE_LIMITED"));
 * }
 * </pre>
 */
public class RateLimitExceededException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient RateLimitResponse rateLimitResponse;

  private final boolean serviceUnavailable;

  /**
   * Creates the exception for an exceeded limit.
   *
   * @param rateLimitResponse the rejection carrying retry, limit and reset information
   */
  public RateLimitExceededException(RateLimitResponse rateLimitResponse) {
    this(rateLimitResponse, false);
  }

  /**
   * Creates the exception.
   *
   * @param rateLimitResponse the rejection carrying retry, limit and reset information
   * @param serviceUnavailable true when the invocation was rejected because rate limiting is
   *     unavailable or not configured (an HTTP layer answers 503), false for an exceeded limit
   *     (429)
   * @since 0.4.0
   */
  public RateLimitExceededException(
      RateLimitResponse rateLimitResponse, boolean serviceUnavailable) {
    super(
        (serviceUnavailable
                ? "Rate limiting unavailable, retry after "
                : "Rate limit exceeded, retry after ")
            + (rateLimitResponse != null ? rateLimitResponse.getRetryAfterMillis() : -1L)
            + " ms");
    this.rateLimitResponse = rateLimitResponse;
    this.serviceUnavailable = serviceUnavailable;
  }

  /**
   * Whether the invocation was rejected because rate limiting itself is unavailable or not
   * configured, rather than because a limit was exceeded. Map it to HTTP 503 instead of 429.
   *
   * @return true for an unavailable rate limiter
   * @since 0.4.0
   */
  public boolean isServiceUnavailable() {
    return serviceUnavailable;
  }

  /**
   * The rejection that caused this exception.
   *
   * @return the rate limit response, may be null after deserialization
   */
  public RateLimitResponse getRateLimitResponse() {
    return rateLimitResponse;
  }

  /**
   * Milliseconds the client should wait before retrying.
   *
   * @return the retry delay, or -1 when unknown
   */
  public long getRetryAfterMillis() {
    return rateLimitResponse != null ? rateLimitResponse.getRetryAfterMillis() : -1L;
  }
}
