package org.fluxgate.core.resilience;

import java.util.function.Supplier;

/**
 * Circuit breaker that prevents cascading failures.
 *
 * <p>The circuit breaker tracks failures and transitions between states:
 *
 * <ul>
 *   <li><b>CLOSED</b>: Normal operation, requests flow through
 *   <li><b>OPEN</b>: After failure threshold reached, requests are rejected or bypassed
 *   <li><b>HALF_OPEN</b>: After wait duration, limited requests are allowed to test recovery
 * </ul>
 *
 */
public interface CircuitBreaker {

    /**
     * Executes the given action with circuit breaker protection.
     *
     * @param <T> the return type
     * @param action the action to execute
     * @return the result of the action
     * @throws CircuitBreakerOpenException if the circuit is open and fallback is FAIL_CLOSED
     * @throws Exception if the action fails
     */
    <T> T execute(Supplier<T> action) throws Exception;

    /**
     * Executes the given action with circuit breaker protection.
     *
     * @param <T> the return type
     * @param operationName the name of the operation for logging
     * @param action the action to execute
     * @return the result of the action
     * @throws CircuitBreakerOpenException if the circuit is open and fallback is FAIL_CLOSED
     * @throws Exception if the action fails
     */
    <T> T execute(String operationName, Supplier<T> action) throws Exception;

    /**
     * Executes the given action with a fallback when circuit is open.
     *
     * @param <T> the return type
     * @param action the action to execute
     * @param fallback the fallback to use when circuit is open
     * @return the result of the action or fallback
     */
    <T> T executeWithFallback(Supplier<T> action, Supplier<T> fallback);

    /**
     * Returns the current state of the circuit breaker.
     *
     * @return the current state
     */
    State getState();

    /**
     * Returns the circuit breaker configuration.
     *
     * @return the configuration
     */
    CircuitBreakerConfig getConfig();

    /**
     * Resets the circuit breaker to closed state.
     *
     * <p>This method should be used with caution, typically only for testing or manual recovery.
     */
    void reset();

    /** Circuit breaker states. */
    enum State {
        /** Circuit is closed, requests flow through normally. */
        CLOSED,

        /** Circuit is open, requests are rejected or bypassed. */
        OPEN,

        /** Circuit is half-open, limited requests are allowed to test recovery. */
        HALF_OPEN
    }
}
