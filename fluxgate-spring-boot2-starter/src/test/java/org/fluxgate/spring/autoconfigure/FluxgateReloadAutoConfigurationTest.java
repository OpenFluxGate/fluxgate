package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.reload.BucketResetHandler;
import org.fluxgate.core.reload.CachingRuleSetProvider;
import org.fluxgate.core.reload.RuleCache;
import org.fluxgate.core.reload.RuleReloadStrategy;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.reload.cache.CaffeineRuleCache;
import org.fluxgate.spring.reload.handler.InMemoryBucketResetHandler;
import org.fluxgate.spring.reload.strategy.CompositeReloadStrategy;
import org.fluxgate.spring.reload.strategy.NoOpReloadStrategy;
import org.fluxgate.spring.reload.strategy.PollingReloadStrategy;
import org.fluxgate.spring.reload.strategy.RedisPubSubReloadStrategy;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tests for {@link FluxgateReloadAutoConfiguration}.
 *
 * <p>Covers the pieces that decide whether a rule change actually reaches this instance: which
 * strategy is selected, whether the Pub/Sub subscription gets a polling backstop (Redis Pub/Sub is
 * at-most-once, so without one a dropped message leaves the node stale), and which bucket reset
 * handler matches the active limiter.
 */
class FluxgateReloadAutoConfigurationTest {

  /** A rule set provider under the name the reload configuration expects. */
  @Configuration(proxyBeanMethods = false)
  static class DelegateProviderConfig {

