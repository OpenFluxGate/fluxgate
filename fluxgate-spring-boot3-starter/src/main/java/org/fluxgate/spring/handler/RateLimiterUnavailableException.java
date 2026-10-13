package org.fluxgate.spring.handler;

/**
 * Signals that a request is rejected because rate limiting itself is unavailable or not configured,
 * not because a limit was exceeded.
 *
 * <p>Thrown by the starter's handlers and limiters once the configured behaviour ({@code
 * fluxgate.ratelimit.failure-behavior=DENY}, {@code missing-rule-behavior=DENY}) has already
 * decided to reject. The filter and the aspect answer it with HTTP 503 Service Unavailable - with
 * {@code Retry-After} when the wait is known, for example the circuit breaker's open-state wait -
 * instead of the 429 that tells a client it sent too many requests.
 *
 * <p>The stack trace is not captured: the exception is control flow on the request path while a
 * backend is down.
 */
public class RateLimiterUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Retry delay used when none is known. */
  public static final long UNKNOWN_RETRY_AFTER = -1L;

  private final long retryAfterMillis;

  /**
   * Creates the exception with an unknown retry delay.
   *
   * @param message what is unavailable
   */
  public RateLimiterUnavailableException(String message) {
    this(message, UNKNOWN_RETRY_AFTER, null);
  }

  /**
   * Creates the exception.
   *
   * @param message what is unavailable
   * @param retryAfterMillis how long the client should wait, or a negative value when unknown
   * @param cause the underlying failure, may be null
   */
  public RateLimiterUnavailableException(String message, long retryAfterMillis, Throwable cause) {
    super(message, cause, false, false);
    this.retryAfterMillis = retryAfterMillis > 0 ? retryAfterMillis : UNKNOWN_RETRY_AFTER;
  }

  /**
   * How long the client should wait before retrying.
   *
   * @return the delay in milliseconds, or {@value #UNKNOWN_RETRY_AFTER} when unknown
   */
  public long getRetryAfterMillis() {
    return retryAfterMillis;
  }
}
