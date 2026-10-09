package org.fluxgate.spring.handler;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.match.PathPatternMatcher;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.resilience.ResilientExecutor;
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
 *       request through unlimited, {@code DENY} rejects it with no wait time
 * </ul>
 *
 * <p>Degraded decisions are never silent: each one increments the {@code fluxgate.limiter.failures}
 * counter through the injected {@link FailureRecorder} (tagged with the action taken) and is
 * logged.
 */
public class ResilientRateLimiter implements RateLimiter {

  private static final Logger log = LoggerFactory.getLogger(ResilientRateLimiter.class);

  /** Operation name reported to the resilience layer and the failure metric. */
  private static final String OPERATION = "ratelimit.tryConsume";

  /** Prefix of the synthetic key reported when the limiter failed and the behavior is DENY. */
  private static final String FAILURE_KEY_PREFIX = "limiter-failure:";

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
    // Remembers the failure so the fallback can tag the metric with its cause; an open circuit
    // never runs the action, which is why the reference can still be empty in the fallback.
    AtomicReference<Throwable> failure = new AtomicReference<>();
    return executor.executeWithFallback(
        OPERATION,
        () -> {
          try {
            return consume(delegate, context, ruleSet, permits, pathMatcher);
          } catch (RuntimeException e) {
            failure.set(e);
            throw e;
          }
        },
        () -> degrade(context, ruleSet, permits, pathMatcher, failure.get()));
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
        failureRecorder.recordLimiterFailure(ruleSetId, endpoint, ACTION_FALLBACK, cause);
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
      failureRecorder.recordLimiterFailure(ruleSetId, endpoint, ACTION_FAIL_OPEN, cause);
      log.warn(
          "Rate limiter unavailable for rule set '{}', allowing the request (failure-behavior"
              + "=ALLOW)",
          ruleSetId,
          cause);
      return RateLimitResult.allowedWithoutRule();
    }

    failureRecorder.recordLimiterFailure(ruleSetId, endpoint, ACTION_FAIL_CLOSED, cause);
    log.warn(
        "Rate limiter unavailable for rule set '{}', rejecting the request (failure-behavior=DENY)",
        ruleSetId,
        cause);
    return RateLimitResult.builder(RateLimitKey.of(FAILURE_KEY_PREFIX + ruleSetId))
        .allowed(false)
        .remainingTokens(0L)
        .nanosToWaitForRefill(0L)
        .build();
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
