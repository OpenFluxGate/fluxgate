package org.fluxgate.spring.aop;

import org.fluxgate.core.handler.RateLimitResponse;

/**
 * Thrown by {@link RateLimitAspect} instead of writing a 429 response directly.
 *
 * <p>Raised in two situations:
 *
 * <ul>
 *   <li>the intercepted method runs outside a servlet request (a scheduled task, a message
 *       listener), so there is no response to write to
 *   <li>the method is annotated {@code @RateLimit(throwOnReject = true)}, letting a
 *       {@code @ControllerAdvice} render the error in the application's own format
 * </ul>
 *
 * <p>Example handler:
 *
 * <pre>
 * {@code @ExceptionHandler(RateLimitExceededException.class)}
 * ResponseEntity&lt;ApiError&gt; handle(RateLimitExceededException e) {
 *   return ResponseEntity.status(429)
 *       .header("Retry-After", String.valueOf(e.getRetryAfterMillis() / 1000))
 *       .body(new ApiError("RATE_LIMITED"));
 * }
 * </pre>
 */
public class RateLimitExceededException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient RateLimitResponse rateLimitResponse;

  /**
   * Creates the exception.
   *
   * @param rateLimitResponse the rejection carrying retry, limit and reset information
   */
  public RateLimitExceededException(RateLimitResponse rateLimitResponse) {
    super(
        "Rate limit exceeded, retry after "
            + (rateLimitResponse != null ? rateLimitResponse.getRetryAfterMillis() : -1L)
            + " ms");
    this.rateLimitResponse = rateLimitResponse;
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
