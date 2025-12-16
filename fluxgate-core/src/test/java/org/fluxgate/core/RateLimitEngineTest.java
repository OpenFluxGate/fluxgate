package org.fluxgate.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.engine.RateLimitEngine.OnMissingRuleSetStrategy;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.junit.jupiter.api.Test;

class RateLimitEngineTest {

  @Test
  void should_delegate_to_rate_limiter_and_return_result() {
    // given
    String ruleSetId = "auth-api-default";

    StubRuleSetProvider ruleSetProvider = new StubRuleSetProvider(ruleSetId);
    StubRateLimiter rateLimiter = new StubRateLimiter();

    RateLimitEngine engine =
        RateLimitEngine.builder()
            .ruleSetProvider(ruleSetProvider)
            .rateLimiter(rateLimiter)
            .onMissingRuleSetStrategy(OnMissingRuleSetStrategy.THROW)
            .build();

    RequestContext context =
        RequestContext.builder()
            .endpoint("/api/auth/login")
            .method("POST")
            .clientIp("127.0.0.1")
            .build();

    // when
    RateLimitResult result = engine.check(ruleSetId, context, 3L);

    // then
    assertThat(rateLimiter.wasCalled.get()).isTrue();
    assertThat(rateLimiter.lastPermits).isEqualTo(3L);
    assertThat(rateLimiter.lastRuleSetId).isEqualTo(ruleSetId);
    assertThat(result).isSameAs(rateLimiter.resultToReturn);
    assertThat(result.isAllowed()).isTrue();
  }

  @Test
  void should_throw_when_rule_set_not_found_and_strategy_throw() {
    // given
    RateLimitRuleSetProvider emptyProvider = id -> Optional.empty();

    RateLimiter dummyLimiter =
        (context, ruleSet, permits) -> {
          throw new IllegalStateException("Should not be called when rule set is missing");
        };

    RateLimitEngine engine =
        RateLimitEngine.builder()
            .ruleSetProvider(emptyProvider)
            .rateLimiter(dummyLimiter)
            .onMissingRuleSetStrategy(OnMissingRuleSetStrategy.THROW)
            .build();

    RequestContext context =
        RequestContext.builder().endpoint("/api/test").method("GET").clientIp("127.0.0.1").build();

    // expect
    assertThatThrownBy(() -> engine.check("unknown-rule-set", context))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown-rule-set");
  }

  @Test
  void should_allow_when_rule_set_not_found_and_strategy_allow() {
    // given
    RateLimitRuleSetProvider emptyProvider = id -> Optional.empty();

    AtomicBoolean limiterCalled = new AtomicBoolean(false);
    RateLimiter dummyLimiter =
        (context, ruleSet, permits) -> {
          limiterCalled.set(true);
          throw new IllegalStateException("Should not be called in ALLOW mode");
        };

    RateLimitEngine engine =
        RateLimitEngine.builder()
            .ruleSetProvider(emptyProvider)
            .rateLimiter(dummyLimiter)
            .onMissingRuleSetStrategy(OnMissingRuleSetStrategy.ALLOW)
            .build();

    RequestContext context =
        RequestContext.builder().endpoint("/api/test").method("GET").clientIp("127.0.0.1").build();

    // when
    RateLimitResult result = engine.check("unknown-rule-set", context);

    // then
    assertThat(limiterCalled.get()).isFalse();
    assertThat(result.isAllowed()).isTrue();
    assertThat(result.getMatchedRule()).isNull();
  }

  // ---- Test stubs ----

  private static class StubRuleSetProvider implements RateLimitRuleSetProvider {

    private final String expectedId;
    private final RateLimitRuleSet ruleSet;

    private StubRuleSetProvider(String expectedId) {
      this.expectedId = expectedId;

      KeyResolver keyResolver = (context, rule) -> RateLimitKey.of("stub");

      RateLimitBand dummyBand =
          RateLimitBand.builder(java.time.Duration.ofMinutes(1), 100).label("dummy-band").build();

      RateLimitRule dummyRule =
          RateLimitRule.builder("dummy-rule")
              .scope(LimitScope.PER_API_KEY)
              .addBand(dummyBand)
              .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
              .build();

      this.ruleSet =
          RateLimitRuleSet.builder(expectedId)
              .keyResolver(keyResolver)
              .rules(java.util.List.of(dummyRule))
              .build();
    }

    @Override
    public Optional<RateLimitRuleSet> findById(String ruleSetId) {
      if (expectedId.equals(ruleSetId)) {
        return Optional.of(ruleSet);
      }
      return Optional.empty();
    }
  }

  private static class StubRateLimiter implements RateLimiter {

    private final RateLimitResult resultToReturn;
    private final AtomicBoolean wasCalled = new AtomicBoolean(false);
    private volatile String lastRuleSetId;
    private volatile long lastPermits;

    private StubRateLimiter() {
      this.resultToReturn = RateLimitResult.allowedWithoutRule();
    }

    @Override
    public RateLimitResult tryConsume(
        RequestContext context, RateLimitRuleSet ruleSet, long permits) {
      wasCalled.set(true);
      this.lastRuleSetId = ruleSet.getId();
      this.lastPermits = permits;
      return resultToReturn;
    }
  }
}
