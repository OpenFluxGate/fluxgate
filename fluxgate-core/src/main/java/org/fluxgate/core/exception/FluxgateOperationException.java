package org.fluxgate.core.exception;

/**
 * Exception thrown when a FluxGate operation fails at runtime.
 *
 * <p>This is the base class for all runtime operation exceptions in FluxGate. Operation exceptions
 * may or may not be retryable depending on the specific failure cause.
 */
public class FluxgateOperationException extends FluxgateException {

  private final boolean retryable;

  /**
   * Constructs a new FluxgateOperationException with the specified message.
   *
   * @param message the detail message
   */
  public FluxgateOperationException(String message) {
    super(message);
    this.retryable = false;
  }

  /**
   * Constructs a new FluxgateOperationException with the specified message and cause.
   *
   * @param message the detail message
   * @param cause the cause of the exception
   */
  public FluxgateOperationException(String message, Throwable cause) {
    super(message, cause);
    this.retryable = false;
  }

  /**
   * Constructs a new FluxgateOperationException with the specified message, cause, and retryable
   * flag.
   *
   * @param message the detail message
   * @param cause the cause of the exception
   * @param retryable whether this operation can be retried
   */
  public FluxgateOperationException(String message, Throwable cause, boolean retryable) {
    super(message, cause);
    this.retryable = retryable;
  }

  @Override
  public boolean isRetryable() {
    return retryable;
  }
}