    @Bean
    RateLimitRuleSetProvider delegateRuleSetProvider() {
      return ruleSetId ->
          Optional.of(
              RateLimitRuleSet.builder(ruleSetId)
                  .rules(
                      List.of(
                          RateLimitRule.builder(ruleSetId + "-rule")
                              .name(ruleSetId)
                              .enabled(true)
                              .ruleSetId(ruleSetId)
                              .scope(LimitScope.PER_IP)
                              .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).build())
                              .build()))
                  .keyResolver(new LimitScopeKeyResolver())
                  .build());
    }
  }

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  FluxgateResilienceAutoConfiguration.class,
                  FluxgateRedisAutoConfiguration.class,
                  FluxgateRateLimiterAutoConfiguration.class,
                  FluxgateReloadAutoConfiguration.class))
          .withUserConfiguration(DelegateProviderConfig.class);

  @Nested
  class CacheWiring {

    @Test
    void shouldBindTheNegativeTtl() {
      contextRunner
          .withPropertyValues("fluxgate.reload.cache.negative-ttl=30s")
          .run(
              context -> {
                CaffeineRuleCache cache = (CaffeineRuleCache) context.getBean(RuleCache.class);
                assertThat(cache.getNegativeTtl()).isEqualTo(Duration.ofSeconds(30));
              });
    }

    @Test
    void shouldDefaultTheNegativeTtlToFiveSeconds() {
      contextRunner.run(
          context ->
              assertThat(((CaffeineRuleCache) context.getBean(RuleCache.class)).getNegativeTtl())
                  .isEqualTo(Duration.ofSeconds(5)));
    }

    @Test
    void shouldAllowDisablingNegativeCaching() {
      contextRunner
          .withPropertyValues("fluxgate.reload.cache.negative-ttl=0s")
          .run(
              context ->
                  assertThat(
                          ((CaffeineRuleCache) context.getBean(RuleCache.class)).getNegativeTtl())
                      .isEqualTo(Duration.ZERO));
    }

    @Test
    void shouldWrapTheDelegateProviderInACachingProvider() {
      contextRunner.run(
          context -> {
            assertThat(context).hasSingleBean(CachingRuleSetProvider.class);
            assertThat(context.getBean(RateLimitRuleSetProvider.class))
                .isInstanceOf(CachingRuleSetProvider.class);
          });
    }
  }

  @Nested
  class StrategySelection {

    @Test
    void shouldUsePollingWhenRedisIsNotEnabled() {
      contextRunner.run(
          context ->
              assertThat(context.getBean(RuleReloadStrategy.class))
                  .isInstanceOf(PollingReloadStrategy.class));
    }

    @Test
    void shouldPairPubSubWithAPollingBackstopUnderAuto() {
      contextRunner
          .withPropertyValues(
              "fluxgate.redis.enabled=true",
              "fluxgate.redis.uri=redis://localhost:1",
              "fluxgate.redis.timeout-ms=200")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                CompositeReloadStrategy strategy =
                    (CompositeReloadStrategy) context.getBean(RuleReloadStrategy.class);
                assertThat(strategy.getPrimary()).isInstanceOf(RedisPubSubReloadStrategy.class);
                assertThat(strategy.getBackstops()).hasSize(1);
                assertThat(strategy.getBackstops().get(0))
                    .isInstanceOf(PollingReloadStrategy.class);
              });
    }

    @Test
    void shouldRunPubSubAloneWhenTheBackstopIsDisabled() {
      contextRunner
          .withPropertyValues(
              "fluxgate.redis.enabled=true",
              "fluxgate.redis.uri=redis://localhost:1",
              "fluxgate.redis.timeout-ms=200",
              "fluxgate.reload.pubsub.backstop-polling-interval=0s")
          .run(
              context ->
                  assertThat(context.getBean(RuleReloadStrategy.class))
                      .isInstanceOf(RedisPubSubReloadStrategy.class));
    }

    @Test
    void shouldPassTheSigningSecretToThePubSubStrategy() {
      contextRunner
          .withPropertyValues(
              "fluxgate.redis.enabled=true",
              "fluxgate.redis.uri=redis://localhost:1",
              "fluxgate.redis.timeout-ms=200",
              "fluxgate.reload.pubsub.backstop-polling-interval=0s",
              "fluxgate.reload.pubsub.secret=unit-test-only-secret",
              "fluxgate.reload.pubsub.max-message-age=30s")
          .run(
              context -> {
                RedisPubSubReloadStrategy strategy =
                    (RedisPubSubReloadStrategy) context.getBean(RuleReloadStrategy.class);
                assertThat(strategy.isVerifyingSignatures()).isTrue();
                assertThat(strategy.getMaxMessageAge()).isEqualTo(Duration.ofSeconds(30));
              });
    }

    @Test
    void shouldLeaveThePubSubStrategyUnverifiedWithoutASecret() {
      contextRunner
          .withPropertyValues(
              "fluxgate.redis.enabled=true",
              "fluxgate.redis.uri=redis://localhost:1",
              "fluxgate.redis.timeout-ms=200",
              "fluxgate.reload.pubsub.backstop-polling-interval=0s")
          .run(
              context -> {
                RedisPubSubReloadStrategy strategy =
                    (RedisPubSubReloadStrategy) context.getBean(RuleReloadStrategy.class);
                assertThat(strategy.isVerifyingSignatures()).isFalse();
                assertThat(strategy.getMaxMessageAge()).isEqualTo(Duration.ofMinutes(5));
              });
    }

    @Test
    void shouldDisableHotReloadEntirelyWhenTheStrategyIsNone() {
      contextRunner
          .withPropertyValues("fluxgate.reload.strategy=NONE")
          .run(
              context ->
                  assertThat(context.getBean(RuleReloadStrategy.class))
                      .isInstanceOf(NoOpReloadStrategy.class));
    }
  }

  @Nested
  class BucketResetWiring {

    @Test
    void shouldUseTheInMemoryResetHandlerWithTheInMemoryLimiter() {
      contextRunner.run(
          context ->
              assertThat(context.getBean(BucketResetHandler.class))
                  .isInstanceOf(InMemoryBucketResetHandler.class));
    }

    @Test
    void shouldUseTheInMemoryResetHandlerWhenModeIsInMemoryDespiteRedis() {
      contextRunner
          .withPropertyValues(
              "fluxgate.ratelimit.mode=IN_MEMORY",
              "fluxgate.redis.enabled=true",
              "fluxgate.redis.uri=redis://localhost:1",
              "fluxgate.redis.timeout-ms=200",
              "fluxgate.reload.pubsub.backstop-polling-interval=0s")
          .run(
              context ->
                  assertThat(context.getBean(BucketResetHandler.class))
                      .isInstanceOf(InMemoryBucketResetHandler.class));
    }
  }
}
