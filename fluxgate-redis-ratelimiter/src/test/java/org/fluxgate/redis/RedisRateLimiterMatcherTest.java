package org.fluxgate.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.config.RuleMatcher;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.redis.store.BucketState;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for PathPatternMatcher-aware rule filtering in {@link RedisRateLimiter}.
 *
 * <p>Verifies that {@link RedisRateLimiter#tryConsume} delegates to {@link
 * org.fluxgate.core.ratelimiter.RateLimitRuleSet#getMatchingRules} so that only rules whose {@link
 * RuleMatcher} accepts the incoming request context are enforced.
 *
 * <p>These tests run without a real Redis connection (store is mocked).
 */
@ExtendWith(MockitoExtension.class)
class RedisRateLimiterMatcherTest {

  @Mock private RedisTokenBucketStore tokenBucketStore;

  private RedisRateLimiter rateLimiter;

  @BeforeEach
  void setUp() {
    rateLimiter = new RedisRateLimiter(tokenBucketStore);
  }

  // =========================================================================
  // Path pattern filtering
  // =========================================================================

  @Test
  @DisplayName("Rule matching the request path is enforced")
  void matchingPathRuleIsEnforced() {
    RateLimitRule rule =
        RateLimitRule.builder("api-rule")
            .name("API rule")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .matcher(RuleMatcher.builder().addPathPattern("/api/**").build())
            .addBand(
                RateLimitBand.builder(Duration.ofSeconds(60), 100).label("100-per-min").build())
            .ruleSetId("rs")
            .build();

    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("rs")
            .keyResolver(new LimitScopeKeyResolver())
            .rules(List.of(rule))
            .build();

    when(tokenBucketStore.tryConsume(anyList(), anyList(), anyLong()))
        .thenReturn(BucketState.allowed(99, 1_000L, 100L, 0));

    RequestContext ctx =
        RequestContext.builder().clientIp("10.0.0.1").endpoint("/api/users").method("GET").build();

    RateLimitResult result = rateLimiter.tryConsume(ctx, ruleSet, 1);

    assertThat(result.isAllowed()).isTrue();
    assertThat(result.hasRule()).isTrue();
    assertThat(result.getMatchedRule().getId()).isEqualTo("api-rule");
  }

  @Test
  @DisplayName("Rule not matching the request path is skipped; returns allowedWithoutRule")
  void nonMatchingPathRuleIsSkipped() {
    RateLimitRule rule =
        RateLimitRule.builder("api-rule")
            .name("API rule")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .matcher(RuleMatcher.builder().addPathPattern("/api/**").build())
            .addBand(
                RateLimitBand.builder(Duration.ofSeconds(60), 100).label("100-per-min").build())
            .ruleSetId("rs")
            .build();

    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("rs")
            .keyResolver(new LimitScopeKeyResolver())
            .rules(List.of(rule))
            .build();

    RequestContext ctx =
        RequestContext.builder()
            .clientIp("10.0.0.1")
            .endpoint("/admin/dashboard")
            .method("GET")
            .build();

    RateLimitResult result = rateLimiter.tryConsume(ctx, ruleSet, 1);

    assertThat(result.isAllowed()).isTrue();
    assertThat(result.hasRule()).isFalse();
    assertThat(result.getRemainingTokens()).isEqualTo(-1L);
    verifyNoInteractions(tokenBucketStore);
  }

  @Test
  @DisplayName("Wildcard path pattern matches any endpoint")
  void wildcardPatternMatchesAnyEndpoint() {
    RateLimitRule rule =
        RateLimitRule.builder("global-rule")
            .name("Global rule")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .matcher(RuleMatcher.builder().addPathPattern("/**").build())
            .addBand(RateLimitBand.builder(Duration.ofSeconds(60), 50).label("50-per-min").build())
            .ruleSetId("rs")
            .build();

    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("rs")
            .keyResolver(new LimitScopeKeyResolver())
            .rules(List.of(rule))
            .build();

    when(tokenBucketStore.tryConsume(anyList(), anyList(), anyLong()))
        .thenReturn(BucketState.allowed(49, 2_000L, 50L, 0));

    for (String endpoint : List.of("/api/v1/users", "/admin/config", "/health", "/")) {
      RequestContext ctx =
          RequestContext.builder().clientIp("10.0.0.2").endpoint(endpoint).method("GET").build();
      RateLimitResult result = rateLimiter.tryConsume(ctx, ruleSet, 1);
      assertThat(result.isAllowed()).as("endpoint %s should be matched", endpoint).isTrue();
      assertThat(result.hasRule()).isTrue();
    }
  }

