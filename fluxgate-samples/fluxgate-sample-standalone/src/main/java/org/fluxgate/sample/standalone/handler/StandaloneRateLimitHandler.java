package org.fluxgate.sample.standalone.handler;

import java.util.Optional;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.redis.RedisRateLimiter;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Rate limit handler for the standalone sample.
 *
 * <p>This handler: 1. Looks up rules from MongoDB via RateLimitRuleSetProvider 2. Applies rate
 * limiting via Redis using RedisRateLimiter 3. Returns rate limit response to the filter
 *
 * <p>The behavior when no rule is found can be configured via:
 *
 * <pre>
 * fluxgate:
 *   ratelimit:
 *     missing-rule-behavior: ALLOW  # or DENY
 * </pre>
 */
@Component
public class StandaloneRateLimitHandler implements FluxgateRateLimitHandler {

  private static final Logger log = LoggerFactory.getLogger(StandaloneRateLimitHandler.class);

  private final RateLimitRuleSetProvider ruleSetProvider;
  private final RedisRateLimiter rateLimiter;
  private final boolean denyWhenRuleMissing;

  public StandaloneRateLimitHandler(
      RateLimitRuleSetProvider ruleSetProvider,
      RedisRateLimiter rateLimiter,
      FluxgateProperties properties) {
    this.ruleSetProvider = ruleSetProvider;
    this.rateLimiter = rateLimiter;
    this.denyWhenRuleMissing = properties.getRatelimit().isDenyWhenRuleMissing();
    log.info(
        "StandaloneRateLimitHandler initialized (denyWhenRuleMissing={})", denyWhenRuleMissing);
  }

  @Override
  public RateLimitResponse tryConsume(RequestContext context, String ruleSetId) {
    log.debug(
        "Processing rate limit for ruleSetId: {}, clientIp: {}", ruleSetId, context.getClientIp());

    // Look up the ruleset from MongoDB
    Optional<RateLimitRuleSet> ruleSetOpt = ruleSetProvider.findById(ruleSetId);
    if (ruleSetOpt.isEmpty()) {
      if (denyWhenRuleMissing) {
        log.warn("RuleSet not found: {}, denying request (missing-rule-behavior=DENY)", ruleSetId);
        return RateLimitResponse.rejected(0);
      } else {
        log.warn(
            "RuleSet not found: {}, allowing request (missing-rule-behavior=ALLOW)", ruleSetId);
        return RateLimitResponse.allowed(-1, 0);
      }
    }

    RateLimitRuleSet ruleSet = ruleSetOpt.get();

    // Apply rate limiting via Redis
    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    if (result.isAllowed()) {
      log.debug(
          "Request allowed for ruleSet: {}, remaining: {}", ruleSetId, result.getRemainingTokens());
      return RateLimitResponse.allowed(
          result.getRemainingTokens(), 0 // reset time not directly available from RateLimitResult
          );
    } else {
      long retryAfterMillis = result.getNanosToWaitForRefill() / 1_000_000;
      log.info("Request rejected for ruleSet: {}, retry after: {} ms", ruleSetId, retryAfterMillis);
      return RateLimitResponse.rejected(retryAfterMillis);
    }
  }
}
