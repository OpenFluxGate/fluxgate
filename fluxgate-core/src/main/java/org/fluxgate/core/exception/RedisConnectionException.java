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
 *
 * <p><b>Retryability depends on the {@link Phase}.</b> A failure while connecting ({@link
 * Phase#CONNECT}) never reached Redis, so retrying it is safe and {@link #isRetryable()} returns
 * {@code true}. A failure while a command was in flight ({@link Phase#COMMAND}) may have happened
 * after Redis executed the command: retrying a token consumption could then charge the same request
 * twice, so it is not retryable. When the phase is not known ({@link Phase#UNKNOWN}, the default of
 * the constructors without a phase) the exception is treated like a command failure. {@link
 * org.fluxgate.core.resilience.RetryConfig#shouldRetry(Exception)} honours this verdict even though
 * {@link FluxgateConnectionException} is on its default allow-list. Timeouts are reported as {@link
 * FluxgateTimeoutException} and governed by {@code retryOnTimeout} instead.
 */
public class RedisConnectionException extends FluxgateConnectionException {

  /**
   * When the failure happened relative to sending a command to Redis.
   *
   * @since 0.4.0
   */
  public enum Phase {
    /** While establishing the connection, before any command was sent; safe to retry. */
    CONNECT,
    /** While a command was in flight; it may have run on Redis, so not retried automatically. */
    COMMAND,
    /** Not known; treated like {@link #COMMAND}. */
    UNKNOWN
  }

  /** The Redis URI that could not be reached. */
  private final String redisUri;

  /** When the failure happened. */
  private final Phase phase;

  /**
   * Constructs a new RedisConnectionException with the specified message and an unknown phase (not
   * retryable).
   *
   * @param message the detail message
   */
  public RedisConnectionException(String message) {
    super(message);
    this.redisUri = null;
    this.phase = Phase.UNKNOWN;
  }

  /**
   * Constructs a new RedisConnectionException with the specified message and cause and an unknown
   * phase (not retryable).
   *
   * @param message the detail message
   * @param cause the cause of the exception
   */
  public RedisConnectionException(String message, Throwable cause) {
    this(message, cause, Phase.UNKNOWN);
  }

  /**
   * Constructs a new RedisConnectionException with the specified message, cause and phase.
   *
   * @param message the detail message
   * @param cause the cause of the exception
   * @param phase when the failure happened; {@code null} means {@link Phase#UNKNOWN}
   * @since 0.4.0
   */
  public RedisConnectionException(String message, Throwable cause, Phase phase) {
    super(message, cause);
    this.redisUri = null;
    this.phase = phase != null ? phase : Phase.UNKNOWN;
  }

  /**
   * Constructs a new RedisConnectionException with the specified message, URI, and cause and an
   * unknown phase (not retryable).
   *
   * @param message the detail message
   * @param redisUri the Redis URI that failed to connect (may be masked for security)
   * @param cause the cause of the exception
   */
  public RedisConnectionException(String message, String redisUri, Throwable cause) {
    this(message, redisUri, cause, Phase.UNKNOWN);
  }

  /**
   * Constructs a new RedisConnectionException with the specified message, URI, cause and phase.
   *
   * @param message the detail message
   * @param redisUri the Redis URI that failed to connect (must be masked: it is part of the
   *     message)
   * @param cause the cause of the exception
   * @param phase when the failure happened; {@code null} means {@link Phase#UNKNOWN}
   * @since 0.4.0
   */
  public RedisConnectionException(String message, String redisUri, Throwable cause, Phase phase) {
    super(message + " (uri: " + redisUri + ")", cause);
    this.redisUri = redisUri;
    this.phase = phase != null ? phase : Phase.UNKNOWN;
  }

  /**
   * Returns the Redis URI that failed to connect, if available.
   *
   * @return the Redis URI (may be masked for security), or null if not available
   */
  public String getRedisUri() {
    return redisUri;
  }

  /**
   * Returns when the failure happened.
   *
   * @return the phase, never null
   * @since 0.4.0
   */
  public Phase getPhase() {
    return phase;
  }

  /**
   * Returns {@code true} only for {@link Phase#CONNECT} failures, which never reached Redis.
   *
   * @return whether the failed operation can safely be retried
   */
  @Override
  public boolean isRetryable() {
    return phase == Phase.CONNECT;
  }
}
