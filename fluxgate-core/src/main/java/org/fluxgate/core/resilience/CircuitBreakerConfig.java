package org.fluxgate.core.resilience;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for circuit breaker behavior.
 *
 * <p>The circuit breaker pattern prevents an application from repeatedly trying to execute an
 * operation that's likely to fail, allowing it to continue without waiting for the fault to be
 * fixed.
 *
 * <p>Two independent conditions open the circuit; whichever is met first wins:
 *
 * <ul>
 *   <li><b>Failure rate</b> over a count-based sliding window of the last {@link
 *       #getSlidingWindowSize()} calls. The rate is only evaluated once at least {@link
 *       #getMinimumNumberOfCalls()} calls have been recorded, and the circuit opens when the
 *       failure percentage reaches {@link #getFailureRateThreshold()}. This is the recommended
 *       model: it also trips on intermittent failures, which a consecutive-failure counter never
 *       does.
 *   <li><b>Legacy absolute threshold</b> of {@link #getFailureThreshold()} <em>consecutive</em>
 *       failures. It is only evaluated when {@link Builder#failureThreshold(int)} was called
 *       explicitly, so configurations written against the previous behaviour keep working
 *       unchanged.
 * </ul>
 */
public class CircuitBreakerConfig {

  private final boolean enabled;
  private final int failureThreshold;
  private final boolean failureThresholdExplicit;
  private final int slidingWindowSize;
  private final int failureRateThreshold;
  private final int minimumNumberOfCalls;
  private final Duration waitDurationInOpenState;
  private final int permittedCallsInHalfOpenState;
  private final FallbackStrategy fallbackStrategy;

  private CircuitBreakerConfig(Builder builder) {
    this.enabled = builder.enabled;
    this.failureThreshold = builder.failureThreshold;
    this.failureThresholdExplicit = builder.failureThresholdExplicit;
    this.slidingWindowSize = builder.slidingWindowSize;
    this.failureRateThreshold = builder.failureRateThreshold;
    this.minimumNumberOfCalls = builder.minimumNumberOfCalls;
    this.waitDurationInOpenState = builder.waitDurationInOpenState;
    this.permittedCallsInHalfOpenState = builder.permittedCallsInHalfOpenState;
    this.fallbackStrategy = builder.fallbackStrategy;
  }

  /**
   * Returns a new builder with default settings.
   *
   * @return a new Builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns a disabled circuit breaker configuration.
   *
   * @return a CircuitBreakerConfig with circuit breaker disabled
   */
  public static CircuitBreakerConfig disabled() {
    return builder().enabled(false).build();
  }

  /**
   * Returns a default circuit breaker configuration.
   *
   * @return a CircuitBreakerConfig with default settings
   */
  public static CircuitBreakerConfig defaults() {
    return builder().build();
  }

  /**
   * Returns whether the circuit breaker is enabled.
   *
   * @return true if circuit breaker is enabled
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Returns the legacy absolute threshold of consecutive failures that opens the circuit.
   *
   * <p>Only honoured when {@link #isFailureThresholdExplicit()} is true.
   *
   * @return the number of consecutive failures before opening the circuit
   */
  public int getFailureThreshold() {
    return failureThreshold;
  }

  /**
   * Returns whether the legacy absolute failure threshold was configured explicitly.
   *
   * @return true if {@link Builder#failureThreshold(int)} was called
   */
  public boolean isFailureThresholdExplicit() {
    return failureThresholdExplicit;
  }

  /**
   * Returns the number of calls kept in the count-based sliding window.
   *
   * @return the sliding window size in calls
   */
  public int getSlidingWindowSize() {
    return slidingWindowSize;
  }

  /**
   * Returns the failure percentage at or above which the circuit opens.
   *
   * @return the failure rate threshold in percent (1-100)
   */
  public int getFailureRateThreshold() {
    return failureRateThreshold;
  }

  /**
   * Returns the number of recorded calls required before the failure rate is evaluated.
   *
   * @return the minimum number of calls
   */
  public int getMinimumNumberOfCalls() {
    return minimumNumberOfCalls;
  }

  /**
   * Returns the duration to wait in open state before transitioning to half-open.
   *
   * @return the wait duration in open state
   */
  public Duration getWaitDurationInOpenState() {
    return waitDurationInOpenState;
  }

  /**
   * Returns the number of calls permitted in half-open state.
   *
   * <p>This value has two roles: it is the number of concurrent trial calls admitted while the
   * circuit is half-open, and the number of consecutive successful trials required to close the
   * circuit again.
   *
   * @return the number of permitted calls in half-open state
   */
  public int getPermittedCallsInHalfOpenState() {
    return permittedCallsInHalfOpenState;
  }

  /**
   * Returns the fallback strategy when the circuit is open.
   *
   * <p>Kept for configuration compatibility; see {@link FallbackStrategy} for what it still
   * influences.
   *
   * @return the fallback strategy
   */
  public FallbackStrategy getFallbackStrategy() {
    return fallbackStrategy;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof CircuitBreakerConfig)) return false;
    CircuitBreakerConfig that = (CircuitBreakerConfig) o;
    return enabled == that.enabled
        && failureThreshold == that.failureThreshold
        && failureThresholdExplicit == that.failureThresholdExplicit
        && slidingWindowSize == that.slidingWindowSize
        && failureRateThreshold == that.failureRateThreshold
        && minimumNumberOfCalls == that.minimumNumberOfCalls
        && permittedCallsInHalfOpenState == that.permittedCallsInHalfOpenState
        && Objects.equals(waitDurationInOpenState, that.waitDurationInOpenState)
        && fallbackStrategy == that.fallbackStrategy;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        enabled,
        failureThreshold,
        failureThresholdExplicit,
        slidingWindowSize,
        failureRateThreshold,
        minimumNumberOfCalls,
        permittedCallsInHalfOpenState,
        waitDurationInOpenState,
        fallbackStrategy);
  }

  @Override
  public String toString() {
    return "CircuitBreakerConfig{"
        + "enabled="
        + enabled
        + ", failureThreshold="
        + failureThreshold
        + (failureThresholdExplicit ? "" : " (unused)")
        + ", slidingWindowSize="
        + slidingWindowSize
        + ", failureRateThreshold="
        + failureRateThreshold
        + ", minimumNumberOfCalls="
        + minimumNumberOfCalls
        + ", waitDurationInOpenState="
        + waitDurationInOpenState
        + ", permittedCallsInHalfOpenState="
        + permittedCallsInHalfOpenState
        + ", fallbackStrategy="
        + fallbackStrategy
        + '}';
  }

  /**
   * Fallback strategy when circuit is open.
   *
   * <p>This enum only selects how an <em>open</em> circuit is reported to code that did not supply
   * a fallback. Since {@link CircuitBreaker#execute(String, java.util.function.Supplier)} always
   * throws {@link CircuitBreakerOpenException} while open and {@link
   * CircuitBreaker#executeWithFallback(java.util.function.Supplier, java.util.function.Supplier)}
   * always returns the caller's fallback, both values behave identically today. It is retained
   * because starters expose it as a property (<code>
   * fluxgate.resilience.circuit-breaker.fallback</code>); callers express fail-open by passing an
   * "allow" fallback to {@code executeWithFallback}, and fail-closed by passing a "deny" fallback
   * or by using {@code execute}.
   */
  public enum FallbackStrategy {
    /** Allow requests to pass through (fail-open). */
    FAIL_OPEN,

    /** Reject requests immediately (fail-closed). */
    FAIL_CLOSED
  }

  /** Builder for creating CircuitBreakerConfig instances. */
  public static class Builder {
    private boolean enabled = false; // Disabled by default
    private int failureThreshold = 5;
    private boolean failureThresholdExplicit = false;
    private int slidingWindowSize = 20;
    private int failureRateThreshold = 50;
    private int minimumNumberOfCalls = 10;
    private Duration waitDurationInOpenState = Duration.ofSeconds(30);
    private int permittedCallsInHalfOpenState = 3;
    private FallbackStrategy fallbackStrategy = FallbackStrategy.FAIL_OPEN;

    private Builder() {}

    /**
     * Sets whether the circuit breaker is enabled.
     *
     * @param enabled true to enable the circuit breaker
     * @return this builder
     */
    public Builder enabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    /**
     * Sets the legacy absolute threshold of consecutive failures.
     *
     * <p>Calling this method opts the circuit breaker into the legacy condition in addition to the
     * sliding-window failure rate; either condition opens the circuit.
     *
     * @param failureThreshold the number of consecutive failures to trigger open state
     * @return this builder
     */
    public Builder failureThreshold(int failureThreshold) {
      if (failureThreshold < 1) {
        throw new IllegalArgumentException("failureThreshold must be >= 1");
      }
      this.failureThreshold = failureThreshold;
      this.failureThresholdExplicit = true;
      return this;
    }

    /**
     * Sets the size of the count-based sliding window.
     *
     * @param slidingWindowSize the number of calls to keep (must be at least 1)
     * @return this builder
     */
    public Builder slidingWindowSize(int slidingWindowSize) {
      if (slidingWindowSize < 1) {
        throw new IllegalArgumentException("slidingWindowSize must be >= 1");
      }
      this.slidingWindowSize = slidingWindowSize;
      return this;
    }

    /**
     * Sets the failure percentage at or above which the circuit opens.
     *
     * @param failureRateThreshold the failure rate threshold in percent (1-100)
     * @return this builder
     */
    public Builder failureRateThreshold(int failureRateThreshold) {
      if (failureRateThreshold < 1 || failureRateThreshold > 100) {
        throw new IllegalArgumentException("failureRateThreshold must be between 1 and 100");
      }
      this.failureRateThreshold = failureRateThreshold;
      return this;
    }

    /**
     * Sets the number of recorded calls required before the failure rate is evaluated.
     *
     * @param minimumNumberOfCalls the minimum number of calls (must be at least 1)
     * @return this builder
     */
    public Builder minimumNumberOfCalls(int minimumNumberOfCalls) {
      if (minimumNumberOfCalls < 1) {
        throw new IllegalArgumentException("minimumNumberOfCalls must be >= 1");
      }
      this.minimumNumberOfCalls = minimumNumberOfCalls;
      return this;
    }

    /**
     * Sets the wait duration in open state.
     *
     * @param waitDuration the duration to wait before transitioning to half-open
     * @return this builder
     * @throws NullPointerException if waitDuration is null
     * @throws IllegalArgumentException if waitDuration is negative
     */
    public Builder waitDurationInOpenState(Duration waitDuration) {
      Objects.requireNonNull(waitDuration, "waitDurationInOpenState must not be null");
      if (waitDuration.isNegative()) {
        throw new IllegalArgumentException("waitDurationInOpenState must not be negative");
      }
      this.waitDurationInOpenState = waitDuration;
      return this;
    }

    /**
     * Sets the number of permitted calls in half-open state.
     *
     * @param permittedCalls the number of calls to allow in half-open state
     * @return this builder
     */
    public Builder permittedCallsInHalfOpenState(int permittedCalls) {
      if (permittedCalls < 1) {
        throw new IllegalArgumentException("permittedCallsInHalfOpenState must be >= 1");
      }
      this.permittedCallsInHalfOpenState = permittedCalls;
      return this;
    }

    /**
     * Sets the fallback strategy.
     *
     * @param strategy the fallback strategy
     * @return this builder
     * @throws NullPointerException if strategy is null
     */
    public Builder fallbackStrategy(FallbackStrategy strategy) {
      this.fallbackStrategy = Objects.requireNonNull(strategy, "fallbackStrategy must not be null");
      return this;
    }

    /**
     * Builds the CircuitBreakerConfig.
     *
     * @return a new CircuitBreakerConfig instance
     */
    public CircuitBreakerConfig build() {
      if (minimumNumberOfCalls > slidingWindowSize) {
        throw new IllegalArgumentException(
            "minimumNumberOfCalls must not exceed slidingWindowSize ("
                + minimumNumberOfCalls
                + " > "
                + slidingWindowSize
                + ")");
      }
      return new CircuitBreakerConfig(this);
    }
  }
}
