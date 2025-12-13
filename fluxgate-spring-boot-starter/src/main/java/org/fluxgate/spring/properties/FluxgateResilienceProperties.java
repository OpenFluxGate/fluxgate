package org.fluxgate.spring.properties;

import java.time.Duration;

import org.fluxgate.core.resilience.CircuitBreakerConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for FluxGate resilience features.
 *
 * <p>These properties configure retry and circuit breaker behavior for FluxGate operations.
 *
 */
@ConfigurationProperties(prefix = "fluxgate.resilience")
public class FluxgateResilienceProperties {

    /** Retry configuration. */
    private final Retry retry = new Retry();

    /** Circuit breaker configuration. */
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();

    public Retry getRetry() {
        return retry;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /** Retry configuration properties. */
    public static class Retry {

        /** Whether retry is enabled. Default is true. */
        private boolean enabled = true;

        /** Maximum number of attempts (including initial attempt). Default is 3. */
        private int maxAttempts = 3;

        /** Initial backoff duration before first retry. Default is 100ms. */
        private Duration initialBackoff = Duration.ofMillis(100);

        /** Multiplier for exponential backoff. Default is 2.0. */
        private double multiplier = 2.0;

        /** Maximum backoff duration. Default is 2 seconds. */
        private Duration maxBackoff = Duration.ofSeconds(2);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }
    }

    /** Circuit breaker configuration properties. */
    public static class CircuitBreaker {

        /** Whether circuit breaker is enabled. Default is false. */
        private boolean enabled = false;

        /** Number of failures before opening the circuit. Default is 5. */
        private int failureThreshold = 5;

        /** Duration to wait in open state before transitioning to half-open. Default is 30s. */
        private Duration waitDurationInOpenState = Duration.ofSeconds(30);

        /** Number of calls permitted in half-open state. Default is 3. */
        private int permittedCallsInHalfOpenState = 3;

        /** Fallback strategy when circuit is open. Default is FAIL_OPEN. */
        private CircuitBreakerConfig.FallbackStrategy fallback =
                CircuitBreakerConfig.FallbackStrategy.FAIL_OPEN;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState;
        }

        public int getPermittedCallsInHalfOpenState() {
            return permittedCallsInHalfOpenState;
        }

        public void setPermittedCallsInHalfOpenState(int permittedCallsInHalfOpenState) {
            this.permittedCallsInHalfOpenState = permittedCallsInHalfOpenState;
        }

        public CircuitBreakerConfig.FallbackStrategy getFallback() {
            return fallback;
        }

        public void setFallback(CircuitBreakerConfig.FallbackStrategy fallback) {
            this.fallback = fallback;
        }
    }
}
