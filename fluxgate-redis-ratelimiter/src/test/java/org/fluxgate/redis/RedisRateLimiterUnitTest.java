package org.fluxgate.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
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

/** Unit tests for {@link RedisRateLimiter} without requiring Redis connection. */
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

  @Test
  @DisplayName("tryConsume should skip disabled rules")
  void tryConsumeShouldSkipDisabledRules() {
    RequestContext context = createContext("192.168.1.1");

    RateLimitBand band =
        RateLimitBand.builder(Duration.ofSeconds(1), 10).label("test-band").build();

    RateLimitRule disabledRule =
        RateLimitRule.builder("disabled-rule")
            .name("Disabled Rule")
            .enabled(false)
            .scope(LimitScope.PER_IP)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(band)
            .ruleSetId("test-rule-set")
            .build();

    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("test-rule-set")
            .keyResolver(new LimitScopeKeyResolver())
            .rules(List.of(disabledRule))
            .build();

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isTrue();
    verifyNoInteractions(tokenBucketStore);
  }

  @Test
  @DisplayName("tryConsume should allow when bucket has tokens")
  void tryConsumeShouldAllowWhenBucketHasTokens() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = createRuleSet("test-rule-set");

    when(tokenBucketStore.tryConsume(anyString(), any(RateLimitBand.class), eq(1L)))
        .thenReturn(BucketState.allowed(9, System.currentTimeMillis()));

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isTrue();
    assertThat(result.getRemainingTokens()).isEqualTo(9);
  }

  @Test
  @DisplayName("tryConsume should reject when bucket is empty")
  void tryConsumeShouldRejectWhenBucketEmpty() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = createRuleSet("test-rule-set");

    when(tokenBucketStore.tryConsume(anyString(), any(RateLimitBand.class), eq(1L)))
        .thenReturn(BucketState.rejected(0, 1_000_000_000L, System.currentTimeMillis()));

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isFalse();
    assertThat(result.getNanosToWaitForRefill()).isEqualTo(1_000_000_000L);
  }

  @Test
  @DisplayName("tryConsume should record metrics when allowed")
  void tryConsumeShouldRecordMetricsWhenAllowed() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = createRuleSetWithMetrics("test-rule-set");

    when(tokenBucketStore.tryConsume(anyString(), any(RateLimitBand.class), eq(1L)))
        .thenReturn(BucketState.allowed(9, System.currentTimeMillis()));

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isTrue();
    verify(metricsRecorder).record(eq(context), any(RateLimitResult.class));
  }

  @Test
  @DisplayName("tryConsume should record metrics when rejected")
  void tryConsumeShouldRecordMetricsWhenRejected() {
    RequestContext context = createContext("192.168.1.1");
    RateLimitRuleSet ruleSet = createRuleSetWithMetrics("test-rule-set");

    when(tokenBucketStore.tryConsume(anyString(), any(RateLimitBand.class), eq(1L)))
        .thenReturn(BucketState.rejected(0, 1_000_000_000L, System.currentTimeMillis()));

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isFalse();
    verify(metricsRecorder).record(eq(context), any(RateLimitResult.class));
  }

  @Test
  @DisplayName("close should close token bucket store")
  void closeShouldCloseTokenBucketStore() {
    rateLimiter.close();

    verify(tokenBucketStore).close();
  }

  @Test
  @DisplayName("tryConsume should use default band label when null")
  void tryConsumeShouldUseDefaultBandLabelWhenNull() {
    RequestContext context = createContext("192.168.1.1");

    RateLimitBand bandWithNullLabel =
        RateLimitBand.builder(Duration.ofSeconds(1), 10).label(null).build();

    RateLimitRule rule =
        RateLimitRule.builder("rule-1")
            .name("Test Rule")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(bandWithNullLabel)
            .ruleSetId("test-rule-set")
            .build();

    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("test-rule-set")
            .keyResolver(new LimitScopeKeyResolver())
            .rules(List.of(rule))
            .build();

    when(tokenBucketStore.tryConsume(contains(":default"), any(RateLimitBand.class), eq(1L)))
        .thenReturn(BucketState.allowed(9, System.currentTimeMillis()));

    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    assertThat(result.isAllowed()).isTrue();
    verify(tokenBucketStore).tryConsume(contains(":default"), any(RateLimitBand.class), eq(1L));
  }

  private RateLimitRuleSet createRuleSet(String ruleSetId) {
    RateLimitBand band =
        RateLimitBand.builder(Duration.ofSeconds(1), 10).label("test-band").build();

    RateLimitRule rule =
        RateLimitRule.builder("rule-1")
            .name("Test Rule")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(band)
            .ruleSetId(ruleSetId)
            .build();

    return RateLimitRuleSet.builder(ruleSetId)
        .keyResolver(new LimitScopeKeyResolver())
        .rules(List.of(rule))
        .build();
  }

  private RateLimitRuleSet createRuleSetWithMetrics(String ruleSetId) {
    RateLimitBand band =
        RateLimitBand.builder(Duration.ofSeconds(1), 10).label("test-band").build();

    RateLimitRule rule =
        RateLimitRule.builder("rule-1")
            .name("Test Rule")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(band)
            .ruleSetId(ruleSetId)
            .build();

    return RateLimitRuleSet.builder(ruleSetId)
        .keyResolver(new LimitScopeKeyResolver())
        .rules(List.of(rule))
        .metricsRecorder(metricsRecorder)
        .build();
  }

  private RequestContext createContext(String ip) {
    return RequestContext.builder().clientIp(ip).endpoint("/api/test").method("GET").build();
  }
}
