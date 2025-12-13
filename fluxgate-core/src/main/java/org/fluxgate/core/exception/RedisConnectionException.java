package org.fluxgate.core.exception;

/**
 * Exception thrown when a connection to Redis fails.
 *
 * <p>This exception is thrown when:
 *
 * <ul>
 *   <li>Initial connection to Redis cannot be established
 *   <li>Connection is lost during operation
 *   <li>Redis cluster node is unreachable
 * </ul>
 */
public class RedisConnectionException extends FluxgateConnectionException {

  private final String redisUri;

  /**
   * Constructs a new RedisConnectionException with the specified message.
   *
   * @param message the detail message
   */
  public RedisConnectionException(String message) {
    super(message);
    this.redisUri = null;
  }

  /**
   * Constructs a new RedisConnectionException with the specified message and cause.
   *
   * @param message the detail message
   * @param cause the cause of the exception
   */
  public RedisConnectionException(String message, Throwable cause) {
    super(message, cause);
    this.redisUri = null;
  }

  /**
   * Constructs a new RedisConnectionException with the specified message, URI, and cause.
   *
   * @param message the detail message
   * @param redisUri the Redis URI that failed to connect (may be masked for security)
   * @param cause the cause of the exception
   */
  public RedisConnectionException(String message, String redisUri, Throwable cause) {
    super(message + " (uri: " + redisUri + ")", cause);
    this.redisUri = redisUri;
  }

  /**
   * Returns the Redis URI that failed to connect, if available.
   *
   * @return the Redis URI (may be masked for security), or null if not available
   */
  public String getRedisUri() {
    return redisUri;
  }
}
