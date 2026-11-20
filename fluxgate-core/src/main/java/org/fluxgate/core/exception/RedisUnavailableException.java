package org.fluxgate.core.exception;

/**
 * Exception thrown when the Redis-backed rate limiter has no connection to serve a request with.
 *
 * <p>Unlike {@link RedisConnectionException} this is deliberately <b>not</b> retryable. The
 * connection is re-established by a background reconnect task with its own backoff, so retrying on
 * the request thread cannot make it available any sooner - it only multiplies the latency the
 * caller already paid and turns one Redis outage into a thread pool exhaustion. A retry executor
 * therefore propagates it immediately and the configured {@code
 * fluxgate.ratelimit.failure-behavior} - or the in-memory fallback limiter - decides what happens
 * to the request.
 */
public class RedisUnavailableException extends FluxgateConnectionException {

  /**
   * Constructs a new RedisUnavailableException with the specified message.
   *
   * @param message the detail message; must not contain credentials
   */
  public RedisUnavailableException(String message) {
    super(message);
  }

  /**
   * Constructs a new RedisUnavailableException with the specified message and cause.
   *
   * @param message the detail message; must not contain credentials
   * @param cause the cause of the exception
   */
  public RedisUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }

  @Override
  public boolean isRetryable() {
    return false;
  }
}
