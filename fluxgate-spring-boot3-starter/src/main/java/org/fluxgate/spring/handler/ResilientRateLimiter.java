package org.fluxgate.spring.handler;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.exception.FluxgateConfigurationException;
import org.fluxgate.core.match.PathPatternMatcher;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.resilience.CircuitBreaker;
import org.fluxgate.core.resilience.IgnoredCallException;
import org.fluxgate.core.resilience.ResilientExecutor;
import org.fluxgate.core.util.LogThrottle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RateLimiter} decorator that runs the primary limiter through a {@link ResilientExecutor}
 * and degrades in a configured way when it fails.
 *
 * <p>Every call goes through {@link ResilientExecutor#executeWithFallback(String, Supplier,
 * Supplier)}, so retries and the circuit breaker configured under {@code fluxgate.resilience} are
 * finally on the rate limiting path. When the primary limiter keeps failing - or while the circuit
 * is open - the decorator degrades:
 *
 * <ul>
 *   <li>{@code fluxgate.ratelimit.fallback.mode=IN_MEMORY} - delegate to an in-memory limiter, so
 *       each instance keeps enforcing its own share of the limit instead of losing it entirely
 *   <li>otherwise {@code fluxgate.ratelimit.failure-behavior} decides: {@code ALLOW} lets the
 *       request through unlimited, {@code DENY} throws {@link RateLimiterUnavailableException},
 *       which the filter and the aspect answer with HTTP 503
 * </ul>
 *
 * <p>Degraded decisions are never silent: each one increments the {@code fluxgate.limiter.failures}
 * counter through the injected {@link FailureRecorder} (tagged with the action taken) and is
 * logged.
 *
 * <p>Client and configuration errors are not limiter failures and never degrade. A request whose
 * cost exceeds the capacity of a matching band is rejected with {@link
 * PermitsExceedCapacityException} before the circuit breaker is consulted, and any other {@link
 * FluxgateConfigurationException} (such as {@link
 * org.fluxgate.core.exception.InvalidRuleConfigException}) or {@link IllegalArgumentException}
 * raised by the primary limiter is rethrown unchanged: it is neither counted as a breaker failure
 * nor answered by the fallback. Otherwise a client sending oversized costs could open the breaker
 * for everyone - a global 503 under {@code failure-behavior=DENY}, no limits at all under {@code
 * ALLOW}. Such a call reaches the breaker as an {@link IgnoredCallException}: it is neither a
 * success nor a failure, so it cannot reset the consecutive failure count of a closed circuit nor
 * count as a successful trial of a half-open one.
 */
public class ResilientRateLimiter implements RateLimiter {

  private static final Logger log = LoggerFactory.getLogger(ResilientRateLimiter.class);

  /** Operation name reported to the resilience layer and the failure metric. */
  private static final String OPERATION = "ratelimit.tryConsume";

  private static final String ACTION_FALLBACK = "fallback_in_memory";
  private static final String ACTION_FAIL_OPEN = "fail_open";
  private static final String ACTION_FAIL_CLOSED = "fail_closed";

  /**
   * Sink for rate limiter failures.
   *
   * <p>Declared here so that this class does not depend on Micrometer: the starter adapts {@code
   * FluxgateMetrics#recordLimiterFailure} onto it when a meter registry is present.
   */
  @FunctionalInterface
  public interface FailureRecorder {

    /**
     * Records one limiter failure and the action that was taken instead.
     *
     * @param ruleSetId the rule set being evaluated
     * @param endpoint the request endpoint, may be null
     * @param action the action taken, such as {@code fail_open} or {@code fallback_in_memory}
     * @param cause the limiter failure cause, may be null when the circuit was simply open
     */
    void recordLimiterFailure(String ruleSetId, String endpoint, String action, Throwable cause);

    /** A recorder that discards everything. */
    FailureRecorder NONE = (ruleSetId, endpoint, action, cause) -> {};
  }

  private final RateLimiter delegate;
  private final ResilientExecutor executor;
  private final RateLimiter fallbackLimiter;
  private final boolean allowOnFailure;
  private final FailureRecorder failureRecorder;

  /** Throttles the warning about a failing {@link FailureRecorder}. */
  private final LogThrottle failureRecorderWarnings = new LogThrottle();

