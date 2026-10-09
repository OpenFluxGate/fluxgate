package org.fluxgate.envoy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.fluxgate.core.config.AccessControl;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.config.RuleMatcher;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.junit.jupiter.api.Test;

class AuthzDecisionServiceTest {
  private final RequestContext request =
      RequestContext.builder().clientIp("127.0.0.1").endpoint("/api/items").method("GET").build();

  private RateLimitRule rule(String id) {
    return RateLimitRule.builder(id)
        .name(id)
        .ruleSetId("pilot")
        .addBand(RateLimitBand.builder(Duration.ofSeconds(60), 1).build())
        .build();
  }

  private AuthzDecisionService service(
      List<RateLimitRule> rules, AccessControl acl, RateLimiter limiter) {
    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("pilot")
            .rules(rules)
            .keyResolver(new LimitScopeKeyResolver())
            .accessControl(acl)
            .build();
    RateLimitRuleSetProvider provider =
        id -> "pilot".equals(id) ? java.util.Optional.of(ruleSet) : java.util.Optional.empty();
    RateLimitEngine engine =
        RateLimitEngine.builder()
            .ruleSetProvider(provider)
            .rateLimiter(limiter)
            .onMissingRuleSetStrategy(RateLimitEngine.OnMissingRuleSetStrategy.DENY)
            .build();
    return new AuthzDecisionService(engine, provider, "pilot");
  }

  @Test
  void allowedRuleReturns200() {
    RateLimitRule rule = rule("one");
    AuthzDecisionService service =
        service(
            List.of(rule),
            AccessControl.EMPTY,
            (ctx, rs, permits) -> RateLimitResult.allowed(RateLimitKey.of("global"), rule, 0, 0));
    assertThat(service.decide(request).status()).isEqualTo(200);
  }

  @Test
  void quotaReturns429AndCeiledRetryAfter() {
    RateLimitRule rule = rule("one");
    AuthzDecisionService service =
        service(
            List.of(rule),
            AccessControl.EMPTY,
            (ctx, rs, permits) ->
                RateLimitResult.rejected(RateLimitKey.of("global"), rule, 1_100_000_000L));
    AuthzDecision decision = service.decide(request);
    assertThat(decision.status()).isEqualTo(429);
    assertThat(decision.retryAfterSeconds()).isEqualTo(2L);
  }

  @Test
  void aclDenyReturns403WithoutLimiterCall() {
    AccessControl acl = AccessControl.builder().addDeniedKey("ip:127.0.0.1").build();
    AuthzDecisionService service =
        service(
            List.of(rule("one")),
            acl,
            (ctx, rs, permits) -> {
              throw new AssertionError("limiter called");
            });
    assertThat(service.decide(request).status()).isEqualTo(403);
  }

  @Test
  void noMatchingRuleKeepsTheExistingFluxGateAllowDecision() {
    RateLimitRule nonmatching =
        RateLimitRule.builder("other")
            .name("other")
            .ruleSetId("pilot")
            .matcher(RuleMatcher.builder().addPathPattern("/other/**").build())
            .addBand(RateLimitBand.builder(Duration.ofSeconds(60), 1).build())
            .build();
    assertThat(
            service(
                    List.of(nonmatching),
                    AccessControl.EMPTY,
                    (ctx, rs, permits) -> RateLimitResult.allowedWithoutRule())
                .decide(request)
                .status())
        .isEqualTo(200);
  }

  @Test
  void multipleMatchingRulesReachTheExistingLimiter() {
    RateLimitRule first = rule("one");
    assertThat(
            service(
                    List.of(first, rule("two")),
                    AccessControl.EMPTY,
                    (ctx, rs, permits) ->
                        RateLimitResult.allowed(RateLimitKey.of("global"), first, 0, 0))
                .decide(request)
                .status())
        .isEqualTo(200);
  }

  @Test
  void limiterFailureReturns503() {
    AuthzDecisionService service =
        service(
            List.of(rule("one")),
            AccessControl.EMPTY,
            (ctx, rs, permits) -> {
              throw new IllegalStateException("redis down");
            });
    assertThat(service.decide(request).status()).isEqualTo(503);
  }

  @Test
  void backendFailureResultReturns503() {
    AuthzDecisionService service =
        service(
            List.of(rule("one")),
            AccessControl.EMPTY,
            (ctx, rs, permits) ->
                RateLimitResult.builder(RateLimitKey.of("failure"))
                    .allowed(false)
                    .decisionReason(RateLimitResult.DecisionReason.BACKEND_FAILURE)
                    .build());
    assertThat(service.decide(request).status()).isEqualTo(503);
  }

  @Test
  void missingIdentityIsRejectedWithoutAQuotaRetryHeader() {
    RateLimitRule rule = rule("one");
    AuthzDecision decision =
        service(
                List.of(rule),
                AccessControl.EMPTY,
                (ctx, rs, permits) ->
                    RateLimitResult.builder(RateLimitKey.of("missing-key:one"))
                        .allowed(false)
                        .matchedRule(rule)
                        .decisionReason(RateLimitResult.DecisionReason.MISSING_KEY)
                        .build())
            .decide(request);
    assertThat(decision.status()).isEqualTo(403);
    assertThat(decision.retryAfterSeconds()).isZero();
  }

  @Test
  void noMatchingRuleFollowsTheExistingFluxGateAllowDecision() {
    AuthzDecisionService service =
        service(
            List.of(rule("one")),
            AccessControl.EMPTY,
            (ctx, rs, permits) -> RateLimitResult.allowedWithoutRule());
    assertThat(service.decide(request).status()).isEqualTo(200);
  }

  @Test
  void allowedAclBypassReturns200WithoutLimiterCall() {
    AccessControl acl = AccessControl.builder().addAllowedKey("ip:127.0.0.1").build();
    AuthzDecisionService service =
        service(
            List.of(rule("one")),
            acl,
            (ctx, rs, permits) -> {
              throw new AssertionError("limiter called");
            });
    assertThat(service.decide(request).status()).isEqualTo(200);
  }

  @Test
  void missingRuleSetReturns503() {
    RateLimitRuleSetProvider provider = id -> java.util.Optional.empty();
    RateLimitEngine engine =
        RateLimitEngine.builder()
            .ruleSetProvider(provider)
            .rateLimiter((ctx, rs, permits) -> RateLimitResult.allowedWithoutRule())
            .onMissingRuleSetStrategy(RateLimitEngine.OnMissingRuleSetStrategy.DENY)
            .build();
    assertThat(new AuthzDecisionService(engine, provider, "pilot").decide(request).status())
        .isEqualTo(503);
  }

  @Test
  void waitPolicyIsRejectedBeforeAnyConsumptionAndIsNotReady() {
    RateLimitRule wait =
        RateLimitRule.builder("wait")
            .name("wait")
            .ruleSetId("pilot")
            .onLimitExceedPolicy(org.fluxgate.core.config.OnLimitExceedPolicy.WAIT_FOR_REFILL)
            .addBand(RateLimitBand.builder(Duration.ofSeconds(60), 1).build())
            .build();
    var svc =
        service(
            List.of(wait),
            AccessControl.EMPTY,
            (ctx, rs, permits) -> {
              throw new AssertionError("WAIT must be rejected before consumption");
            });
    assertThat(svc.isReady("pilot")).isFalse();
    assertThat(svc.decide(request).status()).isEqualTo(503);
  }
}