  @Test
  @DisplayName("matchAll rule (no pattern) matches every path")
  void matchAllRuleMatchesEveryPath() {
    // RuleMatcher.matchAll() has no path constraints — applies everywhere.
    RateLimitRule rule =
        RateLimitRule.builder("catch-all")
            .name("Catch all")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .matcher(RuleMatcher.matchAll())
            .addBand(RateLimitBand.builder(Duration.ofSeconds(60), 10).label("10-per-min").build())
            .ruleSetId("rs")
            .build();

    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("rs")
            .keyResolver(new LimitScopeKeyResolver())
            .rules(List.of(rule))
            .build();

    when(tokenBucketStore.tryConsume(anyList(), anyList(), anyLong()))
        .thenReturn(BucketState.allowed(9, 3_000L, 10L, 0));

    RequestContext ctx =
        RequestContext.builder()
            .clientIp("10.0.0.3")
            .endpoint("/anything/at/all")
            .method("POST")
            .build();

    RateLimitResult result = rateLimiter.tryConsume(ctx, ruleSet, 1);

    assertThat(result.isAllowed()).isTrue();
    assertThat(result.hasRule()).isTrue();
  }

  // =========================================================================
  // Two rules: one matching, one not
  // =========================================================================

  @Test
  @DisplayName("Only matching rule is enforced when two rules have different path patterns")
  void onlyMatchingRuleIsEvaluatedWhenTwoRulesExist() {
    RateLimitRule adminRule =
        RateLimitRule.builder("admin-rule")
            .name("Admin rule")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .matcher(RuleMatcher.builder().addPathPattern("/admin/**").build())
            .addBand(RateLimitBand.builder(Duration.ofSeconds(60), 5).label("5-per-min").build())
            .ruleSetId("rs")
            .build();

    RateLimitRule apiRule =
        RateLimitRule.builder("api-rule")
            .name("API rule")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .matcher(RuleMatcher.builder().addPathPattern("/api/**").build())
            .addBand(
                RateLimitBand.builder(Duration.ofSeconds(60), 100).label("100-per-min").build())
            .ruleSetId("rs")
            .build();

    // admin rule first; request goes to /api — only api-rule should be enforced
    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("rs")
            .keyResolver(new LimitScopeKeyResolver())
            .rules(List.of(adminRule, apiRule))
            .build();

    when(tokenBucketStore.tryConsume(anyList(), anyList(), anyLong()))
        .thenReturn(BucketState.allowed(99, 5_000L, 100L, 0));

    RequestContext ctx =
        RequestContext.builder()
            .clientIp("10.0.0.4")
            .endpoint("/api/orders")
            .method("POST")
            .build();

    RateLimitResult result = rateLimiter.tryConsume(ctx, ruleSet, 1);

    assertThat(result.isAllowed()).isTrue();
    assertThat(result.getMatchedRule().getId()).isEqualTo("api-rule");
    assertThat(result.getLimit()).isEqualTo(100L);
  }

  // =========================================================================
  // Disabled rules are still skipped (getMatchingRules filters them)
  // =========================================================================

  @Test
  @DisplayName("Disabled rule with matching path is skipped; returns allowedWithoutRule")
  void disabledRuleIsSkippedEvenWhenPathMatches() {
    RateLimitRule disabledRule =
        RateLimitRule.builder("disabled-api-rule")
            .name("Disabled")
            .enabled(false)
            .scope(LimitScope.PER_IP)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .matcher(RuleMatcher.builder().addPathPattern("/api/**").build())
            .addBand(
                RateLimitBand.builder(Duration.ofSeconds(60), 100).label("100-per-min").build())
            .ruleSetId("rs")
            .build();

    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("rs")
            .keyResolver(new LimitScopeKeyResolver())
            .rules(List.of(disabledRule))
            .build();

    RequestContext ctx =
        RequestContext.builder()
            .clientIp("10.0.0.5")
            .endpoint("/api/products")
            .method("GET")
            .build();

    RateLimitResult result = rateLimiter.tryConsume(ctx, ruleSet, 1);

    assertThat(result.isAllowed()).isTrue();
    assertThat(result.hasRule()).isFalse();
    verifyNoInteractions(tokenBucketStore);
  }
}
