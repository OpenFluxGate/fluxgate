package org.fluxgate.core.resilience;

import java.util.function.Supplier;

/**
 * A no-operation implementation of {@link RetryExecutor} that executes actions without retry.
 *
 * <p>This implementation is useful when retry functionality is disabled or not needed.
 */
public class NoOpRetryExecutor implements RetryExecutor {

  private static final NoOpRetryExecutor INSTANCE = new NoOpRetryExecutor();

  private final RetryConfig config = RetryConfig.disabled();

  private NoOpRetryExecutor() {}

  /**
   * Returns the singleton instance.
   *
   * @return the NoOpRetryExecutor instance
   */
  public static NoOpRetryExecutor getInstance() {
    return INSTANCE;
  }

  @Override
  public <T> T execute(Supplier<T> action) {
    return action.get();
  }

  @Override
  public <T> T execute(String operationName, Supplier<T> action) {
    return action.get();
  }

  @Override
  public void executeVoid(Runnable action) {
    action.run();
  }

  @Override
  public void executeVoid(String operationName, Runnable action) {
    action.run();
  }

  @Override
  public RetryConfig getConfig() {
    return config;
  }
}
