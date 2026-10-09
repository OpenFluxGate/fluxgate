package org.fluxgate.envoy;

import java.util.Objects;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Delegates Envoy checks to the existing FluxGate engine and its configured rule provider. */
@Service
public final class AuthzDecisionService {
  private static final Logger log = LoggerFactory.getLogger(AuthzDecisionService.class);

  private final RateLimitEngine engine;
  private final String ruleSetId;
  private final RateLimitRuleSetProvider provider;

  @Autowired
  public AuthzDecisionService(
      RateLimitEngine engine,
      RateLimitRuleSetProvider provider,
      @Value("${fluxgate.envoy.rule-set-id:gateway-pilot}") String ruleSetId) {
    this.engine = Objects.requireNonNull(engine, "engine");
    this.provider = provider;
    this.ruleSetId = Objects.requireNonNull(ruleSetId, "ruleSetId");
  }

  /** Compatibility constructor for direct embedding; production always injects the provider. */
  public AuthzDecisionService(RateLimitEngine engine, String ruleSetId) {
    this(engine, null, ruleSetId);
  }

  public boolean isReady(String selectedRuleSetId) {
    if (provider == null) {
      return false;
    }
    try {
      return provider
          .findById(selectedRuleSetId)
          .filter(rs -> !rs.getRules().isEmpty())
          .filter(
              rs ->
                  rs.getRules().stream()
                      .noneMatch(
                          rule ->
                              rule.getOnLimitExceedPolicy() == OnLimitExceedPolicy.WAIT_FOR_REFILL))
          .isPresent();
    } catch (RuntimeException e) {
      return false;
    }
  }

  public AuthzDecision decide(RequestContext context) {
    return decide(ruleSetId, context, 1L);
  }

  public AuthzDecision decide(String selectedRuleSetId, RequestContext context, long permits) {
    try {
      if (permits <= 0 || (provider != null && !isReady(selectedRuleSetId))) {
        return AuthzDecision.of(503);
      }
      RateLimitResult result = engine.check(selectedRuleSetId, context, permits);
      if (result.getPolicy() == OnLimitExceedPolicy.WAIT_FOR_REFILL) {
        return AuthzDecision.of(503);
      }
      if (result.isAllowed()) {
        return AuthzDecision.of(200);
      }
      if (result.getDecisionReason() == RateLimitResult.DecisionReason.ACCESS_DENIED
          || result.getDecisionReason() == RateLimitResult.DecisionReason.MISSING_KEY) {
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