  /**
   * Creates a resilient decorator.
   *
   * @param delegate the primary limiter (must not be null)
   * @param executor the resilience executor wrapping every primary call (must not be null)
   * @param fallbackLimiter the in-memory fallback limiter, or null to apply {@code
   *     failure-behavior} directly
   * @param allowOnFailure whether a failure without a fallback limiter allows the request
   * @param failureRecorder sink for failure metrics, or null for none
   */
  public ResilientRateLimiter(
      RateLimiter delegate,
      ResilientExecutor executor,
      RateLimiter fallbackLimiter,
      boolean allowOnFailure,
      FailureRecorder failureRecorder) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    this.executor = Objects.requireNonNull(executor, "executor must not be null");
    this.fallbackLimiter = fallbackLimiter;
    this.allowOnFailure = allowOnFailure;
    this.failureRecorder = failureRecorder != null ? failureRecorder : FailureRecorder.NONE;

    log.info(
        "ResilientRateLimiter wrapping {}: fallbackLimiter={}, failureBehavior={}",
        delegate.getClass().getSimpleName(),
        fallbackLimiter != null ? fallbackLimiter.getClass().getSimpleName() : "none",
        allowOnFailure ? "ALLOW" : "DENY");
  }

  @Override
  public RateLimitResult tryConsume(
      RequestContext context, RateLimitRuleSet ruleSet, long permits) {
    return execute(context, ruleSet, permits, null);
  }

  /**
   * {@inheritDoc}
   *
   * <p>The path matcher is passed on to the primary limiter and to the fallback limiter, so rule
   * matching (for example {@code case-sensitive-patterns=false}) is the same on both paths.
   */
  @Override
  public RateLimitResult tryConsume(
      RequestContext context,
      RateLimitRuleSet ruleSet,
      long permits,
      PathPatternMatcher pathMatcher) {
    return execute(context, ruleSet, permits, pathMatcher);
  }

  /** Runs the primary limiter through the executor; a null matcher uses the 3-arg overload. */
  private RateLimitResult execute(
      RequestContext context,
      RateLimitRuleSet ruleSet,
      long permits,
      PathPatternMatcher pathMatcher) {
    // A cost that can never fit is the client's error: reject it before the breaker sees the call.
    checkPermitsFitCapacity(context, ruleSet, permits, pathMatcher);

    // Remembers the failure so the fallback can tag the metric with its cause; an open circuit
    // never runs the action, which is why the reference can still be empty in the fallback.
    AtomicReference<Throwable> failure = new AtomicReference<>();
    // A client or configuration error leaves the executor as an IgnoredCallException, which the
    // breaker records as neither a success nor a failure and never answers with the fallback.
    AtomicReference<RuntimeException> clientError = new AtomicReference<>();
    try {
      return executor.executeWithFallback(
          OPERATION,
          () -> {
            try {
              return consume(delegate, context, ruleSet, permits, pathMatcher);
            } catch (RuntimeException e) {
              if (isClientError(e)) {
                clientError.set(e);
                throw new IgnoredCallException(e);
              }
              failure.set(e);
              throw e;
            }
          },
          () -> {
            // a custom breaker that does not know IgnoredCallException falls back instead
            RuntimeException rejected = clientError.get();
            if (rejected != null) {
              throw rejected;
            }
            return degrade(context, ruleSet, permits, pathMatcher, failure.get());
          });
    } catch (IgnoredCallException e) {
      RuntimeException rejected = clientError.get();
      throw rejected != null ? rejected : e;
    }
  }

  /**
   * Whether a primary limiter exception describes the request or the rules rather than the
   * limiter's health. Retrying it, counting it against the breaker or answering it from the
   * fallback would all be wrong: the same call fails the same way every time.
   */
  private static boolean isClientError(RuntimeException e) {
    return e instanceof FluxgateConfigurationException || e instanceof IllegalArgumentException;
  }

  /**
   * Rejects a weighted request whose cost exceeds the capacity of a band of a matching rule, the
   * same check the limiters make, but before the circuit breaker. Only possible when the matcher is
   * known, which is always the case on the engine's path; the 3-arg overload relies on the
   * limiter's own check, which {@link #isClientError} keeps away from the breaker too.
   */
  private static void checkPermitsFitCapacity(
      RequestContext context,
      RateLimitRuleSet ruleSet,
      long permits,
      PathPatternMatcher pathMatcher) {
    if (permits <= 1 || context == null || ruleSet == null || pathMatcher == null) {
      return;
    }
    RateLimitRule offending = null;
    long smallestCapacity = Long.MAX_VALUE;
    for (RateLimitRule rule : ruleSet.getMatchingRules(context, pathMatcher)) {
      for (RateLimitBand band : rule.getBands()) {
        if (band.getCapacity() < smallestCapacity) {
          smallestCapacity = band.getCapacity();
          if (permits > smallestCapacity) {
            offending = rule;
          }
        }
      }
    }
    if (offending != null) {
      throw new PermitsExceedCapacityException(permits, smallestCapacity, offending.getId());
    }
  }

  private static RateLimitResult consume(
      RateLimiter limiter,
      RequestContext context,
      RateLimitRuleSet ruleSet,
      long permits,
      PathPatternMatcher pathMatcher) {
    return pathMatcher != null
        ? limiter.tryConsume(context, ruleSet, permits, pathMatcher)
        : limiter.tryConsume(context, ruleSet, permits);
  }

  /**
   * Reports a degraded decision to the failure recorder. An exception (not an {@link Error}) thrown
   * by the recorder is logged and not rethrown: it must not change the decision that was already
   * taken (or charged).
   */
  private void recordFailure(String ruleSetId, String endpoint, String action, Throwable cause) {
    try {
      failureRecorder.recordLimiterFailure(ruleSetId, endpoint, action, cause);
    } catch (Exception e) {
      if (failureRecorderWarnings.tryAcquire()) {
        log.warn(
            "Limiter failure recorder {} failed; the degraded decision stands. Further failures"
                + " within {} are logged at DEBUG.",
            failureRecorder.getClass().getName(),
            LogThrottle.DEFAULT_INTERVAL,
            e);
      } else {
        log.debug("Limiter failure recorder {} failed", failureRecorder.getClass().getName(), e);
      }
    }
  }

  /** Applies the configured degradation for one failed call. */
  private RateLimitResult degrade(
      RequestContext context,
      RateLimitRuleSet ruleSet,
      long permits,
      PathPatternMatcher pathMatcher,
      Throwable cause) {
    String ruleSetId = ruleSet != null ? ruleSet.getId() : null;
    String endpoint = context != null ? context.getEndpoint() : null;

    if (fallbackLimiter != null) {
      try {
        RateLimitResult result = consume(fallbackLimiter, context, ruleSet, permits, pathMatcher);
        recordFailure(ruleSetId, endpoint, ACTION_FALLBACK, cause);
        log.debug(
            "Primary rate limiter unavailable for rule set '{}', limited in memory instead",
            ruleSetId,
            cause);
        return result;
      } catch (RuntimeException e) {
        log.warn(
            "In-memory fallback limiter also failed for rule set '{}', applying failure-behavior",
            ruleSetId,
            e);
      }
    }

    if (allowOnFailure) {
      recordFailure(ruleSetId, endpoint, ACTION_FAIL_OPEN, cause);
      log.warn(
          "Rate limiter unavailable for rule set '{}', allowing the request (failure-behavior"
              + "=ALLOW)",
          ruleSetId,
          cause);
      return RateLimitResult.allowedWithoutRule();
    }

    recordFailure(ruleSetId, endpoint, ACTION_FAIL_CLOSED, cause);
    log.warn(
        "Rate limiter unavailable for rule set '{}', rejecting the request (failure-behavior=DENY)",
        ruleSetId,
        cause);
    // Item 10: not a "limit exceeded" result. The HTTP layer answers 503, with Retry-After when the
    // circuit breaker says how long it stays open.
    throw new RateLimiterUnavailableException(
        "Rate limiter unavailable for rule set '" + ruleSetId + "'",
        openCircuitWaitMillis(),
        cause);
  }

  /** The circuit breaker's open-state wait while it is open, otherwise unknown (-1). */
  private long openCircuitWaitMillis() {
    try {
      CircuitBreaker circuitBreaker = executor.getCircuitBreaker();
      if (circuitBreaker != null
          && circuitBreaker.getState() == CircuitBreaker.State.OPEN
          && circuitBreaker.getConfig() != null
          && circuitBreaker.getConfig().getWaitDurationInOpenState() != null) {
        return circuitBreaker.getConfig().getWaitDurationInOpenState().toMillis();
      }
    } catch (RuntimeException e) {
      log.debug("Could not read the circuit breaker state", e);
    }
    return RateLimiterUnavailableException.UNKNOWN_RETRY_AFTER;
  }

  /**
   * Returns the decorated primary limiter.
   *
   * @return the primary limiter
   */
  public RateLimiter getDelegate() {
    return delegate;
  }

  /**
   * Returns the in-memory fallback limiter, if one is configured.
   *
   * @return the fallback limiter, or null
   */
  public RateLimiter getFallbackLimiter() {
    return fallbackLimiter;
  }
}
