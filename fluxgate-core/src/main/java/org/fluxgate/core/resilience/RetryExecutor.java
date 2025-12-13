package org.fluxgate.core.resilience;

import java.util.function.Supplier;

/**
 * Executor that provides retry capabilities for operations.
 *
 * <p>This interface defines the contract for executing operations with automatic retry on failure.
 * Implementations should respect the configured retry policy including max attempts, backoff
 * strategy, and retryable exceptions.
 */
public interface RetryExecutor {

  /**
   * Executes the given action with retry support.
   *
   * <p>If the action fails with a retryable exception, it will be retried according to the
   * configured retry policy. If all retries are exhausted, the last exception will be thrown.
   *
   * @param <T> the return type
   * @param action the action to execute
   * @return the result of the action
   * @throws Exception if the action fails after all retry attempts
   */
  <T> T execute(Supplier<T> action) throws Exception;

  /**
   * Executes the given action with retry support and a custom operation name.
   *
   * <p>The operation name is used for logging and metrics purposes.
   *
   * @param <T> the return type
   * @param operationName the name of the operation for logging
   * @param action the action to execute
   * @return the result of the action
   * @throws Exception if the action fails after all retry attempts
   */
  <T> T execute(String operationName, Supplier<T> action) throws Exception;

  /**
   * Executes the given runnable with retry support.
   *
   * @param action the action to execute
   * @throws Exception if the action fails after all retry attempts
   */
  void executeVoid(Runnable action) throws Exception;

  /**
   * Executes the given runnable with retry support and a custom operation name.
   *
   * @param operationName the name of the operation for logging
   * @param action the action to execute
   * @throws Exception if the action fails after all retry attempts
   */
  void executeVoid(String operationName, Runnable action) throws Exception;

  /**
   * Returns the retry configuration used by this executor.
   *
   * @return the retry configuration
   */
  RetryConfig getConfig();
}
