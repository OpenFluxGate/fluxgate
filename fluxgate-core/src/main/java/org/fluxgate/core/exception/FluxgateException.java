package org.fluxgate.core.exception;

/**
 * Base exception for all FluxGate exceptions.
 *
 * <p>This is the root of the FluxGate exception hierarchy. All FluxGate-specific exceptions should
 * extend this class to allow for unified exception handling.
 */
public abstract class FluxgateException extends RuntimeException {

  /**
   * Constructs a new FluxgateException with the specified message.
   *
   * @param message the detail message
   */
  protected FluxgateException(String message) {
    super(message);
  }

  /**
   * Constructs a new FluxgateException with the specified message and cause.
   *
   * @param message the detail message
   * @param cause the cause of the exception
   */
  protected FluxgateException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Returns whether this exception is retryable.
   *
   * <p>Subclasses can override this method to indicate whether the operation that caused this
   * exception can be retried.
   *
   * @return true if the operation can be retried, false otherwise
   */
  public boolean isRetryable() {
    return false;
  }
}
