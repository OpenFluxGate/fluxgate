package org.fluxgate.core.exception;

/**
 * Exception thrown when a connection to a backend service fails.
 *
 * <p>This is the base class for all connection-related exceptions in FluxGate. Connection
 * exceptions are typically retryable as they may be caused by temporary network issues.
 */
public class FluxgateConnectionException extends FluxgateException {

  /**
   * Constructs a new FluxgateConnectionException with the specified message.
   *
   * @param message the detail message
   */
  public FluxgateConnectionException(String message) {
    super(message);
  }

  /**
   * Constructs a new FluxgateConnectionException with the specified message and cause.
   *
   * @param message the detail message
   * @param cause the cause of the exception
   */
  public FluxgateConnectionException(String message, Throwable cause) {
    super(message, cause);
  }

  @Override
  public boolean isRetryable() {
    return true;
  }
}
