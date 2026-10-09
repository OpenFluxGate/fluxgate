package org.fluxgate.spring.properties;

import java.time.Duration;
import org.fluxgate.core.resilience.CircuitBreakerConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for FluxGate resilience features.
 *
 * <p>These properties configure retry and circuit breaker behavior for FluxGate operations.
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

    /**
     * Random jitter applied to each backoff, as a fraction of the computed delay. Default 0.2, so a
     * 100ms backoff waits between 80ms and 120ms.
     *
     * <p>Without jitter every instance that failed at the same moment retries at the same moment,
     * which reproduces the load spike that caused the failure. Set to 0 to disable.
     */
    private double jitterFactor = 0.2;

    /**
     * Whether a timeout is treated as retryable. Default false.
     *
     * <p>A timed out call may still be executing on the server, so retrying it is only safe for
     * idempotent operations.
     */
    private boolean retryOnTimeout = false;

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

    public double getJitterFactor() {
      return jitterFactor;
    }

    public void setJitterFactor(double jitterFactor) {
      this.jitterFactor = jitterFactor;
    }

    public boolean isRetryOnTimeout() {
      return retryOnTimeout;
    }

    public void setRetryOnTimeout(boolean retryOnTimeout) {
      this.retryOnTimeout = retryOnTimeout;
    }
  }

  /** Circuit breaker configuration properties. */
  public static class CircuitBreaker {

    /**
     * Whether circuit breaker is enabled. Default is true.
     *
     * <p>Enabled by default because retry is too: without a breaker a failing Redis was hit {@code
     * retry.max-attempts} times per request, so the rate limiter amplified the very outage it was
     * supposed to survive. An open circuit degrades immediately to {@code
     * fluxgate.ratelimit.fallback.mode} or {@code fluxgate.ratelimit.failure-behavior}, which is
     * also what the fail-closed defaults elsewhere assume.
     */
    private boolean enabled = true;

    /**
     * Number of <em>consecutive</em> failures before opening the circuit.
     *
     * <p>Not set by default, which leaves the sliding-window rule ({@link
     * #getFailureRateThreshold()} over {@link #getSlidingWindowSize()}) in charge. Setting it
     * forces the legacy consecutive-failure rule instead, so only set it when that is what you
     * want.
     */
    private Integer failureThreshold;

    /**
     * Number of recent calls the failure rate is computed over. Default 20.
     *
     * <p>A rate over a window opens the circuit for a dependency that fails most of the time but
     * not every time, which consecutive-failure counting never notices.
     */
    private int slidingWindowSize = 20;

    /** Percentage of failed calls in the window that opens the circuit. Default 50. */
    private int failureRateThreshold = 50;

    /**
     * Minimum number of calls in the window before the failure rate is evaluated. Default 10, so a
     * single early failure cannot open the circuit.
     */
    private int minimumNumberOfCalls = 10;

    /** Duration to wait in open state before transitioning to half-open. Default is 30s. */
    private Duration waitDurationInOpenState = Duration.ofSeconds(30);

    /** Number of calls permitted in half-open state. Default is 3. */
    private int permittedCallsInHalfOpenState = 3;

    /**
     * Fallback strategy when circuit is open.
     *
     * @deprecated inert. Whether an open circuit allows or denies the request is expressed by
     *     {@code fluxgate.ratelimit.failure-behavior} (ALLOW / DENY), which the filter, the aspect
     *     and the handler all read.
     */
    @Deprecated
    private CircuitBreakerConfig.FallbackStrategy fallback =
        CircuitBreakerConfig.FallbackStrategy.FAIL_OPEN;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public Integer getFailureThreshold() {
      return failureThreshold;
    }

    public void setFailureThreshold(Integer failureThreshold) {
      this.failureThreshold = failureThreshold;
    }

    public int getSlidingWindowSize() {
      return slidingWindowSize;
    }

    public void setSlidingWindowSize(int slidingWindowSize) {
      this.slidingWindowSize = slidingWindowSize;
    }

    public int getFailureRateThreshold() {
      return failureRateThreshold;
    }

    public void setFailureRateThreshold(int failureRateThreshold) {
      this.failureRateThreshold = failureRateThreshold;
    }

    public int getMinimumNumberOfCalls() {
      return minimumNumberOfCalls;
    }

    public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
      this.minimumNumberOfCalls = minimumNumberOfCalls;
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

    /**
     * @return the configured strategy
     * @deprecated see the {@code fallback} field
     */
    @Deprecated
    public CircuitBreakerConfig.FallbackStrategy getFallback() {
      return fallback;
    }

    /**
     * @param fallback the strategy to set
     * @deprecated see the {@code fallback} field
     */
    @Deprecated
    public void setFallback(CircuitBreakerConfig.FallbackStrategy fallback) {
      this.fallback = fallback;
    }
  }
}
