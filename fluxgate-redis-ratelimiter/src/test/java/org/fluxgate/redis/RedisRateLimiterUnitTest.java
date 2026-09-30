package org.fluxgate.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
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
    // N17: glob metacharacters are sanitized to '_' at storage time, so the SCAN pattern
    // uses the sanitized form rather than a glob-escaped form.
    assertThat(RedisRateLimiter.bucketKeyPattern("a*b")).isEqualTo("fluxgate:bucket:{a_b:*");
    assertThat(RedisRateLimiter.bucketKeyPattern("a?b")).isEqualTo("fluxgate:bucket:{a_b:*");
    assertThat(RedisRateLimiter.bucketKeyPattern("a[b]c")).isEqualTo("fluxgate:bucket:{a_b_c:*");
    assertThat(RedisRateLimiter.bucketKeyPattern("a\\b")).isEqualTo("fluxgate:bucket:{a_b:*");
  }

  @Test
  @DisplayName("bucketKeyPattern can never match the rule set namespace")
  void bucketKeyPatternShouldNotMatchRuleSetKeys() {
    // N17: a rule set id of "*" is sanitized to "_" so the SCAN pattern stays narrow.
    String pattern = RedisRateLimiter.bucketKeyPattern("*");

    assertThat(globToRegex(pattern).matcher("fluxgate:ruleset:api-limits").matches()).isFalse();
    assertThat(globToRegex(pattern).matcher("fluxgate:rulesets").matches()).isFalse();
    assertThat(globToRegex(pattern).matcher("fluxgate:bucket:{other:r:ip:1}:b").matches())
        .isFalse();
    // The sanitized form "_" matches keys stored under the same sanitized id.
    assertThat(globToRegex(pattern).matcher("fluxgate:bucket:{_:r:ip:1}:b").matches()).isTrue();
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
}
