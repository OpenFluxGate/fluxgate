package org.fluxgate.core.resilience;

import java.time.Duration;

/**
 * Configuration for circuit breaker behavior.
 *
 * <p>The circuit breaker pattern prevents an application from repeatedly trying to execute an
 * operation that's likely to fail, allowing it to continue without waiting for the fault to be
 * fixed.
 *
 */
public class CircuitBreakerConfig {

    private final boolean enabled;
    private final int failureThreshold;
    private final Duration waitDurationInOpenState;
    private final int permittedCallsInHalfOpenState;
    private final FallbackStrategy fallbackStrategy;

    private CircuitBreakerConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.failureThreshold = builder.failureThreshold;
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
     * Returns the failure threshold that triggers the circuit to open.
     *
     * @return the number of failures before opening the circuit
     */
    public int getFailureThreshold() {
        return failureThreshold;
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
     * @return the number of permitted calls in half-open state
     */
    public int getPermittedCallsInHalfOpenState() {
        return permittedCallsInHalfOpenState;
    }

    /**
     * Returns the fallback strategy when the circuit is open.
     *
     * @return the fallback strategy
     */
    public FallbackStrategy getFallbackStrategy() {
        return fallbackStrategy;
    }

    /** Fallback strategy when circuit is open. */
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
         * Sets the failure threshold.
         *
         * @param failureThreshold the number of failures to trigger open state
         * @return this builder
         */
        public Builder failureThreshold(int failureThreshold) {
            if (failureThreshold < 1) {
                throw new IllegalArgumentException("failureThreshold must be >= 1");
            }
            this.failureThreshold = failureThreshold;
            return this;
        }

        /**
         * Sets the wait duration in open state.
         *
         * @param waitDuration the duration to wait before transitioning to half-open
         * @return this builder
         */
        public Builder waitDurationInOpenState(Duration waitDuration) {
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
         */
        public Builder fallbackStrategy(FallbackStrategy strategy) {
            this.fallbackStrategy = strategy;
            return this;
        }

        /**
         * Builds the CircuitBreakerConfig.
         *
         * @return a new CircuitBreakerConfig instance
         */
        public CircuitBreakerConfig build() {
            return new CircuitBreakerConfig(this);
        }
    }
}
