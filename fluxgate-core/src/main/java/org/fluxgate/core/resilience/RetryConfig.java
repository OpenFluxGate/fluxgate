package org.fluxgate.core.resilience;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import org.fluxgate.core.exception.FluxgateConnectionException;
import org.fluxgate.core.exception.FluxgateException;
import org.fluxgate.core.exception.FluxgateTimeoutException;

/**
 * Configuration for retry behavior.
 *
 * <p>This class provides immutable configuration for retry operations, including the maximum number
 * of attempts, backoff timing, and which exceptions should trigger retries.
 */
public class RetryConfig {

  private final boolean enabled;
  private final int maxAttempts;
  private final Duration initialBackoff;
  private final double multiplier;
  private final Duration maxBackoff;
  private final double jitterFactor;
  private final boolean retryOnTimeout;
  private final Set<Class<? extends Exception>> retryableExceptions;

  private RetryConfig(Builder builder) {
    this.enabled = builder.enabled;
    this.maxAttempts = builder.maxAttempts;
    this.initialBackoff = builder.initialBackoff;
    this.multiplier = builder.multiplier;
    this.maxBackoff = builder.maxBackoff;
    this.jitterFactor = builder.jitterFactor;
    this.retryOnTimeout = builder.retryOnTimeout;
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
   * Returns the relative jitter applied to each computed backoff.
   *
   * @return the jitter factor, where {@code 0.2} means the backoff varies by up to ±20%
   */
  public double getJitterFactor() {
    return jitterFactor;
  }

  /**
   * Returns whether timeouts are retryable.
   *
   * @return true if timed-out operations may be retried
   */
  public boolean isRetryOnTimeout() {
    return retryOnTimeout;
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
   * <p>The decision is made in this order:
   *
   * <ol>
   *   <li>Retry disabled - never retry.
   *   <li>An {@link IgnoredCallException} - never retry; it describes the call, not the resource.
   *   <li>A timeout ({@link FluxgateTimeoutException} or {@link TimeoutException}, either the
   *       exception itself or anywhere in its cause chain) while {@link #isRetryOnTimeout()} is
   *       false - never retry. A timed-out call may well have been executed by the server, so
   *       retrying it double-consumes tokens for non-idempotent operations.
   *   <li>A {@link FluxgateException} - its {@link FluxgateException#isRetryable()} is the final
   *       answer. The per-instance verdict wins over the class-based allow-list below, which would
   *       otherwise override an exception that explicitly declared itself non-retryable. For
   *       example a {@link org.fluxgate.core.exception.RedisConnectionException} is retried only
   *       when it failed while connecting; a failure during a command (or of unknown phase) may
   *       already have consumed tokens on Redis and is not retried.
   *   <li>Otherwise, whether the exception is an instance of one of {@link
   *       #getRetryableExceptions()}.
   * </ol>
   *
   * @param exception the exception to check
   * @return true if the exception should trigger a retry
   */
  public boolean shouldRetry(Exception exception) {
    if (!enabled) {
      return false;
    }

    // a call the circuit breaker ignores fails the same way on every attempt
    if (exception instanceof IgnoredCallException) {
      return false;
    }

    if (!retryOnTimeout && isTimeout(exception)) {
      return false;
    }

    // The instance-level verdict of the FluxGate hierarchy is authoritative in both directions.
    if (exception instanceof FluxgateException) {
      return ((FluxgateException) exception).isRetryable();
    }

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
   * <p>The exponential base ({@code initialBackoff * multiplier^(attempt-1)}, capped at {@link
   * #getMaxBackoff()}) is spread by ±{@link #getJitterFactor()} so that every node retrying after a
   * shared outage does not hit the recovering dependency at the same instant. The returned duration
   * never exceeds {@code maxBackoff} and is never negative.
   *
   * @param attempt the attempt number (1-based)
   * @return the backoff duration
   */
  public Duration calculateBackoff(int attempt) {
    long maxBackoffMillis = maxBackoff.toMillis();
    long baseMillis =
        attempt <= 1
            ? initialBackoff.toMillis()
            : (long) (initialBackoff.toMillis() * Math.pow(multiplier, attempt - 1));
    baseMillis = Math.min(baseMillis, maxBackoffMillis);

    if (jitterFactor <= 0.0) {
      return Duration.ofMillis(baseMillis);
    }

    double spread = ThreadLocalRandom.current().nextDouble(-jitterFactor, jitterFactor);
    long jitteredMillis = Math.round(baseMillis * (1.0 + spread));
    return Duration.ofMillis(Math.max(0L, Math.min(jitteredMillis, maxBackoffMillis)));
  }

  /** Whether the exception or any of its causes is a timeout; guards against cause cycles. */
  private static boolean isTimeout(Exception exception) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable t = exception; t != null && seen.add(t); t = t.getCause()) {
      if (t instanceof FluxgateTimeoutException || t instanceof TimeoutException) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RetryConfig)) return false;
    RetryConfig that = (RetryConfig) o;
    return enabled == that.enabled
        && maxAttempts == that.maxAttempts
        && Double.compare(multiplier, that.multiplier) == 0
        && Double.compare(jitterFactor, that.jitterFactor) == 0
        && retryOnTimeout == that.retryOnTimeout
        && Objects.equals(initialBackoff, that.initialBackoff)
        && Objects.equals(maxBackoff, that.maxBackoff)
        && retryableExceptions.equals(that.retryableExceptions);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        enabled,
        maxAttempts,
        initialBackoff,
        multiplier,
        maxBackoff,
        jitterFactor,
        retryOnTimeout,
        retryableExceptions);
  }

  @Override
  public String toString() {
    return "RetryConfig{"
        + "enabled="
        + enabled
        + ", maxAttempts="
        + maxAttempts
        + ", initialBackoff="
        + initialBackoff
        + ", multiplier="
        + multiplier
        + ", maxBackoff="
        + maxBackoff
        + ", jitterFactor="
        + jitterFactor
        + ", retryOnTimeout="
        + retryOnTimeout
        + ", retryableExceptions="
        + retryableExceptions
        + '}';
  }

  /** Builder for creating RetryConfig instances. */
  public static class Builder {
    private boolean enabled = true;
    private int maxAttempts = 3;
    private Duration initialBackoff = Duration.ofMillis(100);
    private double multiplier = 2.0;
    private Duration maxBackoff = Duration.ofSeconds(2);
    private double jitterFactor = 0.2;
    private boolean retryOnTimeout = false;
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
     * @param maxAttempts the maximum attempts (must be at least 1)
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
     * @throws NullPointerException if initialBackoff is null
     * @throws IllegalArgumentException if initialBackoff is negative
     */
    public Builder initialBackoff(Duration initialBackoff) {
      this.initialBackoff = requireNonNegative(initialBackoff, "initialBackoff");
      return this;
    }

    /**
     * Sets the backoff multiplier.
     *
     * @param multiplier the multiplier (must be at least 1.0)
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
     * @throws NullPointerException if maxBackoff is null
     * @throws IllegalArgumentException if maxBackoff is negative
     */
    public Builder maxBackoff(Duration maxBackoff) {
      this.maxBackoff = requireNonNegative(maxBackoff, "maxBackoff");
      return this;
    }

    /**
     * Sets the relative jitter applied to each computed backoff.
     *
     * @param jitterFactor the jitter factor, {@code 0.0} to disable jitter (must be within {@code
     *     [0.0, 1.0)})
     * @return this builder
     */
    public Builder jitterFactor(double jitterFactor) {
      if (jitterFactor < 0.0 || jitterFactor >= 1.0) {
        throw new IllegalArgumentException("jitterFactor must be >= 0.0 and < 1.0");
      }
      this.jitterFactor = jitterFactor;
      return this;
    }

    /**
     * Sets whether timed-out operations may be retried.
     *
     * <p>Leave this disabled for non-idempotent operations such as token consumption: a timeout
     * does not tell you whether the server executed the call, so a retry may consume twice.
     *
     * @param retryOnTimeout true to retry timeouts
     * @return this builder
     */
    public Builder retryOnTimeout(boolean retryOnTimeout) {
      this.retryOnTimeout = retryOnTimeout;
      return this;
    }

    /**
     * Adds an exception class to the retryable set.
     *
     * @param exceptionClass the exception class to add
     * @return this builder
     */
    public Builder retryOn(Class<? extends Exception> exceptionClass) {
      this.retryableExceptions.add(
          Objects.requireNonNull(exceptionClass, "exceptionClass must not be null"));
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

    private static Duration requireNonNegative(Duration value, String name) {
      Objects.requireNonNull(value, name + " must not be null");
      if (value.isNegative()) {
        throw new IllegalArgumentException(name + " must not be negative");
      }
      return value;
    }
  }
}
