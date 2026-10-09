package org.fluxgate.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.fluxgate.core.config.AccessControl;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.config.RuleMatcher;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.RateLimitEngine.OnMissingRuleSetStrategy;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.match.CidrSet;
import org.fluxgate.core.match.SimpleAntPathMatcher;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RateLimitEngine – matching, AccessControl, PathMatcher")
class RateLimitEngineMatchingTest {

  private static final RateLimitBand BAND =
      RateLimitBand.builder(Duration.ofSeconds(60), 100).build();

  private static RateLimitRule rule(String id, int priority, RuleMatcher matcher) {
    return RateLimitRule.builder(id)
        .scope(LimitScope.PER_IP)
        .keyStrategyId("ip")
        .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
        .priority(priority)
        .matcher(matcher)
        .addBand(BAND)
        .build();
  }

  private static RateLimitRuleSet ruleSet(String id, AccessControl ac, RateLimitRule... rules) {
    return RateLimitRuleSet.builder(id)
        .rules(Arrays.asList(rules))
        .keyResolver(new LimitScopeKeyResolver())
        .accessControl(ac)
        .build();
  }

  private static RateLimitEngine engine(RateLimitRuleSet rs, RateLimiter limiter) {
    RateLimitRuleSetProvider provider =
        ruleSetId -> "test".equals(ruleSetId) ? Optional.of(rs) : Optional.empty();
    return RateLimitEngine.builder()
        .ruleSetProvider(provider)
        .rateLimiter(limiter)
        .onMissingRuleSetStrategy(OnMissingRuleSetStrategy.ALLOW)
        .pathMatcher(SimpleAntPathMatcher.INSTANCE)
        .build();
  }

  // ===== AccessControl: ALLOW_BYPASS =====

  @Nested
  @DisplayName("AccessControl ALLOW_BYPASS")
  class AllowBypassTests {

    @Test
    @DisplayName("IP in allowed CIDR skips limiter and returns allowedWithoutRule")
    void allowedIp_skipsLimiter() {
      AccessControl ac =
          AccessControl.builder()
              .allowedIps(CidrSet.of(Collections.singletonList("10.0.0.0/8")))
              .build();
      RateLimitRule r = rule("r1", 0, RuleMatcher.matchAll());
      RateLimitRuleSet rs = ruleSet("test", ac, r);

      RateLimiter limiter =
          (ctx, ruleSet2, permits) -> {
            throw new AssertionError("limiter must not be called on ALLOW_BYPASS");
          };

      RequestContext ctx =
          RequestContext.builder().clientIp("10.0.0.5").endpoint("/api/x").method("GET").build();
      RateLimitResult result = engine(rs, limiter).check("test", ctx);

      assertThat(result.isAllowed()).isTrue();
      assertThat(result.hasRule()).isFalse();
    }

    @Test
    @DisplayName("key in allowed set skips limiter")
    void allowedKey_skipsLimiter() {
      AccessControl ac = AccessControl.builder().addAllowedKey("ip:192.168.1.1").build();
      RateLimitRule r = rule("r1", 0, RuleMatcher.matchAll());
      RateLimitRuleSet rs = ruleSet("test", ac, r);

      RateLimiter limiter =
          (ctx, ruleSet2, permits) -> {
            throw new AssertionError("limiter must not be called on ALLOW_BYPASS");
          };

      RequestContext ctx =
          RequestContext.builder().clientIp("192.168.1.1").endpoint("/api/x").method("GET").build();
      assertThat(engine(rs, limiter).check("test", ctx).isAllowed()).isTrue();
    }
  }

  // ===== AccessControl: DENY =====

  @Nested
  @DisplayName("AccessControl DENY")
  class DenyTests {

    @Test
    @DisplayName("IP in denied CIDR returns rejected result without calling limiter")
    void deniedIp_returnsRejected() {
      AccessControl ac =
          AccessControl.builder()
              .deniedIps(CidrSet.of(Collections.singletonList("172.16.0.0/12")))
              .build();
      RateLimitRule r = rule("r1", 0, RuleMatcher.matchAll());
      RateLimitRuleSet rs = ruleSet("test", ac, r);

      RateLimiter limiter =
          (ctx, ruleSet2, permits) -> {
            throw new AssertionError("limiter must not be called on DENY");
          };

      RequestContext ctx =
          RequestContext.builder().clientIp("172.16.1.5").endpoint("/api/x").method("GET").build();
      RateLimitResult result = engine(rs, limiter).check("test", ctx);

      assertThat(result.isAllowed()).isFalse();
      assertThat(result.getNanosToWaitForRefill()).isEqualTo(0L);
      assertThat(result.getKey()).isNotNull();
      assertThat(result.getKey().value()).startsWith("denied:");
    }

    @Test
    @DisplayName("denied key returns rejected result")
    void deniedKey_returnsRejected() {
      AccessControl ac = AccessControl.builder().addDeniedKey("ip:1.2.3.4").build();
      RateLimitRule r = rule("r1", 0, RuleMatcher.matchAll());
      RateLimitRuleSet rs = ruleSet("test", ac, r);

      RateLimiter limiter =
          (ctx, ruleSet2, permits) -> {
            throw new AssertionError("limiter must not be called on DENY");
          };

      RequestContext ctx =
          RequestContext.builder().clientIp("1.2.3.4").endpoint("/api/x").method("GET").build();
      RateLimitResult result = engine(rs, limiter).check("test", ctx);

      assertThat(result.isAllowed()).isFalse();
      assertThat(result.getNanosToWaitForRefill()).isEqualTo(0L);
    }
  }

