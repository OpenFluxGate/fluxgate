package org.fluxgate.core.resilience;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A no-operation implementation of {@link CircuitBreaker}.
 *
 * <p>This implementation executes actions directly without any circuit breaker logic. It is useful
 * when circuit breaker functionality is disabled.
 *
 * <p>The contract of {@link CircuitBreaker} still holds: the circuit never opens, so {@link
 * #execute(String, Supplier)} never throws {@link CircuitBreakerOpenException} and {@link
 * #executeWithFallback(Supplier, Supplier)} only falls back when the action itself fails (an {@link
 * IgnoredCallException} is rethrown instead). Neither method ever returns {@code null} on behalf of
 * the caller.
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
    Objects.requireNonNull(fallback, "fallback must not be null");
    try {
      return action.get();
    } catch (IgnoredCallException e) {
      throw e;
    } catch (RuntimeException e) {
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
