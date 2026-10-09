package org.fluxgate.envoy;

import java.util.Objects;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Delegates Envoy checks to the existing FluxGate engine and its configured rule provider. */
@Service
public final class AuthzDecisionService {
  private static final Logger log = LoggerFactory.getLogger(AuthzDecisionService.class);

  private final RateLimitEngine engine;
  private final String ruleSetId;

  public AuthzDecisionService(
      RateLimitEngine engine,
      @Value("${fluxgate.envoy.rule-set-id:gateway-pilot}") String ruleSetId) {
    this.engine = Objects.requireNonNull(engine, "engine");
    this.ruleSetId = Objects.requireNonNull(ruleSetId, "ruleSetId");
  }

  public AuthzDecision decide(RequestContext context) {
    try {
      RateLimitResult result = engine.check(ruleSetId, context, 1L);
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
