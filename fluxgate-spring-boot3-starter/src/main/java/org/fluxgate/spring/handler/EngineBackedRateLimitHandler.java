package org.fluxgate.spring.handler;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.exception.InvalidRuleConfigException;
import org.fluxgate.core.exception.MissingRateLimitKeyException;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The library-provided {@link FluxgateRateLimitHandler}, backed by a {@link RateLimitEngine}.
 *
 * <p>This is the official adapter between the starter entry point ({@code
 * FluxgateRateLimitHandler}) and the core SPI ({@code RateLimitRuleSetProvider} + {@code
 * RateLimiter}). The starter registers it automatically whenever a {@code RateLimiter} and a {@code
 * RateLimitRuleSetProvider} bean exist, so {@code @EnableFluxgateFilter} with Redis and MongoDB
 * needs no user code at all. Define your own {@code FluxgateRateLimitHandler} bean to replace it.
 *
 * <p>A rule whose scope value is missing under {@code missing-key-behavior=REJECT} ({@link
 * MissingRateLimitKeyException}) produces a rejected response with no wait time (HTTP 429). A rule
 * set that cannot be built ({@link InvalidRuleConfigException}) or does not exist under {@code
 * missing-rule-behavior=DENY} is a configuration problem, not an exceeded limit: it throws {@link
 * RateLimiterUnavailableException}, which the filter and the aspect answer with HTTP 503 - under
 * {@code failure-behavior=ALLOW} too, because that setting covers limiter failures, not
 * configuration errors. All of them are logged at WARN once per rule set id and at DEBUG
 * afterwards, so a misconfigured rule set is visible without flooding the log on the hot path.
 *
 * <p>The exception is a weighted request whose cost exceeds the capacity of a matching band: that
 * is the caller's error, so it is rethrown as {@link PermitsExceedCapacityException} (logged at
 * DEBUG only), which the filter answers with HTTP 429.
 */
public class EngineBackedRateLimitHandler implements FluxgateRateLimitHandler {

  private static final Logger log = LoggerFactory.getLogger(EngineBackedRateLimitHandler.class);

  /** Key prefix {@code RateLimitEngine} uses for an unknown rule set under DENY. */
  private static final String MISSING_RULE_SET_PREFIX = "missing-rule-set:";

  private final RateLimitEngine engine;
  private final Set<String> warnedRuleSetIds = ConcurrentHashMap.newKeySet();

  /**
   * Creates a handler delegating to the given engine.
   *
   * @param engine the engine that resolves rule sets and consumes permits
   */
  public EngineBackedRateLimitHandler(RateLimitEngine engine) {
    this.engine = Objects.requireNonNull(engine, "engine must not be null");
    log.info("EngineBackedRateLimitHandler initialized");
  }

  @Override
  public RateLimitResponse tryConsume(RequestContext context, String ruleSetId) {
    return tryConsume(context, ruleSetId, 1L);
  }

  @Override
  public RateLimitResponse tryConsume(RequestContext context, String ruleSetId, long permits) {
    RateLimitResult result;
    try {
      result = engine.check(ruleSetId, context, permits);
    } catch (MissingRateLimitKeyException e) {
      logConfigurationProblem(
          ruleSetId, "no rate limit key could be resolved, rejecting the request", e);
      return RateLimitResponse.rejected(0L);
    } catch (InvalidRuleConfigException e) {
      // A request cost no band can hold is the client's error (HTTP 429), not a broken rule set.
      PermitsExceedCapacityException tooCostly = PermitsExceedCapacityException.from(e, permits);
      if (tooCostly != null) {
        log.debug("Rule set '{}': {}", ruleSetId, tooCostly.getMessage());
        throw tooCostly;
      }
      logConfigurationProblem(ruleSetId, "the rule configuration is invalid", e);
      throw new RateLimiterUnavailableException(
          "Rule set '" + ruleSetId + "' has an invalid configuration",
          RateLimiterUnavailableException.UNKNOWN_RETRY_AFTER,
          e);
    }
    if (!result.isAllowed() && isMissingRuleSet(result)) {
      logConfigurationProblem(
          ruleSetId, "no such rule set (missing-rule-behavior=DENY), rejecting the request", null);
      throw new RateLimiterUnavailableException("Rule set '" + ruleSetId + "' is not configured");
    }
    return RateLimitResponse.from(result);
  }

  /** The engine reports an unknown rule set under DENY with this synthetic key. */
  private static boolean isMissingRuleSet(RateLimitResult result) {
    RateLimitKey key = result.getKey();
    return key != null && key.value() != null && key.value().startsWith(MISSING_RULE_SET_PREFIX);
  }

  /** Logs a configuration problem at WARN the first time it is seen, then at DEBUG. */
  private void logConfigurationProblem(String ruleSetId, String what, RuntimeException e) {
    String detail = e != null ? e.getMessage() : "";
    if (warnedRuleSetIds.add(ruleSetId)) {
      log.warn("Rule set '{}': {}: {}", ruleSetId, what, detail);
    } else {
      log.debug("Rule set '{}': {}: {}", ruleSetId, what, detail);
    }
  }
}
