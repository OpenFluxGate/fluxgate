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

  @Test
  void should_deny_when_rule_set_not_found_and_strategy_deny() {
    // given
    RateLimitRuleSetProvider emptyProvider = id -> Optional.empty();

    AtomicBoolean limiterCalled = new AtomicBoolean(false);
    RateLimiter dummyLimiter =
        (context, ruleSet, permits) -> {
          limiterCalled.set(true);
          throw new IllegalStateException("Should not be called in DENY mode");
        };

    RateLimitEngine engine =
        RateLimitEngine.builder()
            .ruleSetProvider(emptyProvider)
            .rateLimiter(dummyLimiter)
            .onMissingRuleSetStrategy(OnMissingRuleSetStrategy.DENY)
            .build();

    RequestContext context =
        RequestContext.builder().endpoint("/api/test").method("GET").clientIp("127.0.0.1").build();

    // when
    RateLimitResult result = engine.check("unknown-rule-set", context);

    // then
    assertThat(limiterCalled.get()).isFalse();
    assertThat(result.isAllowed()).isFalse();
    assertThat(result.getMatchedRule()).isNull();
    assertThat(result.hasRule()).isFalse();
    assertThat(result.getNanosToWaitForRefill()).isZero();
    assertThat(result.getRemainingTokens()).isZero();
    assertThat(result.getKey().value()).isEqualTo("missing-rule-set:unknown-rule-set");
    assertThat(result.getDecisionReason())
        .isEqualTo(RateLimitResult.DecisionReason.MISSING_RULE_SET);
  }

  @Test
  void should_throw_when_rate_limiter_returns_null() {
    // given
    String ruleSetId = "auth-api-default";
    StubRuleSetProvider ruleSetProvider = new StubRuleSetProvider(ruleSetId);
    RateLimiter nullLimiter = (context, ruleSet, permits) -> null;

    RateLimitEngine engine =
        RateLimitEngine.builder()
            .ruleSetProvider(ruleSetProvider)
            .rateLimiter(nullLimiter)
            .onMissingRuleSetStrategy(OnMissingRuleSetStrategy.THROW)
            .build();

    RequestContext context =
        RequestContext.builder().endpoint("/api/test").method("GET").clientIp("127.0.0.1").build();

    // expect - check() never hands a null result to callers
    assertThatThrownBy(() -> engine.check(ruleSetId, context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("returned null");
  }

  @Test
  void snapshot_entrypoint_preserves_policy_context_permits_and_matcher_without_lookup() {
    RateLimitRuleSet snapshot = new StubRuleSetProvider("policy").ruleSet;
    RequestContext context = RequestContext.builder().endpoint("/api/test").build();
    org.fluxgate.core.match.PathPatternMatcher matcher = (pattern, path) -> true;
    RateLimitResult rejected =
        RateLimitResult.rejected(RateLimitKey.of("stub"), snapshot.getRules().get(0), 123L);
    RateLimiter limiter =
        new RateLimiter() {
          @Override
          public RateLimitResult tryConsume(RequestContext ctx, RateLimitRuleSet rs, long permits) {
            throw new AssertionError("matcher-aware execution must be preserved");
          }

          @Override
          public RateLimitResult tryConsume(
              RequestContext ctx,
              RateLimitRuleSet rs,
              long permits,
              org.fluxgate.core.match.PathPatternMatcher actualMatcher) {
            assertThat(ctx).isSameAs(context);
            assertThat(rs).isSameAs(snapshot);
            assertThat(permits).isEqualTo(3L);
            assertThat(actualMatcher).isSameAs(matcher);
            return rejected;
          }
        };
    RateLimitEngine engine =
        RateLimitEngine.builder()
            .ruleSetProvider(
                id -> {
                  throw new AssertionError("snapshot must not be re-resolved");
                })
            .rateLimiter(limiter)
            .pathMatcher(matcher)
            .build();
    assertThat(engine.checkUsingSnapshot(snapshot, context, 3L)).isSameAs(rejected);
  }

  @Test
  void snapshot_entrypoint_keeps_acl_and_null_result_guards() {
    RateLimitRuleSet plain = new StubRuleSetProvider("policy").ruleSet;
    RequestContext context = RequestContext.builder().clientIp("127.0.0.1").build();
    RateLimitEngine engine =
        RateLimitEngine.builder()
            .ruleSetProvider(
                id -> {
                  throw new AssertionError("unexpected lookup");
                })
            .rateLimiter((ctx, rs, permits) -> null)
            .build();
    RateLimitRuleSet denied =
        RateLimitRuleSet.builder("policy")
            .rules(plain.getRules())
            .keyResolver(plain.getKeyResolver())
            .accessControl(
                org.fluxgate.core.config.AccessControl.builder().addDeniedKey("stub").build())
            .build();
    assertThat(engine.checkUsingSnapshot(denied, context, 1L).getDecisionReason())
        .isEqualTo(RateLimitResult.DecisionReason.ACCESS_DENIED);
    assertThatThrownBy(() -> engine.checkUsingSnapshot(plain, context, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("policy");
    assertThatThrownBy(() -> engine.checkUsingSnapshot(null, context, 1L))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> engine.checkUsingSnapshot(plain, null, 1L))
        .isInstanceOf(NullPointerException.class);
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
