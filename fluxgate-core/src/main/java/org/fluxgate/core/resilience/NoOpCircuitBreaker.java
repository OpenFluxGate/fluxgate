package org.fluxgate.core.resilience;

import java.util.function.Supplier;

/**
 * A no-operation implementation of {@link CircuitBreaker}.
 *
 * <p>This implementation executes actions directly without any circuit breaker logic. It is useful
 * when circuit breaker functionality is disabled.
 */
public class NoOpCircuitBreaker implements CircuitBreaker {

  private static final NoOpCircuitBreaker INSTANCE = new NoOpCircuitBreaker();

  private final CircuitBreakerConfig config = CircuitBreakerConfig.disabled();

  private NoOpCircuitBreaker() {}

  /**
   * Returns the singleton instance.
   *
   * @return the NoOpCircuitBreaker instance
   */
  public static NoOpCircuitBreaker getInstance() {
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
  public <T> T executeWithFallback(Supplier<T> action, Supplier<T> fallback) {
    try {
      return action.get();
    } catch (Exception e) {
      return fallback.get();
    }
  }

  @Override
  public State getState() {
    return State.CLOSED;
  }

  @Override
  public CircuitBreakerConfig getConfig() {
    return config;
  }

  @Override
  public void reset() {
    // No-op
  }
}