  // ===== getMatchingRules priority ordering =====

  @Nested
  @DisplayName("getMatchingRules priority and tie-break")
  class MatchingRulesTests {

    @Test
    @DisplayName("priority DESC ordering is respected")
    void priorityDescOrdering() {
      RateLimitRule low = rule("low-rule", 0, RuleMatcher.matchAll());
      RateLimitRule high = rule("high-rule", 10, RuleMatcher.matchAll());
      RateLimitRuleSet rs =
          RateLimitRuleSet.builder("test")
              .rules(Arrays.asList(low, high))
              .keyResolver(new LimitScopeKeyResolver())
              .build();
      RequestContext ctx =
          RequestContext.builder().clientIp("1.1.1.1").endpoint("/api").method("GET").build();
      List<RateLimitRule> matching = rs.getMatchingRules(ctx, SimpleAntPathMatcher.INSTANCE);
      assertThat(matching).hasSize(2);
      assertThat(matching.get(0).getId()).isEqualTo("high-rule");
      assertThat(matching.get(1).getId()).isEqualTo("low-rule");
    }

    @Test
    @DisplayName("tie-break by id ASC is deterministic")
    void tieBreakByIdAsc() {
      RateLimitRule a = rule("a-rule", 5, RuleMatcher.matchAll());
      RateLimitRule b = rule("b-rule", 5, RuleMatcher.matchAll());
      RateLimitRule c = rule("c-rule", 5, RuleMatcher.matchAll());
      RateLimitRuleSet rs =
          RateLimitRuleSet.builder("test")
              .rules(Arrays.asList(c, a, b))
              .keyResolver(new LimitScopeKeyResolver())
              .build();
      RequestContext ctx =
          RequestContext.builder().clientIp("1.1.1.1").endpoint("/api").method("GET").build();
      List<RateLimitRule> matching = rs.getMatchingRules(ctx, SimpleAntPathMatcher.INSTANCE);
      assertThat(matching)
          .extracting(RateLimitRule::getId)
          .containsExactly("a-rule", "b-rule", "c-rule");
    }

    @Test
    @DisplayName("disabled rules are excluded from getMatchingRules")
    void disabledRulesExcluded() {
      RateLimitRule active = rule("active", 0, RuleMatcher.matchAll());
      RateLimitRule disabled =
          RateLimitRule.builder("disabled")
              .enabled(false)
              .scope(LimitScope.PER_IP)
              .keyStrategyId("ip")
              .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
              .addBand(BAND)
              .build();
      RateLimitRuleSet rs =
          RateLimitRuleSet.builder("test")
              .rules(Arrays.asList(active, disabled))
              .keyResolver(new LimitScopeKeyResolver())
              .build();
      RequestContext ctx =
          RequestContext.builder().clientIp("1.1.1.1").endpoint("/api").method("GET").build();
      List<RateLimitRule> matching = rs.getMatchingRules(ctx, SimpleAntPathMatcher.INSTANCE);
      assertThat(matching).hasSize(1);
      assertThat(matching.get(0).getId()).isEqualTo("active");
    }

    @Test
    @DisplayName("path-filtered rule excluded when path does not match")
    void pathFilteredRuleExcluded() {
      RateLimitRule apiRule =
          rule("api-rule", 0, RuleMatcher.builder().addPathPattern("/api/**").build());
      RateLimitRule adminRule =
          rule("admin-rule", 0, RuleMatcher.builder().addPathPattern("/admin/**").build());
      RateLimitRuleSet rs =
          RateLimitRuleSet.builder("test")
              .rules(Arrays.asList(apiRule, adminRule))
              .keyResolver(new LimitScopeKeyResolver())
              .build();
      RequestContext ctx =
          RequestContext.builder().clientIp("1.1.1.1").endpoint("/api/users").method("GET").build();
      List<RateLimitRule> matching = rs.getMatchingRules(ctx, SimpleAntPathMatcher.INSTANCE);
      assertThat(matching).hasSize(1);
      assertThat(matching.get(0).getId()).isEqualTo("api-rule");
    }
  }

  // ===== getAttribute type checking =====

  @Nested
  @DisplayName("getAttribute(String, Class<T>) type checking")
  class GetAttributeTypeCheckTests {

    @Test
    @DisplayName("correct type returns non-empty Optional")
    void correctType_returnsValue() {
      RateLimitRule r =
          RateLimitRule.builder("r1")
              .attribute("tier", "premium")
              .scope(LimitScope.PER_IP)
              .keyStrategyId("ip")
              .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
              .addBand(BAND)
              .build();
      Optional<String> result = r.getAttribute("tier", String.class);
      assertThat(result).hasValue("premium");
    }

    @Test
    @DisplayName("type mismatch returns empty Optional (no exception)")
    void typeMismatch_returnsEmpty() {
      RateLimitRule r =
          RateLimitRule.builder("r1")
              .attribute("tier", "premium")
              .scope(LimitScope.PER_IP)
              .keyStrategyId("ip")
              .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
              .addBand(BAND)
              .build();
      Optional<Integer> result = r.getAttribute("tier", Integer.class);
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("missing key returns empty Optional")
    void missingKey_returnsEmpty() {
      RateLimitRule r =
          RateLimitRule.builder("r1")
              .scope(LimitScope.PER_IP)
              .keyStrategyId("ip")
              .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
              .addBand(BAND)
              .build();
      Optional<String> result = r.getAttribute("nonexistent", String.class);
      assertThat(result).isEmpty();
    }
  }
}
