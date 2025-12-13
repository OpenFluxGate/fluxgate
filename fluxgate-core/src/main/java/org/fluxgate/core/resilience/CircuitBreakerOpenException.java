package org.fluxgate.core.resilience;

import org.fluxgate.core.exception.FluxgateException;

/**
 * Exception thrown when a circuit breaker is open and the fallback strategy is FAIL_CLOSED.
 *
 * <p>This exception indicates that the circuit breaker has tripped due to consecutive failures and
 * is rejecting requests to prevent further damage.
 *
 */
public class CircuitBreakerOpenException extends FluxgateException {

    private final String circuitBreakerName;

    /**
     * Constructs a new CircuitBreakerOpenException.
     *
     * @param circuitBreakerName the name of the circuit breaker
     */
    public CircuitBreakerOpenException(String circuitBreakerName) {
        super("Circuit breaker '" + circuitBreakerName + "' is open");
        this.circuitBreakerName = circuitBreakerName;
    }

    /**
     * Constructs a new CircuitBreakerOpenException with a custom message.
     *
     * @param circuitBreakerName the name of the circuit breaker
     * @param message the detail message
     */
    public CircuitBreakerOpenException(String circuitBreakerName, String message) {
        super(message);
        this.circuitBreakerName = circuitBreakerName;
    }

    /**
     * Returns the name of the circuit breaker that is open.
     *
     * @return the circuit breaker name
     */
    public String getCircuitBreakerName() {
        return circuitBreakerName;
    }

    @Override
    public boolean isRetryable() {
        return false; // Don't retry when circuit is open
    }
}
