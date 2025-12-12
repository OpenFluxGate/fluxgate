package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/** Tests for {@link LazyMetricsMongoRuleSetProvider}. */
@ExtendWith(MockitoExtension.class)
class LazyMetricsMongoRuleSetProviderTest {

  @Mock private RateLimitRuleRepository ruleRepository;
  @Mock private ObjectProvider<RateLimitMetricsRecorder> metricsRecorderProvider;
  @Mock private RateLimitMetricsRecorder metricsRecorder;

  private KeyResolver keyResolver;
  private LazyMetricsMongoRuleSetProvider provider;

  @BeforeEach
  void setUp() {
    keyResolver = context -> new RateLimitKey("test-key");
    provider =
        new LazyMetricsMongoRuleSetProvider(ruleRepository, keyResolver, metricsRecorderProvider);
  }

  @Test
  void shouldThrowWhenRuleRepositoryIsNull() {
    assertThatThrownBy(
            () -> new LazyMetricsMongoRuleSetProvider(null, keyResolver, metricsRecorderProvider))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("ruleRepository must not be null");
  }

  @Test
  void shouldThrowWhenKeyResolverIsNull() {
    assertThatThrownBy(
            () -> new LazyMetricsMongoRuleSetProvider(ruleRepository, null, metricsRecorderProvider))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("keyResolver must not be null");
  }

  @Test
  void shouldReturnEmptyWhenNoRulesFound() {
    when(ruleRepository.findByRuleSetId("unknown")).thenReturn(Collections.emptyList());

    Optional<RateLimitRuleSet> result = provider.findById("unknown");

    assertThat(result).isEmpty();
  }

  @Test
  void shouldReturnRuleSetWithMetricsRecorder() {
    RateLimitRule rule = createTestRule("test-rule");
    when(ruleRepository.findByRuleSetId("test-rules")).thenReturn(List.of(rule));
    when(metricsRecorderProvider.getIfAvailable()).thenReturn(metricsRecorder);

    Optional<RateLimitRuleSet> result = provider.findById("test-rules");

    assertThat(result).isPresent();
    assertThat(result.get().getId()).isEqualTo("test-rules");
  }

  @Test
  void shouldReturnRuleSetWithoutMetricsRecorderWhenNotAvailable() {
    RateLimitRule rule = createTestRule("test-rule");
    when(ruleRepository.findByRuleSetId("test-rules")).thenReturn(List.of(rule));
    when(metricsRecorderProvider.getIfAvailable()).thenReturn(null);

    Optional<RateLimitRuleSet> result = provider.findById("test-rules");

    assertThat(result).isPresent();
    assertThat(result.get().getId()).isEqualTo("test-rules");
  }

  @Test
  void shouldCacheResolvedMetricsRecorder() {
    RateLimitRule rule = createTestRule("test-rule");
    when(ruleRepository.findByRuleSetId("test-rules")).thenReturn(List.of(rule));
    when(metricsRecorderProvider.getIfAvailable()).thenReturn(metricsRecorder);

    // Call twice
    provider.findById("test-rules");
    provider.findById("test-rules");

    // Should only resolve once
    verify(metricsRecorderProvider, times(1)).getIfAvailable();
  }

  private RateLimitRule createTestRule(String ruleId) {
    return RateLimitRule.builder(ruleId)
        .name("Test Rule")
        .enabled(true)
        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).label("per-minute").build())
        .build();
  }
}
