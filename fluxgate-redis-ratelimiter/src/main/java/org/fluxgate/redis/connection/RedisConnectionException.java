package org.fluxgate.redis.connection;

/**
 * Exception thrown when Redis connection operations fail.
 *
 * <p>This exception wraps underlying connection errors from Lettuce and provides a consistent
 * exception type for both standalone and cluster modes.
 */
public class RedisConnectionException extends RuntimeException {

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
