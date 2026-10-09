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
 * <p>Configuration errors are turned into rejections rather than being propagated: a rule whose
 * scope value is missing under {@code missing-key-behavior=REJECT} ({@link
 * MissingRateLimitKeyException}) and a rule that cannot be built ({@link
 * InvalidRuleConfigException}) both produce a rejected response with no wait time. Both are logged
 * at WARN once per rule set id and at DEBUG afterwards, so a misconfigured rule set is visible
 * without flooding the log on the hot path.
 */
public class EngineBackedRateLimitHandler implements FluxgateRateLimitHandler {

  private static final Logger log = LoggerFactory.getLogger(EngineBackedRateLimitHandler.class);

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
    try {
      return RateLimitResponse.from(engine.check(ruleSetId, context, permits));
    } catch (MissingRateLimitKeyException e) {
      logConfigurationProblem(
          ruleSetId, "no rate limit key could be resolved, rejecting the request", e);
      return RateLimitResponse.rejected(0L);
    } catch (InvalidRuleConfigException e) {
      logConfigurationProblem(ruleSetId, "the rule configuration is invalid", e);
      return RateLimitResponse.rejected(0L);
    }
  }

  /** Logs a configuration problem at WARN the first time it is seen, then at DEBUG. */
  private void logConfigurationProblem(String ruleSetId, String what, RuntimeException e) {
    if (warnedRuleSetIds.add(ruleSetId)) {
      log.warn("Rule set '{}': {}: {}", ruleSetId, what, e.getMessage());
    } else {
      log.debug("Rule set '{}': {}: {}", ruleSetId, what, e.getMessage());
    }
  }
}
