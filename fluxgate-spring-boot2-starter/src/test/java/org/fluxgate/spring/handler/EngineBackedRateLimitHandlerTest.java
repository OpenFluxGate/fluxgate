package org.fluxgate.spring.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.engine.RateLimitEngine.OnMissingRuleSetStrategy;
import org.fluxgate.core.exception.InvalidRuleConfigException;
import org.fluxgate.core.exception.MissingRateLimitKeyException;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.junit.jupiter.api.Test;

/** Tests for {@link EngineBackedRateLimitHandler}. */
class EngineBackedRateLimitHandlerTest {

  private static final RequestContext CONTEXT =
      RequestContext.builder().clientIp("10.0.0.1").endpoint("/api/orders").build();

  private static RateLimitRule rule() {
    return RateLimitRule.builder("orders-rule")
        .name("orders")
        .enabled(true)
        .ruleSetId("orders")
        .scope(LimitScope.PER_IP)
        .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).label("per-minute").build())
        .build();
  }

  private static RateLimitRuleSet ruleSet() {
    return RateLimitRuleSet.builder("orders")
        .rules(List.of(rule()))
        .keyResolver((ctx, r) -> RateLimitKey.of("ip:" + ctx.getClientIp()))
        .build();
  }

  private static EngineBackedRateLimitHandler handlerOver(RateLimiter limiter) {
    return handlerOver(limiter, OnMissingRuleSetStrategy.DENY);
  }

  private static EngineBackedRateLimitHandler handlerOver(
      RateLimiter limiter, OnMissingRuleSetStrategy strategy) {
    RateLimitEngine engine =
        RateLimitEngine.builder()
            .ruleSetProvider(id -> "orders".equals(id) ? Optional.of(ruleSet()) : Optional.empty())
            .rateLimiter(limiter)
            .onMissingRuleSetStrategy(strategy)
            .build();
    return new EngineBackedRateLimitHandler(engine);
  }

  @Test
  void shouldConvertAnAllowedResultIntoAnAllowedResponse() {
    RateLimitResult result =
        RateLimitResult.allowed(
            RateLimitKey.of("ip:10.0.0.1"), rule(), 42L, 1_500_000L, 100L, 1_700_000_000_000L);
    RateLimitResponse response =
        handlerOver((ctx, rules, permits) -> result).tryConsume(CONTEXT, "orders");

    assertThat(response.isAllowed()).isTrue();
    assertThat(response.getRemainingTokens()).isEqualTo(42L);
    assertThat(response.getRetryAfterMillis()).isEqualTo(2L); // rounded up from 1.5ms
    assertThat(response.getLimit()).isEqualTo(100L);
    assertThat(response.getResetTimeMillis()).isEqualTo(1_700_000_000_000L);
  }

  @Test
  void shouldConvertARejectedResultIntoARejectedResponseCarryingTheRealRemaining() {
    RateLimitResult result =
        RateLimitResult.rejected(
            RateLimitKey.of("ip:10.0.0.1"), rule(), 3L, 250_000_000L, 100L, 1_700_000_000_000L);
    RateLimitResponse response =
        handlerOver((ctx, rules, permits) -> result).tryConsume(CONTEXT, "orders");

    assertThat(response.isAllowed()).isFalse();
    assertThat(response.getRemainingTokens()).isEqualTo(3L);
    assertThat(response.getRetryAfterMillis()).isEqualTo(250L);
    assertThat(response.getOnLimitExceedPolicy()).isEqualTo(OnLimitExceedPolicy.REJECT_REQUEST);
  }

  @Test
  void shouldSupportWeightedPermits() {
    long[] seen = new long[1];
    RateLimitResponse response =
        handlerOver(
                (ctx, rules, permits) -> {
                  seen[0] = permits;
                  return RateLimitResult.allowedWithoutRule();
                })
            .tryConsume(CONTEXT, "orders", 5L);

    assertThat(seen[0]).isEqualTo(5L);
    assertThat(response.isAllowed()).isTrue();
  }

  @Test
  void shouldRejectWhenNoRuleSetExistsAndTheStrategyIsDeny() {
    EngineBackedRateLimitHandler handler =
        handlerOver((ctx, rules, permits) -> RateLimitResult.allowedWithoutRule());

    // Item 10: an unknown rule set under DENY is unconfigured rate limiting (HTTP 503).
    assertThatThrownBy(() -> handler.tryConsume(CONTEXT, "unknown"))
        .isInstanceOf(RateLimiterUnavailableException.class);
  }

  @Test
  void shouldRecogniseAMissingRuleSetWhoseIdHasToBeRewritten() {
    EngineBackedRateLimitHandler handler =
        handlerOver((ctx, rules, permits) -> RateLimitResult.allowedWithoutRule());

    // the synthetic key keeps its missing-rule-set: prefix even for an id with a space
    assertThatThrownBy(() -> handler.tryConsume(CONTEXT, "orders v2"))
        .isInstanceOf(RateLimiterUnavailableException.class);
  }

  @Test
  void shouldAllowWhenNoRuleSetExistsAndTheStrategyIsAllow() {
    RateLimitResponse response =
        handlerOver(
                (ctx, rules, permits) -> RateLimitResult.allowedWithoutRule(),
                OnMissingRuleSetStrategy.ALLOW)
            .tryConsume(CONTEXT, "unknown");

    assertThat(response.isAllowed()).isTrue();
    assertThat(response.getRemainingTokens()).isEqualTo(-1L);
  }

  @Test
  void shouldRejectWhenNoRateLimitKeyCanBeResolved() {
    RateLimitResponse response =
        handlerOver(
                (ctx, rules, permits) -> {
                  throw new MissingRateLimitKeyException("orders-rule", LimitScope.PER_USER);
                })
            .tryConsume(CONTEXT, "orders");

    assertThat(response.isAllowed()).isFalse();
    assertThat(response.getRetryAfterMillis()).isZero();
  }

  @Test
  void shouldRejectWhenTheRuleConfigurationIsInvalid() {
    EngineBackedRateLimitHandler handler =
        handlerOver(
            (ctx, rules, permits) -> {
              throw new InvalidRuleConfigException("permits exceed capacity", "orders-rule");
            });

    // Called twice: the first failure warns, the rest go to DEBUG. Item 10: a broken rule set is
    // reported as unavailable (HTTP 503), not as an exceeded limit.
    assertThatThrownBy(() -> handler.tryConsume(CONTEXT, "orders"))
        .isInstanceOf(RateLimiterUnavailableException.class)
        .hasCauseInstanceOf(InvalidRuleConfigException.class);
    assertThatThrownBy(() -> handler.tryConsume(CONTEXT, "orders"))
        .isInstanceOf(RateLimiterUnavailableException.class);
  }

  @Test
  void shouldReportACostAboveTheBandCapacityAsTheCallersError() {
    // The in-memory limiter's own check, as reached without the resilient wrapper.
    EngineBackedRateLimitHandler handler =
        handlerOver(
            new org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter(
                100L, Duration.ofMinutes(1)));

    assertThatThrownBy(() -> handler.tryConsume(CONTEXT, "orders", 500L))
        .isInstanceOfSatisfying(
            PermitsExceedCapacityException.class,
            e -> {
              assertThat(e.getPermits()).isEqualTo(500L);
              assertThat(e.getCapacity()).isEqualTo(100L);
              assertThat(e.getRuleId()).isEqualTo("orders-rule");
            })
        .hasCauseInstanceOf(InvalidRuleConfigException.class);
  }

  @Test
  void shouldRethrowACostErrorFromTheResilientWrapperUnchanged() {
    PermitsExceedCapacityException tooCostly =
        new PermitsExceedCapacityException(500L, 100L, "orders-rule");
    EngineBackedRateLimitHandler handler =
        handlerOver(
            (ctx, rules, permits) -> {
              throw tooCostly;
            });

    assertThatThrownBy(() -> handler.tryConsume(CONTEXT, "orders", 500L)).isSameAs(tooCostly);
  }

  @Test
  void shouldKeepOtherInvalidRuleErrorsOfAWeightedRequestAsUnavailable() {
    EngineBackedRateLimitHandler handler =
        handlerOver(
            (ctx, rules, permits) -> {
              throw new InvalidRuleConfigException("window must be positive", "orders-rule");
            });

    assertThatThrownBy(() -> handler.tryConsume(CONTEXT, "orders", 5L))
        .isInstanceOf(RateLimiterUnavailableException.class);
  }
}
