package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.reload.*;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.fluxgate.spring.rule.CompositeRuleSetProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PublishedEpochReloadTest {
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(FluxgateProperties.class)
  static class BoundPropertiesConfiguration {}

  private ApplicationContextRunner contextRunner() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FluxgateReloadAutoConfiguration.class))
        .withUserConfiguration(BoundPropertiesConfiguration.class)
        .withPropertyValues(
            "fluxgate.reload.counter-mode=PUBLISHED_EPOCH",
            "fluxgate.redis.enabled=true",
            "fluxgate.reload.strategy=NONE");
  }

  @Test
  void disabledReloadFailsDuringActualPropertyBinding() {
    contextRunner()
        .withPropertyValues("fluxgate.reload.enabled=false")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("PUBLISHED_EPOCH requires reload.enabled=true");
            });
  }

  @Test
  void disabledCacheStillRejectsUnsupportedProviderAtStartup() {
    contextRunner()
        .withPropertyValues("fluxgate.reload.cache.enabled=false", "fluxgate.reload.strategy=AUTO")
        .withBean(
            "delegateRuleSetProvider", RateLimitRuleSetProvider.class, () -> provider(false, false))
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("authoritative fresh-read published-counter provider");
            });
  }

  @Test
  void disabledCacheAcceptsPublishedProviderWithoutCachingWrapper() {
    contextRunner()
        .withPropertyValues("fluxgate.reload.cache.enabled=false", "fluxgate.reload.strategy=AUTO")
        .withBean(
            "delegateRuleSetProvider", RateLimitRuleSetProvider.class, () -> provider(true, true))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(RateLimitRuleSetProvider.class);
              assertThat(context).doesNotHaveBean(RuleCache.class);
              assertThat(context).doesNotHaveBean(CachingRuleSetProvider.class);
              assertThat(context).hasBean("publishedEpochReloadValidation");
            });
  }

  @Test
  void validatesPrimaryRuntimeProviderInsteadOfNamedReloadDelegate() {
    contextRunner()
        .withConfiguration(AutoConfigurations.of(FluxgateRateLimiterAutoConfiguration.class))
        .withPropertyValues("fluxgate.reload.cache.enabled=false")
        .withBean(RateLimiter.class, () -> mock(RateLimiter.class))
        .withBean(
            "delegateRuleSetProvider", RateLimitRuleSetProvider.class, () -> provider(true, true))
        .withBean(
            "runtimeProvider",
            RateLimitRuleSetProvider.class,
            () -> provider(false, false),
            definition -> definition.setPrimary(true))
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("authoritative fresh-read published-counter provider");
            });
  }

  @Test
  void compositeRequiresFreshReadsFromEveryPublishedDelegate() {
    for (CompositeRuleSetProvider composite :
        new CompositeRuleSetProvider[] {
          new CompositeRuleSetProvider(provider(true, false), provider(true, true)),
          new CompositeRuleSetProvider(provider(true, true), provider(true, false))
        }) {
      assertThat(composite.requiresFreshRead()).isTrue();
      assertThat(composite.usesPublishedCounterEpochs()).isFalse();
      contextRunner()
          .withPropertyValues("fluxgate.reload.cache.enabled=false")
          .withBean("delegateRuleSetProvider", RateLimitRuleSetProvider.class, () -> composite)
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("authoritative fresh-read published-counter provider");
              });
    }
    assertThat(
            new CompositeRuleSetProvider(provider(true, true), provider(true, true))
                .usesPublishedCounterEpochs())
        .isTrue();
  }

  @Test
  void enabledCacheValidatesPublishedCachingWrapperWithoutCircularResolution() {
    contextRunner()
        .withConfiguration(AutoConfigurations.of(FluxgateRateLimiterAutoConfiguration.class))
        .withPropertyValues("fluxgate.reload.cache.enabled=true", "fluxgate.reload.strategy=AUTO")
        .withBean(RateLimiter.class, () -> mock(RateLimiter.class))
        .withBean(
            "delegateRuleSetProvider", RateLimitRuleSetProvider.class, () -> provider(true, true))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(CachingRuleSetProvider.class);
              RateLimitRuleSetProvider runtimeProvider =
                  context.getBeanProvider(RateLimitRuleSetProvider.class).getIfAvailable();
              assertThat(runtimeProvider).isSameAs(context.getBean(CachingRuleSetProvider.class));
              assertThat(runtimeProvider.usesPublishedCounterEpochs()).isTrue();
              assertThat(runtimeProvider.requiresFreshRead()).isTrue();
            });
  }

  private FluxgateProperties properties() {
    FluxgateProperties p = new FluxgateProperties();
    p.getRedis().setEnabled(true);
    p.getReload()
        .setCounterMode(FluxgateProperties.ReloadProperties.CounterReloadMode.PUBLISHED_EPOCH);
    return p;
  }

  private RateLimitRuleSetProvider provider(boolean epochs, boolean fresh) {
    return new RateLimitRuleSetProvider() {
      public Optional<RateLimitRuleSet> findById(String id) {
        return Optional.empty();
      }

      public boolean usesPublishedCounterEpochs() {
        return epochs;
      }

      public boolean requiresFreshRead() {
        return fresh;
      }
    };
  }

  private DefaultListableBeanFactory factory(RateLimitRuleSetProvider provider) {
    DefaultListableBeanFactory f = new DefaultListableBeanFactory();
    if (provider != null) f.registerSingleton("delegateRuleSetProvider", provider);
    return f;
  }

  @Test
  void legacyIsDefaultAndDoesNotRequireEpochProvider() {
    FluxgateProperties p = new FluxgateProperties();
    assertThat(p.getReload().getCounterMode())
        .isEqualTo(FluxgateProperties.ReloadProperties.CounterReloadMode.LEGACY_RESET);
    new FluxgateReloadAutoConfiguration(p)
        .publishedEpochReloadValidation(factory(null))
        .afterSingletonsInstantiated();
  }

  @Test
  void refusesLegacyMissingMixedAndCachedProviders() {
    FluxgateReloadAutoConfiguration config = new FluxgateReloadAutoConfiguration(properties());
    for (RateLimitRuleSetProvider p :
        new RateLimitRuleSetProvider[] {
          null,
          provider(false, false),
          provider(true, false),
          new CompositeRuleSetProvider(provider(true, true), provider(false, true))
        }) {
      assertThatThrownBy(
              () -> config.publishedEpochReloadValidation(factory(p)).afterSingletonsInstantiated())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("authoritative");
    }
  }

  @Test
  void rejectsPermissiveFallbackAndRedisDisabled() {
    FluxgateProperties p = properties();
    p.getRatelimit().getFallback().setMode(FluxgateProperties.FallbackMode.IN_MEMORY);
    assertUnsafe(p);
    p = properties();
    p.getRedis().setEnabled(false);
    assertUnsafe(p);
    p = properties();
    p.getRatelimit().setFailureBehavior(FluxgateProperties.FailureBehavior.ALLOW);
    assertUnsafe(p);
    p = properties();
    p.getRatelimit().setMissingRuleBehavior(FluxgateProperties.MissingRuleBehavior.ALLOW);
    assertUnsafe(p);
  }

  private void assertUnsafe(FluxgateProperties p) {
    assertThatThrownBy(
            () ->
                new FluxgateReloadAutoConfiguration(p)
                    .publishedEpochReloadValidation(factory(provider(true, true)))
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires Redis");
  }

  @Test
  void preservesRefreshButDoesNotResolveStoreOrExposeDestructiveReset() {
    FluxgateReloadAutoConfiguration config = new FluxgateReloadAutoConfiguration(properties());
    DefaultListableBeanFactory f = factory(provider(true, true));
    RedisTokenBucketStore store = mock(RedisTokenBucketStore.class);
    f.registerSingleton("store", store);
    BucketResetHandler reset =
        config.bucketResetHandler(f.getBeanProvider(RedisTokenBucketStore.class));
    f.registerSingleton("bucketResetHandler", reset);
    RuleReloadStrategy strategy = mock(RuleReloadStrategy.class);
    f.registerSingleton("strategy", strategy);
    RuleCache cache = mock(RuleCache.class);
    CachingRuleSetProvider wrapper =
        config.cachingRuleSetProvider(
            f,
            cache,
            f.getBeanProvider(RuleReloadStrategy.class),
            f.getBeanProvider(BucketResetHandler.class));
    assertThat(wrapper.usesPublishedCounterEpochs()).isTrue();
    verify(strategy).addListener(wrapper);
    verifyNoMoreInteractions(strategy);
    assertThatThrownBy(() -> reset.resetBuckets("orders"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(reset::resetAllBuckets).isInstanceOf(UnsupportedOperationException.class);
    config.publishedEpochReloadValidation(f).afterSingletonsInstantiated();
    verifyNoInteractions(store);
  }

  @Test
  void refusesCustomDestructiveHandler() {
    DefaultListableBeanFactory f = factory(provider(true, true));
    f.registerSingleton("customReset", mock(BucketResetHandler.class));
    assertThatThrownBy(
            () ->
                new FluxgateReloadAutoConfiguration(properties())
                    .publishedEpochReloadValidation(f)
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("destructive");
  }
}
