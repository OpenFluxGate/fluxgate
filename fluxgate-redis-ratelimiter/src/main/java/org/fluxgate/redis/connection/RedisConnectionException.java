package org.fluxgate.redis.connection;

/**
 * Exception thrown when Redis connection operations fail.
 *
 * <p>This exception wraps underlying connection errors from Lettuce and provides a consistent
 * exception type for both standalone and cluster modes.
 *
 * @deprecated Use {@link org.fluxgate.core.exception.RedisConnectionException} instead. FluxGate no
 *     longer throws this type anywhere; it now extends the core exception so that a {@code catch}
 *     block written against the core hierarchy sees connection failures, while code still catching
 *     this class keeps compiling. It will be removed in a future release.
 */
@Deprecated(since = "0.2.0", forRemoval = true)
public class RedisConnectionException extends org.fluxgate.core.exception.RedisConnectionException {

  /**
   * Creates a new Redis connection exception.
   *
   * @param message the error message
   */
  public RedisConnectionException(String message) {
    super(message);
  }

  /**
   * Creates a new Redis connection exception with a cause.
   *
   * @param message the error message
   * @param cause the underlying cause
   */
  public RedisConnectionException(String message, Throwable cause) {
    super(message, cause);
  }
}
