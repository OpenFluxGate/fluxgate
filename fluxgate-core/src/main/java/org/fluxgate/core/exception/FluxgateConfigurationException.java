package org.fluxgate.core.exception;

/**
 * Exception thrown when FluxGate configuration is invalid or incomplete.
 *
 * <p>This exception indicates a configuration problem that prevents FluxGate
 * from starting or operating correctly. Configuration exceptions are not
 * retryable as they require manual intervention to fix.
 *
 */
public class FluxgateConfigurationException extends FluxgateException {

    /**
     * Constructs a new FluxgateConfigurationException with the specified message.
     *
     * @param message the detail message
     */
    public FluxgateConfigurationException(String message) {
        super(message);
    }

    /**
     * Constructs a new FluxgateConfigurationException with the specified message and cause.
     *
     * @param message the detail message
     * @param cause the cause of the exception
     */
    public FluxgateConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public boolean isRetryable() {
        return false;
    }
}
