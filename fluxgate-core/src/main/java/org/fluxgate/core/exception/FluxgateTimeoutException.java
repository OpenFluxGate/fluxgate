package org.fluxgate.core.exception;

import java.time.Duration;

/**
 * Exception thrown when an operation times out.
 *
 * <p>This exception is thrown when:
 *
 * <ul>
 *   <li>A connection attempt exceeds the configured timeout
 *   <li>A rate limit operation takes too long
 *   <li>A script execution exceeds the timeout limit
 * </ul>
 *
 * <p>Timeout exceptions are typically retryable as they may be caused by temporary load spikes or
 * network congestion.
 */
public class FluxgateTimeoutException extends FluxgateException {

  private final Duration timeout;
  private final String operation;

  /**
   * Constructs a new FluxgateTimeoutException with the specified message.
   *
   * @param message the detail message
   */
  public FluxgateTimeoutException(String message) {
    super(message);
    this.timeout = null;
    this.operation = null;
  }

  /**
   * Constructs a new FluxgateTimeoutException with the specified message and cause.
   *
   * @param message the detail message
   * @param cause the cause of the exception
   */
  public FluxgateTimeoutException(String message, Throwable cause) {
    super(message, cause);
    this.timeout = null;
    this.operation = null;
  }

  /**
   * Constructs a new FluxgateTimeoutException with detailed timeout information.
   *
   * @param operation the operation that timed out
   * @param timeout the timeout duration that was exceeded
   */
  public FluxgateTimeoutException(String operation, Duration timeout) {
    super("Operation '" + operation + "' timed out after " + timeout.toMillis() + "ms");
    this.operation = operation;
    this.timeout = timeout;
  }

  /**
   * Constructs a new FluxgateTimeoutException with detailed timeout information and cause.
   *
   * @param operation the operation that timed out
   * @param timeout the timeout duration that was exceeded
   * @param cause the cause of the exception
   */
  public FluxgateTimeoutException(String operation, Duration timeout, Throwable cause) {
    super("Operation '" + operation + "' timed out after " + timeout.toMillis() + "ms", cause);
    this.operation = operation;
    this.timeout = timeout;
  }

  /**
   * Returns the timeout duration that was exceeded, if available.
   *
   * @return the timeout duration, or null if not available
   */
  public Duration getTimeout() {
    return timeout;
  }

  /**
   * Returns the name of the operation that timed out, if available.
   *
   * @return the operation name, or null if not available
   */
  public String getOperation() {
    return operation;
  }

  @Override
  public boolean isRetryable() {
    return true;
  }
}
