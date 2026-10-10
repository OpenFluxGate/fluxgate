package org.fluxgate.core.resilience;

import java.util.function.Supplier;

/**
 * Circuit breaker that prevents cascading failures.
 *
 * <p>The circuit breaker tracks failures and transitions between states:
 *
 * <ul>
 *   <li><b>CLOSED</b>: Normal operation, requests flow through
 *   <li><b>OPEN</b>: After the failure threshold is reached, requests are rejected without touching
 *       the protected resource
 *   <li><b>HALF_OPEN</b>: After the wait duration, a bounded number of concurrent trial calls are
 *       admitted to test recovery; every other call is treated as if the circuit were still open
 * </ul>
 *
 * <p>The two entry points differ only in how an open circuit is reported: {@link #execute(String,
 * Supplier)} throws {@link CircuitBreakerOpenException}, while {@link
 * #executeWithFallback(Supplier, Supplier)} returns the caller-supplied fallback. Neither ever
 * returns {@code null} on behalf of the caller, so callers that need fail-open behaviour must pass
 * an explicit fallback.
 *
 * <p>An action that throws {@link IgnoredCallException} reports an outcome that is neither a
 * success nor a failure. The built-in implementations record nothing for it and rethrow it from
 * both entry points, without using the fallback.
 */
public interface CircuitBreaker {

  /**
   * Executes the given action with circuit breaker protection.
   *
   * @param <T> the return type
   * @param action the action to execute
   * @return the result of the action
   * @throws CircuitBreakerOpenException if the circuit is open, regardless of the configured
   *     fallback strategy
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
   * @throws CircuitBreakerOpenException if the circuit is open, regardless of the configured
   *     fallback strategy
   * @throws Exception if the action fails
   */
  <T> T execute(String operationName, Supplier<T> action) throws Exception;

  /**
   * Executes the given action, falling back when the circuit is open or the action fails.
   *
   * <p>Failures are recorded by the circuit breaker before the fallback is applied, so a circuit
   * protecting a permanently failing resource does eventually open on this path too.
   *
   * @param <T> the return type
   * @param action the action to execute
   * @param fallback the fallback to use when the circuit is open or the action fails; must not be
   *     null
   * @return the result of the action or fallback
   */
  <T> T executeWithFallback(Supplier<T> action, Supplier<T> fallback);

  /**
   * Returns the current state of the circuit breaker.
   *
   * <p>This is a pure read: polling it never advances the state machine, so monitoring code cannot
   * consume the OPEN to HALF_OPEN transition. That transition only happens on the execute path.
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
