package org.fluxgate.core.resilience;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link CircuitBreaker}.
 *
 * <p>Failures are tracked two ways, and either condition opens the circuit: the failure rate over a
 * count-based sliding window of the last {@link CircuitBreakerConfig#getSlidingWindowSize()} calls,
 * and - when configured explicitly - the legacy absolute count of consecutive failures. See {@link
 * CircuitBreakerConfig} for the exact rules.
 *
 * <p>While the circuit is HALF_OPEN, at most {@link
 * CircuitBreakerConfig#getPermittedCallsInHalfOpenState()} trial calls run concurrently; calls that
 * cannot obtain a trial permit are treated exactly like calls arriving at an open circuit. A single
 * failing trial reopens the circuit and discards the remaining permits, so a dependency that is
 * still down is never flooded by the requests that queued up during the wait duration.
 */
public class DefaultCircuitBreaker implements CircuitBreaker {

  private static final Logger log = LoggerFactory.getLogger(DefaultCircuitBreaker.class);

  private final String name;
  private final CircuitBreakerConfig config;
  private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
  private final AtomicInteger failureCount = new AtomicInteger(0);
  private final AtomicInteger halfOpenSuccessCount = new AtomicInteger(0);
  private final AtomicReference<Semaphore> halfOpenPermits = new AtomicReference<>();
  private final SlidingWindow callWindow;
  private final Object transitionLock = new Object();
  private volatile Instant openedAt;

  /**
   * Creates a new DefaultCircuitBreaker with the given name and configuration.
   *
   * @param name the name of the circuit breaker
   * @param config the circuit breaker configuration
   */
  public DefaultCircuitBreaker(String name, CircuitBreakerConfig config) {
    this.name = Objects.requireNonNull(name, "name must not be null");
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.callWindow = new SlidingWindow(config.getSlidingWindowSize());
  }

  /**
   * Creates a new DefaultCircuitBreaker with default configuration.
   *
   * @param name the name of the circuit breaker
   * @return a new DefaultCircuitBreaker
   */
  public static DefaultCircuitBreaker withDefaults(String name) {
    return new DefaultCircuitBreaker(name, CircuitBreakerConfig.defaults());
  }

  @Override
  public <T> T execute(Supplier<T> action) throws Exception {
    return execute("operation", action);
  }

  @Override
  public <T> T execute(String operationName, Supplier<T> action) throws Exception {
    if (!config.isEnabled()) {
      return action.get();
    }

    Admission admission = tryAdmit();
    if (!admission.permitted) {
      log.debug("Circuit breaker '{}' is open, rejecting '{}'", name, operationName);
      throw new CircuitBreakerOpenException(name);
    }

    try {
      T result = action.get();
      onSuccess();
      return result;
    } catch (Exception e) {
      onFailure(e);
      throw e;
    } finally {
      admission.release();
    }
  }

  @Override
  public <T> T executeWithFallback(Supplier<T> action, Supplier<T> fallback) {
    Objects.requireNonNull(fallback, "fallback must not be null");

    if (!config.isEnabled()) {
      try {
        return action.get();
      } catch (RuntimeException e) {
        return fallback.get();
      }
    }

    Admission admission = tryAdmit();
    if (!admission.permitted) {
      log.debug("Circuit breaker '{}' is open, using fallback", name);
      return fallback.get();
    }

    try {
      T result = action.get();
      onSuccess();
      return result;
    } catch (RuntimeException e) {
      onFailure(e);
      return fallback.get();
    } finally {
      admission.release();
    }
  }

  @Override
  public State getState() {
    return state.get();
  }

  @Override
  public CircuitBreakerConfig getConfig() {
    return config;
  }

  @Override
  public void reset() {
    synchronized (transitionLock) {
      state.set(State.CLOSED);
      failureCount.set(0);
      halfOpenSuccessCount.set(0);
      halfOpenPermits.set(null);
      callWindow.reset();
      openedAt = null;
    }
    log.info("Circuit breaker '{}' has been reset", name);
  }

  /**
   * Returns the name of this circuit breaker.
   *
   * @return the circuit breaker name
   */
  public String getName() {
    return name;
  }

  /**
   * Returns the current number of consecutive failures.
   *
   * <p>This counter feeds the legacy absolute threshold and is reset by any success. The
   * sliding-window failure rate is reported by {@link #getFailureRate()}.
   *
   * @return the consecutive failure count
   */
  public int getFailureCount() {
    return failureCount.get();
  }

  /**
   * Returns the number of calls currently held in the sliding window.
   *
   * @return the number of recorded calls, at most the configured sliding window size
   */
  public int getRecordedCalls() {
    return callWindow.getRecordedCalls();
  }

  /**
   * Returns the failure percentage over the sliding window.
   *
   * @return the failure rate in percent, or {@code 0.0} when no call has been recorded
   */
  public double getFailureRate() {
    return callWindow.getFailureRate();
  }

  /**
   * Moves an open circuit to HALF_OPEN when the wait duration has elapsed.
   *
   * <p>Package-private on purpose: production code reaches this transition through the execute
   * path, so that {@link #getState()} stays a pure read. Tests use it to reach HALF_OPEN
   * deterministically.
   *
   * @return true if the circuit is HALF_OPEN when this call returns
   */
  boolean tryTransitionToHalfOpen() {
    if (state.get() == State.HALF_OPEN) {
      return true;
    }

    // Serialised so that the trial permits are published before the state flips: a thread that
    // observes HALF_OPEN is then guaranteed to see the permits and is not turned away spuriously.
    synchronized (transitionLock) {
      State currentState = state.get();
      if (currentState == State.HALF_OPEN) {
        return true;
      }
      if (currentState != State.OPEN || !hasWaitDurationElapsed()) {
        return false;
      }

      halfOpenSuccessCount.set(0);
      halfOpenPermits.set(new Semaphore(config.getPermittedCallsInHalfOpenState()));
      if (!state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
        return false;
      }
      log.info("Circuit breaker '{}' transitioning from OPEN to HALF_OPEN", name);
      return true;
    }
  }

  private boolean hasWaitDurationElapsed() {
    Instant openedAtSnapshot = openedAt;
    if (openedAtSnapshot == null) {
      return false;
    }
    return Instant.now().isAfter(openedAtSnapshot.plus(config.getWaitDurationInOpenState()));
  }

  /**
   * Decides whether the current call may touch the protected resource.
   *
   * @return an admission carrying the trial permit to release afterwards, if any
   */
  private Admission tryAdmit() {
    State currentState = state.get();

    if (currentState == State.OPEN) {
      tryTransitionToHalfOpen();
      currentState = state.get();
      if (currentState == State.OPEN) {
        return Admission.REJECTED;
      }
    }

    if (currentState == State.HALF_OPEN) {
      Semaphore permits = halfOpenPermits.get();
      if (permits == null || !permits.tryAcquire()) {
        log.debug("Circuit breaker '{}' is half-open and out of trial permits", name);
        return Admission.REJECTED;
      }
      return new Admission(permits);
    }

    return Admission.ADMITTED;
  }

  private void onSuccess() {
    State currentState = state.get();

    if (currentState == State.HALF_OPEN) {
      int successes = halfOpenSuccessCount.incrementAndGet();
      if (successes >= config.getPermittedCallsInHalfOpenState()) {
        if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
          log.info("Circuit breaker '{}' transitioning from HALF_OPEN to CLOSED", name);
          failureCount.set(0);
          callWindow.reset();
          halfOpenPermits.set(null);
          openedAt = null;
        }
      }
    } else if (currentState == State.CLOSED) {
      failureCount.set(0);
      callWindow.record(false);
    }
  }

  private void onFailure(Exception e) {
    State currentState = state.get();

    if (currentState == State.HALF_OPEN) {
      if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
        log.warn(
            "Circuit breaker '{}' transitioning from HALF_OPEN to OPEN after failure: {}",
            name,
            e.getMessage());
        openTheCircuit();
      }
    } else if (currentState == State.CLOSED) {
      int failures = failureCount.incrementAndGet();
      callWindow.record(true);

      boolean absoluteThresholdReached =
          config.isFailureThresholdExplicit() && failures >= config.getFailureThreshold();
      boolean failureRateExceeded =
          callWindow.isFailureRateExceeded(
              config.getMinimumNumberOfCalls(), config.getFailureRateThreshold());

      if ((absoluteThresholdReached || failureRateExceeded)
          && state.compareAndSet(State.CLOSED, State.OPEN)) {
        log.warn(
            "Circuit breaker '{}' transitioning from CLOSED to OPEN "
                + "(consecutiveFailures={}, failureRate={}% over {} calls)",
            name,
            failures,
            String.format("%.1f", callWindow.getFailureRate()),
            callWindow.getRecordedCalls());
        openTheCircuit();
      }
    }
  }

  /** Starts the open-state timer and drops any state that only applies to a live circuit. */
  private void openTheCircuit() {
    openedAt = Instant.now();
    halfOpenPermits.set(null);
    halfOpenSuccessCount.set(0);
    failureCount.set(0);
    callWindow.reset();
  }

  /** Whether a call was admitted, plus the HALF_OPEN trial permit it has to give back. */
  private static final class Admission {

    private static final Admission REJECTED = new Admission(false, null);
    private static final Admission ADMITTED = new Admission(true, null);

    private final boolean permitted;
    private final Semaphore trialPermits;

    private Admission(Semaphore trialPermits) {
      this(true, trialPermits);
    }

    private Admission(boolean permitted, Semaphore trialPermits) {
      this.permitted = permitted;
      this.trialPermits = trialPermits;
    }

    /**
     * Returns the trial permit to the semaphore it came from.
     *
     * <p>Releasing to the captured instance rather than to the current one keeps a reopened circuit
     * from inheriting permits that were handed out before it reopened.
     */
    private void release() {
      if (trialPermits != null) {
        trialPermits.release();
      }
    }
  }

  /**
   * Count-based sliding window of call outcomes.
   *
   * <p>Deliberately a plain synchronized ring buffer: the critical section is a handful of array
   * writes, and a shared window is what makes the failure rate meaningful across threads.
   */
  private static final class SlidingWindow {

    private final boolean[] outcomes;
    private int cursor;
    private int recorded;
    private int failures;

    private SlidingWindow(int size) {
      this.outcomes = new boolean[size];
    }

    private synchronized void record(boolean failure) {
      if (recorded == outcomes.length) {
        if (outcomes[cursor]) {
          failures--;
        }
      } else {
        recorded++;
      }

      outcomes[cursor] = failure;
      if (failure) {
        failures++;
      }
      cursor = (cursor + 1) % outcomes.length;
    }

    private synchronized boolean isFailureRateExceeded(
        int minimumNumberOfCalls, int failureRateThreshold) {
      if (recorded < minimumNumberOfCalls) {
        return false;
      }
      return failures * 100.0 / recorded >= failureRateThreshold;
    }

    private synchronized void reset() {
      cursor = 0;
      recorded = 0;
      failures = 0;
    }

    private synchronized int getRecordedCalls() {
      return recorded;
    }

    private synchronized double getFailureRate() {
      return recorded == 0 ? 0.0 : failures * 100.0 / recorded;
    }
  }
}
