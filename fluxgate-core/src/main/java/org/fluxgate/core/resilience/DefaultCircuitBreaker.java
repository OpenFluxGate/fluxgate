package org.fluxgate.core.resilience;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link CircuitBreaker}.
 *
 * <p>This implementation tracks consecutive failures and transitions between states based on the
 * configured thresholds.
 *
 */
public class DefaultCircuitBreaker implements CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(DefaultCircuitBreaker.class);

    private final String name;
    private final CircuitBreakerConfig config;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenSuccessCount = new AtomicInteger(0);
    private volatile Instant openedAt;

    /**
     * Creates a new DefaultCircuitBreaker with the given name and configuration.
     *
     * @param name the name of the circuit breaker
     * @param config the circuit breaker configuration
     */
    public DefaultCircuitBreaker(String name, CircuitBreakerConfig config) {
        this.name = name;
        this.config = config;
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

        State currentState = checkAndUpdateState();

        if (currentState == State.OPEN) {
            return handleOpenState(operationName);
        }

        try {
            T result = action.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure(e);
            throw e;
        }
    }

    @Override
    public <T> T executeWithFallback(Supplier<T> action, Supplier<T> fallback) {
        if (!config.isEnabled()) {
            return action.get();
        }

        State currentState = checkAndUpdateState();

        if (currentState == State.OPEN) {
            log.debug("Circuit breaker '{}' is open, using fallback", name);
            return fallback.get();
        }

        try {
            T result = action.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure(e);
            return fallback.get();
        }
    }

    private State checkAndUpdateState() {
        State currentState = state.get();

        if (currentState == State.OPEN && shouldTransitionToHalfOpen()) {
            if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                log.info("Circuit breaker '{}' transitioning from OPEN to HALF_OPEN", name);
                halfOpenSuccessCount.set(0);
            }
            return state.get();
        }

        return currentState;
    }

    private boolean shouldTransitionToHalfOpen() {
        if (openedAt == null) {
            return false;
        }
        return Instant.now().isAfter(openedAt.plus(config.getWaitDurationInOpenState()));
    }

    private <T> T handleOpenState(String operationName) throws CircuitBreakerOpenException {
        if (config.getFallbackStrategy() == CircuitBreakerConfig.FallbackStrategy.FAIL_OPEN) {
            log.debug(
                    "Circuit breaker '{}' is open but using FAIL_OPEN strategy for '{}'",
                    name,
                    operationName);
            // Return null to indicate fallback - caller should handle this
            return null;
        }

        throw new CircuitBreakerOpenException(name);
    }

    private void onSuccess() {
        State currentState = state.get();

        if (currentState == State.HALF_OPEN) {
            int successes = halfOpenSuccessCount.incrementAndGet();
            if (successes >= config.getPermittedCallsInHalfOpenState()) {
                if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    log.info("Circuit breaker '{}' transitioning from HALF_OPEN to CLOSED", name);
                    failureCount.set(0);
                    openedAt = null;
                }
            }
        } else if (currentState == State.CLOSED) {
            failureCount.set(0);
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
                openedAt = Instant.now();
            }
        } else if (currentState == State.CLOSED) {
            int failures = failureCount.incrementAndGet();
            if (failures >= config.getFailureThreshold()) {
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    log.warn(
                            "Circuit breaker '{}' transitioning from CLOSED to OPEN after {} failures",
                            name,
                            failures);
                    openedAt = Instant.now();
                }
            }
        }
    }

    @Override
    public State getState() {
        return checkAndUpdateState();
    }

    @Override
    public CircuitBreakerConfig getConfig() {
        return config;
    }

    @Override
    public void reset() {
        state.set(State.CLOSED);
        failureCount.set(0);
        halfOpenSuccessCount.set(0);
        openedAt = null;
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
     * Returns the current failure count.
     *
     * @return the failure count
     */
    public int getFailureCount() {
        return failureCount.get();
    }
}
