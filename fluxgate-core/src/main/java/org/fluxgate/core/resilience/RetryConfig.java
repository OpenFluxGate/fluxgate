package org.fluxgate.core.resilience;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import org.fluxgate.core.exception.FluxgateConnectionException;
import org.fluxgate.core.exception.FluxgateException;
import org.fluxgate.core.exception.FluxgateTimeoutException;

/**
 * Configuration for retry behavior.
 *
 * <p>This class provides immutable configuration for retry operations, including the maximum number
 * of attempts, backoff timing, and which exceptions should trigger retries.
 *
 */
public class RetryConfig {

    private final boolean enabled;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final double multiplier;
    private final Duration maxBackoff;
    private final Set<Class<? extends Exception>> retryableExceptions;

    private RetryConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.maxAttempts = builder.maxAttempts;
        this.initialBackoff = builder.initialBackoff;
        this.multiplier = builder.multiplier;
        this.maxBackoff = builder.maxBackoff;
        this.retryableExceptions = Set.copyOf(builder.retryableExceptions);
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
     * Returns a disabled retry configuration.
     *
     * @return a RetryConfig with retry disabled
     */
    public static RetryConfig disabled() {
        return builder().enabled(false).build();
    }

    /**
     * Returns a default retry configuration.
     *
     * @return a RetryConfig with default settings
     */
    public static RetryConfig defaults() {
        return builder().build();
    }

    /**
     * Returns whether retry is enabled.
     *
     * @return true if retry is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the maximum number of attempts (including the initial attempt).
     *
     * @return the maximum number of attempts
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * Returns the initial backoff duration before the first retry.
     *
     * @return the initial backoff duration
     */
    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    /**
     * Returns the multiplier for exponential backoff.
     *
     * @return the backoff multiplier
     */
    public double getMultiplier() {
        return multiplier;
    }

    /**
     * Returns the maximum backoff duration.
     *
     * @return the maximum backoff duration
     */
    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    /**
     * Returns the set of exception classes that should trigger retries.
     *
     * @return an unmodifiable set of retryable exception classes
     */
    public Set<Class<? extends Exception>> getRetryableExceptions() {
        return retryableExceptions;
    }

    /**
     * Checks if the given exception should trigger a retry.
     *
     * @param exception the exception to check
     * @return true if the exception should trigger a retry
     */
    public boolean shouldRetry(Exception exception) {
        if (!enabled) {
            return false;
        }

        // Check if it's a FluxgateException with isRetryable
        if (exception instanceof FluxgateException fluxgateEx) {
            if (fluxgateEx.isRetryable()) {
                return true;
            }
        }

        // Check against configured retryable exceptions
        for (Class<? extends Exception> retryableClass : retryableExceptions) {
            if (retryableClass.isInstance(exception)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Calculates the backoff duration for the given attempt number.
     *
     * @param attempt the attempt number (1-based)
     * @return the backoff duration
     */
    public Duration calculateBackoff(int attempt) {
        if (attempt <= 1) {
            return initialBackoff;
        }

        long backoffMillis =
                (long) (initialBackoff.toMillis() * Math.pow(multiplier, attempt - 1));
        return Duration.ofMillis(Math.min(backoffMillis, maxBackoff.toMillis()));
    }

    /** Builder for creating RetryConfig instances. */
    public static class Builder {
        private boolean enabled = true;
        private int maxAttempts = 3;
        private Duration initialBackoff = Duration.ofMillis(100);
        private double multiplier = 2.0;
        private Duration maxBackoff = Duration.ofSeconds(2);
        private Set<Class<? extends Exception>> retryableExceptions = new HashSet<>();

        private Builder() {
            // Default retryable exceptions
            retryableExceptions.add(FluxgateConnectionException.class);
            retryableExceptions.add(FluxgateTimeoutException.class);
        }

        /**
         * Sets whether retry is enabled.
         *
         * @param enabled true to enable retry
         * @return this builder
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * Sets the maximum number of attempts.
         *
         * @param maxAttempts the maximum attempts (must be >= 1)
         * @return this builder
         */
        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * Sets the initial backoff duration.
         *
         * @param initialBackoff the initial backoff duration
         * @return this builder
         */
        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
            return this;
        }

        /**
         * Sets the backoff multiplier.
         *
         * @param multiplier the multiplier (must be >= 1.0)
         * @return this builder
         */
        public Builder multiplier(double multiplier) {
            if (multiplier < 1.0) {
                throw new IllegalArgumentException("multiplier must be >= 1.0");
            }
            this.multiplier = multiplier;
            return this;
        }

        /**
         * Sets the maximum backoff duration.
         *
         * @param maxBackoff the maximum backoff duration
         * @return this builder
         */
        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
            return this;
        }

        /**
         * Adds an exception class to the retryable set.
         *
         * @param exceptionClass the exception class to add
         * @return this builder
         */
        public Builder retryOn(Class<? extends Exception> exceptionClass) {
            this.retryableExceptions.add(exceptionClass);
            return this;
        }

        /**
         * Sets the retryable exception classes.
         *
         * @param exceptionClasses the exception classes
         * @return this builder
         */
        public Builder retryableExceptions(Set<Class<? extends Exception>> exceptionClasses) {
            this.retryableExceptions = new HashSet<>(exceptionClasses);
            return this;
        }

        /**
         * Builds the RetryConfig.
         *
         * @return a new RetryConfig instance
         */
        public RetryConfig build() {
            return new RetryConfig(this);
        }
    }
}
