package org.fluxgate.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitAlgorithm;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.exception.ScriptExecutionException;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.key.MissingKeyBehavior;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.redis.store.BucketState;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link RedisRateLimiter} without requiring a Redis connection. */
@ExtendWith(MockitoExtension.class)
class RedisRateLimiterUnitTest {

  @Mock private RedisTokenBucketStore tokenBucketStore;

  @Mock private RateLimitMetricsRecorder metricsRecorder;

  private RedisRateLimiter rateLimiter;

  @BeforeEach
  void setUp() {
    rateLimiter = new RedisRateLimiter(tokenBucketStore);
  }

  @Test
  @DisplayName("Constructor should throw NullPointerException for null tokenBucketStore")
  void constructorShouldThrowForNullStore() {
    assertThatThrownBy(() -> new RedisRateLimiter(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("tokenBucketStore must not be null");
  }

  @Test
  @DisplayName("tryConsume should throw NullPointerException for null context")
  void tryConsumeShouldThrowForNullContext() {
    RateLimitRuleSet ruleSet = createRuleSet("test-rule-set");

    assertThatThrownBy(() -> rateLimiter.tryConsume(null, ruleSet, 1))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("context must not be null");
  }

  @Test
  @DisplayName("tryConsume should throw NullPointerException for null ruleSet")
  void tryConsumeShouldThrowForNullRuleSet() {
    RequestContext context = createContext("192.168.1.1");

    assertThatThrownBy(() -> rateLimiter.tryConsume(context, null, 1))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("ruleSet must not be null");
  }

  @Test
  @DisplayName("tryConsume should throw IllegalArgumentException for zero permits")
  void tryConsumeShouldThrowForZeroPermits() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = createRuleSet("test-rule-set");

    assertThatThrownBy(() -> rateLimiter.tryConsume(context, ruleSet, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("permits must be > 0");
  }

  @Test
  @DisplayName("tryConsume should throw IllegalArgumentException for negative permits")
  void tryConsumeShouldThrowForNegativePermits() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = createRuleSet("test-rule-set");

    assertThatThrownBy(() -> rateLimiter.tryConsume(context, ruleSet, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("permits must be > 0");
  }

  // ===== Bucket key layout =====

  @Test
  @DisplayName("Bucket keys use the bucket prefix, a hash tag and the band key label")
  void shouldBuildHashTaggedBucketKeys() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = createRuleSet("api-limits");

    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.allowed(9, 1_000L, 10L, 0));

    rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(capturedKeys())
        .containsExactly("fluxgate:bucket:{api-limits:rule-1:ip:192.168.1.1}:test-band");
  }

  @Test
  @DisplayName("Two unlabelled bands of one rule get distinct keys")
  void shouldKeepUnlabelledBandsApart() {
    RequestContext context = createContext("192.168.1.1");

    RateLimitRule rule =
        RateLimitRule.builder("rule-1")
            .name("Two bands")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10).build())
            .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).build())
            .ruleSetId("api-limits")
            .build();

    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("api-limits")
            .keyResolver(new LimitScopeKeyResolver())
            .rules(List.of(rule))
            .build();

    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.allowed(9, 1_000L, 10L, 0));

    rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(capturedKeys())
        .containsExactly(
            "fluxgate:bucket:{api-limits:rule-1:ip:192.168.1.1}:10-per-1s",
            "fluxgate:bucket:{api-limits:rule-1:ip:192.168.1.1}:100-per-60s");
  }

  @Test
  @DisplayName("A TOKEN_BUCKET label 'x:fw' does not collide with a FIXED_WINDOW band 'x'")
  void bandLabelsCannotForgeTheFixedWindowSuffix() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRule rule =
        RateLimitRule.builder("rule-1")
            .name("Label collision")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).label("x:fw").build())
            .addBand(
                RateLimitBand.builder(Duration.ofMinutes(1), 10)
                    .label("x")
                    .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                    .build())
            .ruleSetId("api-limits")
            .build();
    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("api-limits")
            .keyResolver(new LimitScopeKeyResolver())
            .rules(List.of(rule))
            .build();
    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.allowed(9, 1_000L, 10L, 0));

    rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(capturedKeys())
        .containsExactly(
            "fluxgate:bucket:{api-limits:rule-1:ip:192.168.1.1}:x%3Afw",
            "fluxgate:bucket:{api-limits:rule-1:ip:192.168.1.1}:x:fw")
        .doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("Rule ids 'a:b' and 'a_b' get distinct buckets")
  void ruleIdsThatUsedToCollideGetDistinctBuckets() {
    RequestContext context = createContext("192.168.1.1");
    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.allowed(9, 1_000L, 10L, 0));

    rateLimiter.tryConsume(context, createRuleSet("rs:1"), 1);
    rateLimiter.tryConsume(context, createRuleSet("rs_1"), 1);

    ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
    verify(tokenBucketStore, times(2)).tryConsume(keys.capture(), anyList(), anyLong());
    assertThat(keys.getAllValues().get(0)).doesNotContainAnyElementsOf(keys.getAllValues().get(1));
  }

  @Test
  @DisplayName("All bands of one rule go into a single store call")
  void shouldSendAllBandsOfARuleInOneCall() {
    RequestContext context = createContext("192.168.1.1");

    RateLimitRule rule =
        RateLimitRule.builder("rule-1")
            .name("Two bands")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10).label("fast").build())
            .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).label("slow").build())
            .ruleSetId("api-limits")
            .build();

    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("api-limits")
            .keyResolver(new LimitScopeKeyResolver())
            .rules(List.of(rule))
            .build();

    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.allowed(9, 1_000L, 10L, 0));

    rateLimiter.tryConsume(context, ruleSet, 1);

    verify(tokenBucketStore, times(1)).tryConsume(anyList(), anyList(), anyLong());
  }

  // ===== bucketKeyPattern =====

  @Test
  @DisplayName("bucketKeyPattern is anchored on the bucket prefix")
  void bucketKeyPatternShouldBeAnchoredOnTheBucketPrefix() {
    assertThat(RedisRateLimiter.bucketKeyPattern("api-limits"))
        .isEqualTo("fluxgate:bucket:{api-limits:*")
        .startsWith(RedisRateLimiter.BUCKET_KEY_PREFIX);
  }

  @Test
  @DisplayName("bucketKeyPattern sanitizes glob metacharacters in the rule set id (N17)")
  void bucketKeyPatternShouldEscapeGlobMetacharacters() {
    // N17: glob metacharacters are percent-escaped at storage time, so the SCAN pattern uses
    // the escaped form rather than a glob-escaped form.
    assertThat(RedisRateLimiter.bucketKeyPattern("a*b")).isEqualTo("fluxgate:bucket:{a%2Ab:*");
    assertThat(RedisRateLimiter.bucketKeyPattern("a?b")).isEqualTo("fluxgate:bucket:{a%3Fb:*");
    assertThat(RedisRateLimiter.bucketKeyPattern("a[b]c"))
        .isEqualTo("fluxgate:bucket:{a%5Bb%5Dc:*");
    assertThat(RedisRateLimiter.bucketKeyPattern("a\\b")).isEqualTo("fluxgate:bucket:{a%5Cb:*");
  }

  @Test
  @DisplayName("bucketKeyPattern can never match the rule set namespace")
  void bucketKeyPatternShouldNotMatchRuleSetKeys() {
    // N17: a rule set id of "*" is escaped to "%2A" so the SCAN pattern stays narrow.
    String pattern = RedisRateLimiter.bucketKeyPattern("*");

    assertThat(globToRegex(pattern).matcher("fluxgate:ruleset:api-limits").matches()).isFalse();
    assertThat(globToRegex(pattern).matcher("fluxgate:rulesets").matches()).isFalse();
    assertThat(globToRegex(pattern).matcher("fluxgate:bucket:{other:r:ip:1}:b").matches())
        .isFalse();
    // The escaped form matches keys stored under the same escaped id, and no other id.
    assertThat(globToRegex(pattern).matcher("fluxgate:bucket:{%2A:r:ip:1}:b").matches()).isTrue();
    assertThat(globToRegex(pattern).matcher("fluxgate:bucket:{_:r:ip:1}:b").matches()).isFalse();
  }

  @Test
  @DisplayName("The produced keys are matched by the produced pattern")
  void patternShouldMatchTheKeysTheLimiterWrites() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = createRuleSet("api-limits");

    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.allowed(9, 1_000L, 10L, 0));

    rateLimiter.tryConsume(context, ruleSet, 1);

    String pattern = RedisRateLimiter.bucketKeyPattern("api-limits");
    assertThat(capturedKeys())
        .allSatisfy(key -> assertThat(globToRegex(pattern).matcher(key).matches()).isTrue());
  }

  @Test
  @DisplayName("bucketKeyPattern rejects a null rule set id")
  void bucketKeyPatternShouldRejectNull() {
    assertThatThrownBy(() -> RedisRateLimiter.bucketKeyPattern(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("ruleSetId must not be null");
  }

  // ===== Decisions =====

  @Test
  @DisplayName("Disabled rules yield allowedWithoutRule with unknown remaining, not MAX_VALUE")
  void tryConsumeShouldSkipDisabledRules() {
    RequestContext context = createContext("192.168.1.1");

    RateLimitRule disabledRule =
        RateLimitRule.builder("disabled-rule")
            .name("Disabled Rule")
            .enabled(false)
            .scope(LimitScope.PER_IP)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10).label("test-band").build())
            .ruleSetId("test-rule-set")
            .build();

    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("test-rule-set")
            .keyResolver(new LimitScopeKeyResolver())
            .rules(List.of(disabledRule))
            .build();

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isTrue();
    assertThat(result.hasRule()).isFalse();
    assertThat(result.getRemainingTokens()).isEqualTo(-1L);
    assertThat(result.getMatchedRule()).isNull();
    verifyNoInteractions(tokenBucketStore);
  }

  @Test
  @DisplayName("An allowed result carries limit, reset time, policy and band label")
  void tryConsumeShouldPropagateBindingBandOnAllow() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = createRuleSet("test-rule-set");

    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.allowed(9, 4_242L, 10L, 0));

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isTrue();
    assertThat(result.getRemainingTokens()).isEqualTo(9);
    assertThat(result.getNanosToWaitForRefill()).isZero();
    assertThat(result.getLimit()).isEqualTo(10L);
    assertThat(result.getResetTimeMillis()).isEqualTo(4_242L);
    assertThat(result.getPolicy()).isEqualTo(OnLimitExceedPolicy.REJECT_REQUEST);
    assertThat(result.getBandLabel()).isEqualTo("test-band");
    assertThat(result.getKey().value()).isEqualTo("ip:192.168.1.1");
  }

  @Test
  @DisplayName("A rejected result carries the real remaining tokens of the rejecting band")
  void tryConsumeShouldPropagateRejectingBand() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = createRuleSet("test-rule-set");

    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.rejected(0, 1_000_000_000L, 9_999L, 10L, 0));

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isFalse();
    assertThat(result.getRemainingTokens()).isZero();
    assertThat(result.getNanosToWaitForRefill()).isEqualTo(1_000_000_000L);
    assertThat(result.getLimit()).isEqualTo(10L);
    assertThat(result.getResetTimeMillis()).isEqualTo(9_999L);
    assertThat(result.getPolicy()).isEqualTo(OnLimitExceedPolicy.REJECT_REQUEST);
    assertThat(result.getBandLabel()).isEqualTo("test-band");
  }

  @Test
  @DisplayName("Evaluation stops at the first rejecting rule")
  void shouldStopAtTheFirstRejectingRule() {
    RequestContext context = createContext("192.168.1.1");

    RateLimitRule first = rule("first", "test-rule-set", LimitScope.PER_IP);
    RateLimitRule second = rule("second", "test-rule-set", LimitScope.GLOBAL);

    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("test-rule-set")
            .keyResolver(new LimitScopeKeyResolver())
            .rules(List.of(first, second))
            .build();

    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.rejected(0, 1_000L, 1L, 10L, 0));

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isFalse();
    assertThat(result.getMatchedRule().getId()).isEqualTo("first");
    verify(tokenBucketStore, times(1)).tryConsume(anyList(), anyList(), anyLong());
  }

  @Test
  @DisplayName("Across rules the most restrictive result is reported")
  void shouldReportTheMostRestrictiveRuleOnAllow() {
    RequestContext context = createContext("192.168.1.1");

    RateLimitRule first = rule("first", "test-rule-set", LimitScope.PER_IP);
    RateLimitRule second = rule("second", "test-rule-set", LimitScope.GLOBAL);

    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("test-rule-set")
            .keyResolver(new LimitScopeKeyResolver())
            .rules(List.of(first, second))
            .build();

    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.allowed(9, 1_000L, 10L, 0))
        .thenReturn(BucketState.allowed(2, 2_000L, 10L, 0));

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isTrue();
    assertThat(result.getRemainingTokens()).isEqualTo(2);
    assertThat(result.getMatchedRule().getId()).isEqualTo("second");
    assertThat(result.getResetTimeMillis()).isEqualTo(2_000L);
  }

  @Test
  @DisplayName("A missing key becomes a rejected result with a synthetic key")
  void shouldRejectWhenTheKeyCannotBeResolved() {
    // No user id in the context, and the resolver is configured to reject rather than fall back
    RequestContext context = RequestContext.builder().endpoint("/api/test").method("GET").build();

    RateLimitRule rule = rule("per-user", "test-rule-set", LimitScope.PER_USER);

    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("test-rule-set")
            .keyResolver(new LimitScopeKeyResolver(MissingKeyBehavior.REJECT))
            .rules(List.of(rule))
            .build();

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isFalse();
    assertThat(result.getKey().value()).isEqualTo("missing-key:per-user");
    assertThat(result.getNanosToWaitForRefill()).isZero();
    assertThat(result.getLimit()).isEqualTo(-1L);
    assertThat(result.getResetTimeMillis()).isEqualTo(-1L);
    verifyNoInteractions(tokenBucketStore);
  }

  @Test
  @DisplayName("The missing-key synthetic key keeps its prefix when the rule id is rewritten")
  void missingKeyPrefixSurvivesARewrittenRuleId() {
    RequestContext context = RequestContext.builder().endpoint("/api/test").method("GET").build();
    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("test-rule-set")
            .keyResolver(new LimitScopeKeyResolver(MissingKeyBehavior.REJECT))
            .rules(List.of(rule("per user", "test-rule-set", LimitScope.PER_USER)))
            .build();

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isFalse();
    assertThat(result.getKey().value()).matches("missing-key:h:per_user:[0-9a-f]{16}");
  }

  // ===== Cross-rule atomicity =====

  @Test
  @DisplayName("Compensation: a later rejection refunds the rules already charged")
  void shouldRefundEarlierRulesWhenALaterRuleRejects() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = twoRuleSet();

    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(new BucketState(true, 9, 0L, 1_000L, 10L, 0, 4242L))
        .thenReturn(BucketState.rejected(0, 1_000L, 1L, 10L, 0));

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isFalse();
    assertThat(result.getMatchedRule().getId()).isEqualTo("second");
    verify(tokenBucketStore)
        .refund(
            eq(List.of("fluxgate:bucket:{test-rule-set:first:ip:192.168.1.1}:test-band")),
            anyList(),
            eq(1L),
            eq(4242L));
  }

  @Test
  @DisplayName("Compensation: the rule with the longest wait is reported when several reject")
  void shouldReportTheLongestWaitAcrossRejectingRules() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = twoRuleSet();

    // the first rule rejects with a 1 s wait and stops the charging...
    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.rejected(0, 1_000_000_000L, 1_000L, 10L, 0));
    // ...but the second rule would reject for 5 s: Retry-After must not say 1 s
    when(tokenBucketStore.check(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.rejected(0, 5_000_000_000L, 5_000L, 3L, 0));

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isFalse();
    assertThat(result.getNanosToWaitForRefill()).isEqualTo(5_000_000_000L);
    assertThat(result.getMatchedRule().getId()).isEqualTo("second");
    assertThat(result.getLimit()).isEqualTo(3L);
    // only the first rule was charged (and rejected), so nothing is refunded; the second one is
    // only checked, never charged
    verify(tokenBucketStore, times(1)).tryConsume(anyList(), anyList(), anyLong());
    verify(tokenBucketStore, never()).refund(anyList(), anyList(), anyLong(), anyLong());
  }

  @Test
  @DisplayName("Compensation: the longest-wait search checks at most a bounded number of rules")
  void shouldBoundTheChecksOfLaterRules() {
    RequestContext context = createContext("192.168.1.1");
    int laterRules = RedisRateLimiter.MAX_LONGEST_WAIT_CHECKS + 4;
    List<RateLimitRule> rules = new java.util.ArrayList<>();
    for (int i = 0; i <= laterRules; i++) {
      rules.add(rule("rule-" + i, "test-rule-set", LimitScope.PER_IP));
    }
    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("test-rule-set")
            .keyResolver(new LimitScopeKeyResolver())
            .rules(rules)
            .build();

    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.rejected(0, 1_000_000_000L, 1_000L, 10L, 0));
    when(tokenBucketStore.check(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.rejected(0, 2_000_000_000L, 2_000L, 10L, 0));

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isFalse();
    assertThat(result.getNanosToWaitForRefill()).isEqualTo(2_000_000_000L);
    verify(tokenBucketStore, times(RedisRateLimiter.MAX_LONGEST_WAIT_CHECKS))
        .check(anyList(), anyList(), anyLong());
  }

  @Test
  @DisplayName("Compensation: a later rule that would allow does not change the reported wait")
  void shouldKeepTheRejectingRuleWhenLaterRulesWouldAllow() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = twoRuleSet();

    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.rejected(0, 1_000_000_000L, 1_000L, 10L, 0));
    when(tokenBucketStore.check(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.allowed(5, 0L, 10L, 0));

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.getNanosToWaitForRefill()).isEqualTo(1_000_000_000L);
    assertThat(result.getMatchedRule().getId()).isEqualTo("first");
  }

  @Test
  @DisplayName("Compensation: a failing check of a later rule does not change the rejection")
  void shouldIgnoreAFailingCheckOfALaterRule() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = twoRuleSet();

    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.rejected(0, 1_000_000_000L, 1_000L, 10L, 0));
    when(tokenBucketStore.check(anyList(), anyList(), eq(1L)))
        .thenThrow(new ScriptExecutionException("boom"));

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isFalse();
    assertThat(result.getMatchedRule().getId()).isEqualTo("first");
  }

  @Test
  @DisplayName("Compensation: a Redis failure on a later rule refunds and rethrows")
  void shouldRefundEarlierRulesWhenALaterRuleFails() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = twoRuleSet();

    ScriptExecutionException failure = new ScriptExecutionException("boom");
    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(new BucketState(true, 9, 0L, 1_000L, 10L, 0, 4242L))
        .thenThrow(failure);

    assertThatThrownBy(() -> rateLimiter.tryConsume(context, ruleSet, 1)).isSameAs(failure);
    verify(tokenBucketStore).refund(anyList(), anyList(), eq(1L), eq(4242L));
  }

  @Test
  @DisplayName("Compensation: a failing refund does not change the rejection")
  void shouldStillRejectWhenTheRefundFails() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = twoRuleSet();

    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(new BucketState(true, 9, 0L, 1_000L, 10L, 0, 4242L))
        .thenReturn(BucketState.rejected(0, 1_000L, 1L, 10L, 0));
    when(tokenBucketStore.refund(anyList(), anyList(), anyLong(), anyLong()))
        .thenThrow(new ScriptExecutionException("refund failed"));

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isFalse();
    assertThat(result.getMatchedRule().getId()).isEqualTo("second");
  }

  @Test
  @DisplayName("Compensation: nothing is refunded when every rule allows")
  void shouldNotRefundWhenAllRulesAllow() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = twoRuleSet();

    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(new BucketState(true, 9, 0L, 1_000L, 10L, 0, 4242L));

    assertThat(rateLimiter.tryConsume(context, ruleSet, 1).isAllowed()).isTrue();
    verify(tokenBucketStore, never()).refund(anyList(), anyList(), anyLong(), anyLong());
  }

  @Test
  @DisplayName("One call: co-located rules go into a single store call, mapped back per rule")
  void shouldEvaluateCoLocatedRulesInOneCall() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = twoRuleSet();

    when(tokenBucketStore.canEvaluateAtomically(any())).thenReturn(true);
    // band index 1 of the concatenated list is the only band of the second rule
    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.rejected(0, 1_000L, 7_000L, 10L, 1));

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isFalse();
    assertThat(result.getMatchedRule().getId()).isEqualTo("second");
    assertThat(result.getKey().value()).isEqualTo("global");
    assertThat(result.getBandLabel()).isEqualTo("test-band");
    assertThat(result.getResetTimeMillis()).isEqualTo(7_000L);
    assertThat(capturedKeys())
        .containsExactly(
            "fluxgate:bucket:{test-rule-set:first:ip:192.168.1.1}:test-band",
            "fluxgate:bucket:{test-rule-set:second:global}:test-band");
    verify(tokenBucketStore, never()).refund(anyList(), anyList(), anyLong(), anyLong());
  }

  @Test
  @DisplayName("One call: the allowed result names the rule of the binding band")
  void shouldMapTheBindingBandOfOneCallBackToItsRule() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = twoRuleSet();

    when(tokenBucketStore.canEvaluateAtomically(any())).thenReturn(true);
    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.allowed(0, 2_000L, 10L, 0));

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isTrue();
    assertThat(result.getMatchedRule().getId()).isEqualTo("first");
    assertThat(result.getRemainingTokens()).isZero();
  }

  @Test
  @DisplayName("A key that cannot be resolved for a later rule rejects before anything is charged")
  void shouldResolveEveryKeyBeforeCharging() {
    // an IP but no user id: the first rule resolves, the second cannot
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("test-rule-set")
            .keyResolver(new LimitScopeKeyResolver(MissingKeyBehavior.REJECT))
            .rules(
                List.of(
                    rule("first", "test-rule-set", LimitScope.PER_IP),
                    rule("second", "test-rule-set", LimitScope.PER_USER)))
            .build();

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isFalse();
    assertThat(result.getKey().value()).isEqualTo("missing-key:second");
    verify(tokenBucketStore, never()).tryConsume(anyList(), anyList(), anyLong());
    verify(tokenBucketStore, never()).refund(anyList(), anyList(), anyLong(), anyLong());
  }

  @Test
  @DisplayName("A FIXED_WINDOW band keys its counter hash with the :fw suffix")
  void shouldSuffixFixedWindowKeys() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRule rule =
        RateLimitRule.builder("rule-1")
            .name("fixed")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .addBand(
                RateLimitBand.builder(Duration.ofMinutes(1), 10)
                    .label("fw")
                    .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                    .build())
            .ruleSetId("api-limits")
            .build();
    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("api-limits")
            .keyResolver(new LimitScopeKeyResolver())
            .rules(List.of(rule))
            .build();

    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.allowed(9, 1_000L, 10L, 0));

    rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(capturedKeys())
        .containsExactly("fluxgate:bucket:{api-limits:rule-1:ip:192.168.1.1}:fw:fw");
  }

  // ===== Metrics =====

  @Test
  @DisplayName("tryConsume should record metrics when allowed")
  void tryConsumeShouldRecordMetricsWhenAllowed() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = createRuleSetWithMetrics("test-rule-set");

    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.allowed(9, 1_000L, 10L, 0));

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isTrue();
    verify(metricsRecorder).record(eq(context), eq(result));
  }

  @Test
  @DisplayName("tryConsume should record metrics when rejected")
  void tryConsumeShouldRecordMetricsWhenRejected() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = createRuleSetWithMetrics("test-rule-set");

    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.rejected(0, 1_000_000_000L, 1_000L, 10L, 0));

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isFalse();
    verify(metricsRecorder).record(eq(context), eq(result));
  }

  @Test
  @DisplayName("A failing metrics recorder neither fails the call nor charges the tokens again")
  void failingRecorderDoesNotFailTheDecision() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = createRuleSetWithMetrics("test-rule-set");

    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.allowed(9, 1_000L, 10L, 0))
        .thenReturn(BucketState.rejected(0, 1_000_000_000L, 1_000L, 10L, 0));
    doThrow(new IllegalStateException("metrics backend down"))
        .when(metricsRecorder)
        .record(any(), any());

    RateLimitResult allowed = rateLimiter.tryConsume(context, ruleSet, 1);
    RateLimitResult rejected = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(allowed.isAllowed()).isTrue();
    assertThat(allowed.getRemainingTokens()).isEqualTo(9);
    assertThat(rejected.isAllowed()).isFalse();
    verify(tokenBucketStore, times(2)).tryConsume(anyList(), anyList(), anyLong());
    verify(metricsRecorder, times(2)).record(any(), any());
  }

  @Test
  @DisplayName("A recorder sneaky-throwing a checked exception does not fail the decision")
  void recorderThrowingACheckedExceptionDoesNotFailTheDecision() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = createRuleSetWithMetrics("test-rule-set");

    when(tokenBucketStore.tryConsume(anyList(), anyList(), eq(1L)))
        .thenReturn(BucketState.allowed(9, 1_000L, 10L, 0));
    doAnswer(
            invocation -> {
              throw new IOException("metrics backend down");
            })
        .when(metricsRecorder)
        .record(any(), any());

    RateLimitResult allowed = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(allowed.isAllowed()).isTrue();
    assertThat(allowed.getRemainingTokens()).isEqualTo(9);
    verify(tokenBucketStore, times(1)).tryConsume(anyList(), anyList(), anyLong());
  }

  @Test
  @DisplayName("close owns nothing and therefore closes nothing")
  void closeShouldNotTouchTheStore() {
    rateLimiter.close();

    verifyNoInteractions(tokenBucketStore);
  }

  // ===== Helpers =====

  @SuppressWarnings("unchecked")
  private List<String> capturedKeys() {
    ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
    verify(tokenBucketStore).tryConsume(keys.capture(), anyList(), anyLong());
    return keys.getValue();
  }

  /** Translates a Redis glob pattern into an equivalent regex, so tests can check matching. */
  private static java.util.regex.Pattern globToRegex(String glob) {
    StringBuilder regex = new StringBuilder();
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      switch (c) {
        case '*':
          regex.append(".*");
          break;
        case '?':
          regex.append('.');
          break;
        case '\\':
          if (i + 1 < glob.length()) {
            regex.append(java.util.regex.Pattern.quote(String.valueOf(glob.charAt(++i))));
          }
          break;
        default:
          regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
          break;
      }
    }
    return java.util.regex.Pattern.compile(regex.toString());
  }

  private static RateLimitRule rule(String id, String ruleSetId, LimitScope scope) {
    return RateLimitRule.builder(id)
        .name(id)
        .enabled(true)
        .scope(scope)
        .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
        .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10).label("test-band").build())
        .ruleSetId(ruleSetId)
        .build();
  }

  private static RateLimitRuleSet twoRuleSet() {
    return RateLimitRuleSet.builder("test-rule-set")
        .keyResolver(new LimitScopeKeyResolver())
        .rules(
            List.of(
                rule("first", "test-rule-set", LimitScope.PER_IP),
                rule("second", "test-rule-set", LimitScope.GLOBAL)))
        .build();
  }

  private RateLimitRuleSet createRuleSet(String ruleSetId) {
    return RateLimitRuleSet.builder(ruleSetId)
        .keyResolver(new LimitScopeKeyResolver())
        .rules(List.of(rule("rule-1", ruleSetId, LimitScope.PER_IP)))
        .build();
  }

  private RateLimitRuleSet createRuleSetWithMetrics(String ruleSetId) {
    return RateLimitRuleSet.builder(ruleSetId)
        .keyResolver(new LimitScopeKeyResolver())
        .rules(List.of(rule("rule-1", ruleSetId, LimitScope.PER_IP)))
        .metricsRecorder(metricsRecorder)
        .build();
  }

  private RequestContext createContext(String ip) {
    return RequestContext.builder().clientIp(ip).endpoint("/api/test").method("GET").build();
  }

  // ===== max-bucket-ttl clamp warning =====

  @Test
  @DisplayName("A TOKEN_BUCKET or SLIDING_WINDOW window longer than the TTL cap is clamped")
  void tokenAndSlidingBucketsAreClampedByTheTtlCap() {
    long capSeconds = Duration.ofDays(7).getSeconds();
    RateLimitBand tokenBucket = RateLimitBand.builder(Duration.ofDays(30), 10).build();
    RateLimitBand slidingWindow =
        RateLimitBand.builder(Duration.ofDays(30), 10)
            .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
            .build();

    assertThat(RedisRateLimiter.ttlCapShortensWindow(tokenBucket, capSeconds)).isTrue();
    assertThat(RedisRateLimiter.ttlCapShortensWindow(slidingWindow, capSeconds)).isTrue();
  }

  @Test
  @DisplayName("FIXED_WINDOW is exempt from max-bucket-ttl, so it never triggers the clamp warning")
  void fixedWindowIsNeverClampedByTheTtlCap() {
    long capSeconds = Duration.ofDays(7).getSeconds();
    RateLimitBand monthly =
        RateLimitBand.builder(Duration.ofDays(30), 1000)
            .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
            .build();

    assertThat(RedisRateLimiter.ttlCapShortensWindow(monthly, capSeconds)).isFalse();
  }

  @Test
  @DisplayName("A window within the TTL cap is not clamped")
  void shortWindowIsNotClamped() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofHours(1), 10).build();

    assertThat(RedisRateLimiter.ttlCapShortensWindow(band, Duration.ofDays(7).getSeconds()))
        .isFalse();
  }
}
