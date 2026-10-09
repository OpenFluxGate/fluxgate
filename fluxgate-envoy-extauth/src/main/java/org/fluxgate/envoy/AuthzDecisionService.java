package org.fluxgate.envoy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.match.PathPatternMatcher;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Applies exactly one server-selected FluxGate rule before Envoy forwards a request. */
@Service
public final class AuthzDecisionService {
  private static final Logger log = LoggerFactory.getLogger(AuthzDecisionService.class);

  private final RateLimitEngine engine;
  private final RateLimitRuleSetProvider provider;
  private final PathPatternMatcher matcher;
  private final String ruleSetId;

  public AuthzDecisionService(
      RateLimitEngine engine,
      RateLimitRuleSetProvider provider,
      PathPatternMatcher matcher,
      @Value("${fluxgate.envoy.rule-set-id:gateway-pilot}") String ruleSetId) {
    this.engine = Objects.requireNonNull(engine, "engine");
    this.provider = Objects.requireNonNull(provider, "provider");
    this.matcher = Objects.requireNonNull(matcher, "matcher");
    this.ruleSetId = Objects.requireNonNull(ruleSetId, "ruleSetId");
  }

  public AuthzDecision decide(RequestContext context) {
    try {
      Optional<RateLimitRuleSet> configured = provider.findById(ruleSetId);
      if (configured.isEmpty()) {
        log.error("Envoy authorization rule set is missing: {}", ruleSetId);
        return AuthzDecision.of(503);
      }
      List<RateLimitRule> matched = configured.get().getMatchingRules(context, matcher);
      if (matched.size() != 1 || matched.get(0).getBands().isEmpty()) {
        log.error(
            "Envoy authorization requires exactly one matching rule with a band: {}", ruleSetId);
        return AuthzDecision.of(503);
      }

      RateLimitResult result = engine.check(ruleSetId, context, 1L);
      if (result.isAllowed()) {
        return result.hasRule()
                || result.getDecisionReason() == RateLimitResult.DecisionReason.ACCESS_BYPASS
            ? AuthzDecision.of(200)
            : AuthzDecision.of(503);
      }
      if (result.getDecisionReason() == RateLimitResult.DecisionReason.ACCESS_DENIED) {
        return AuthzDecision.of(403);
      }
      if (result.getDecisionReason() == RateLimitResult.DecisionReason.QUOTA) {
        long nanos = result.getNanosToWaitForRefill();
        long seconds = nanos <= 0 ? 1 : 1 + (nanos - 1) / 1_000_000_000L;
        return new AuthzDecision(429, seconds);
      }
      return AuthzDecision.of(503);
    } catch (RuntimeException e) {
      log.warn("Envoy authorization backend failed", e);
      return AuthzDecision.of(503);
    }
  }
}
