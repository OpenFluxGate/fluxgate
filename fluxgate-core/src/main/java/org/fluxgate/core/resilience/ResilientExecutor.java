package org.fluxgate.core.resilience;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Combines retry and circuit breaker patterns for resilient operation execution.
 *
 * <p>The execution flow is:
 *
 * <pre>
 * Request → CircuitBreaker → Retry → Actual Operation
 * </pre>
 *
 * <p>The circuit breaker is checked first to prevent unnecessary retries when the circuit is open.
 * If the circuit is closed or half-open, the retry executor handles the actual execution with
 * retries.
 *
 * <p>An exhausted retry chain is a failure of the guarded operation, so it is propagated to the
 * circuit breaker rather than being converted into a fallback value here. Only the circuit breaker
 * decides when to fall back, which is what lets it count failures and eventually open.
 *
 * <p>An {@link IgnoredCallException} is never retried. With the built-in circuit breakers ({@link
 * DefaultCircuitBreaker}, {@link NoOpCircuitBreaker}) it is not recorded either and is rethrown
 * unchanged from both {@code execute} and {@code executeWithFallback}: the fallback is not used for
 * it.
 */
public class ResilientExecutor {

  private final RetryExecutor retryExecutor;
  private final CircuitBreaker circuitBreaker;

  /**
   * Creates a new ResilientExecutor with the given retry and circuit breaker configurations.
   *
   * @param retryConfig the retry configuration
   * @param circuitBreakerConfig the circuit breaker configuration
   * @param name the name for the circuit breaker
   */
  public ResilientExecutor(
      RetryConfig retryConfig, CircuitBreakerConfig circuitBreakerConfig, String name) {
    this.retryExecutor =
        retryConfig.isEnabled()
            ? new DefaultRetryExecutor(retryConfig)
            : NoOpRetryExecutor.getInstance();

    this.circuitBreaker =
        circuitBreakerConfig.isEnabled()
            ? new DefaultCircuitBreaker(name, circuitBreakerConfig)
            : NoOpCircuitBreaker.getInstance();
  }

  /**
   * Creates a new ResilientExecutor with the given executors.
   *
   * @param retryExecutor the retry executor
   * @param circuitBreaker the circuit breaker
   */
  public ResilientExecutor(RetryExecutor retryExecutor, CircuitBreaker circuitBreaker) {
    this.retryExecutor = retryExecutor;
    this.circuitBreaker = circuitBreaker;
  }

  /**
   * Creates a ResilientExecutor with retry only.
   *
   * @param retryConfig the retry configuration
   * @return a new ResilientExecutor
   */
  public static ResilientExecutor withRetryOnly(RetryConfig retryConfig) {
    return new ResilientExecutor(retryConfig, CircuitBreakerConfig.disabled(), "default");
  }

  /**
   * Creates a ResilientExecutor with circuit breaker only.
   *
   * @param circuitBreakerConfig the circuit breaker configuration
   * @param name the name for the circuit breaker
   * @return a new ResilientExecutor
   */
  public static ResilientExecutor withCircuitBreakerOnly(
      CircuitBreakerConfig circuitBreakerConfig, String name) {
    return new ResilientExecutor(RetryConfig.disabled(), circuitBreakerConfig, name);
  }

  /**
   * Creates a disabled ResilientExecutor that executes operations directly.
   *
   * @return a disabled ResilientExecutor
   */
  public static ResilientExecutor disabled() {
    return new ResilientExecutor(NoOpRetryExecutor.getInstance(), NoOpCircuitBreaker.getInstance());
  }

  /**
   * Executes the given action with resilience support.
   *
   * @param <T> the return type
   * @param action the action to execute
   * @return the result of the action
   * @throws Exception if the action fails after all retry attempts or circuit is open
   */
  public <T> T execute(Supplier<T> action) throws Exception {
    return execute("operation", action);
  }

  /**
   * Executes the given action with resilience support.
   *
   * @param <T> the return type
   * @param operationName the name of the operation for logging
   * @param action the action to execute
   * @return the result of the action
   * @throws Exception if the action fails after all retry attempts or circuit is open
   */
  public <T> T execute(String operationName, Supplier<T> action) throws Exception {
    return circuitBreaker.execute(operationName, () -> retrying(operationName, action));
  }

  /**
   * Executes the given action with a fallback when all retries fail or circuit is open.
   *
   * @param <T> the return type
   * @param action the action to execute
   * @param fallback the fallback supplier
   * @return the result of the action or fallback
   */
  public <T> T executeWithFallback(Supplier<T> action, Supplier<T> fallback) {
    return executeWithFallback("operation", action, fallback);
  }

  /**
   * Executes the given action with a fallback when all retries fail or circuit is open.
   *
   * @param <T> the return type
   * @param operationName the name of the operation for logging
   * @param action the action to execute
   * @param fallback the fallback supplier; must not be null
   * @return the result of the action or fallback
   */
  public <T> T executeWithFallback(String operationName, Supplier<T> action, Supplier<T> fallback) {
    Objects.requireNonNull(fallback, "fallback must not be null");
    return circuitBreaker.executeWithFallback(() -> retrying(operationName, action), fallback);
  }

  /**
   * Runs the action through the retry executor, keeping the original failure visible.
   *
   * <p>{@link RetryExecutor#execute(String, Supplier)} is declared to throw checked exceptions even
   * though a {@link Supplier} can only raise unchecked ones, so the unchecked failure is rethrown
   * as-is and only a genuinely checked exception is wrapped.
   */
  private <T> T retrying(String operationName, Supplier<T> action) {
    try {
      return retryExecutor.execute(operationName, action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Operation '" + operationName + "' failed", e);
    }
  }

  /**
   * Executes the given runnable with resilience support.
   *
   * @param action the action to execute
   * @throws Exception if the action fails after all retry attempts or circuit is open
   */
  public void executeVoid(Runnable action) throws Exception {
    executeVoid("operation", action);
  }

  /**
   * Executes the given runnable with resilience support.
   *
   * @param operationName the name of the operation for logging
   * @param action the action to execute
   * @throws Exception if the action fails after all retry attempts or circuit is open
   */
  public void executeVoid(String operationName, Runnable action) throws Exception {
    execute(
        operationName,
        () -> {
          action.run();
          return null;
        });
  }

  /**
   * Returns the retry executor.
   *
   * @return the retry executor
   */
  public RetryExecutor getRetryExecutor() {
    return retryExecutor;
  }

  /**
   * Returns the circuit breaker.
   *
   * @return the circuit breaker
   */
  public CircuitBreaker getCircuitBreaker() {
    return circuitBreaker;
  }
}
