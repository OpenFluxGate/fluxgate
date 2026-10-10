package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.fluxgate.core.reload.BucketResetHandler;
import org.fluxgate.core.reload.CachingRuleSetProvider;
import org.fluxgate.core.reload.RuleReloadStrategy;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.reload.handler.InMemoryBucketResetHandler;
import org.fluxgate.spring.reload.handler.RedisBucketResetHandler;
import org.fluxgate.spring.reload.strategy.NoOpReloadStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Regression tests for N2/N9 (ObjectProvider provider resolution) and N6 (both reset handlers
 * coexist without @ConditionalOnMissingBean).
 */
class FluxgateReloadProviderResolutionTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(FluxgateReloadAutoConfiguration.class))
          .withPropertyValues("fluxgate.reload.enabled=true");

  @Nested
  @DisplayName("N2/N9 provider resolution")
  class ProviderResolution {

    @Test
    @DisplayName("no provider -> NoOpReloadStrategy, no caching provider")
    void noProvider() {
      runner.run(
          ctx -> {
            assertThat(ctx).hasSingleBean(RuleReloadStrategy.class);
            assertThat(ctx.getBean(RuleReloadStrategy.class))
                .isInstanceOf(NoOpReloadStrategy.class);
            assertThat(ctx).doesNotHaveBean(CachingRuleSetProvider.class);
          });
    }

    @Test
    @DisplayName("unique named provider -> caching provider wraps it")
    void namedProviderIsWrapped() {
      runner
          .withUserConfiguration(NamedProviderConfig.class)
          .run(ctx -> assertThat(ctx).hasSingleBean(CachingRuleSetProvider.class));
    }

    @Test
    @DisplayName("two unnamed providers -> startup fails with a clear message")
    void twoProvidersNoName() {
      runner
          .withUserConfiguration(TwoProvidersConfig.class)
          .run(
              ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure())
                    .hasStackTraceContaining("providerOne")
                    .hasStackTraceContaining("providerTwo")
                    .hasStackTraceContaining("delegateRuleSetProvider")
                    .hasStackTraceContaining("@Primary");
              });
    }

    @Test
    @DisplayName("two providers, one @Primary -> the primary one is cached")
    void twoProvidersOnePrimary() {
      runner
          .withUserConfiguration(PrimaryProvidersConfig.class)
          .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(CachingRuleSetProvider.class));
    }
  }

  @Nested
  @DisplayName("N6 both reset handlers coexist")
  class BothHandlers {

    @Test
    @DisplayName("both Redis and in-memory handlers can be registered simultaneously")
    void bothHandlersPresent() {
      runner
          .withUserConfiguration(BothHandlersConfig.class, NamedProviderConfig.class)
          .run(
              ctx -> {
                List<BucketResetHandler> handlers =
                    new ArrayList<>(ctx.getBeansOfType(BucketResetHandler.class).values());
                assertThat(handlers).hasSizeGreaterThanOrEqualTo(2);
                assertThat(handlers).anyMatch(h -> h instanceof RedisBucketResetHandler);
                assertThat(handlers).anyMatch(h -> h instanceof InMemoryBucketResetHandler);
              });
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class NamedProviderConfig {

    @Bean(name = "delegateRuleSetProvider")
    public RateLimitRuleSetProvider ruleSetProvider() {
      return id -> Optional.empty();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class TwoProvidersConfig {

    @Bean
    public RateLimitRuleSetProvider providerOne() {
      return id -> Optional.empty();
    }

    @Bean
    public RateLimitRuleSetProvider providerTwo() {
      return id -> Optional.empty();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class PrimaryProvidersConfig {

    @Bean
    @Primary
    public RateLimitRuleSetProvider providerOne() {
      return id -> Optional.empty();
    }

    @Bean
    public RateLimitRuleSetProvider providerTwo() {
      return id -> Optional.empty();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class BothHandlersConfig {

    @Bean
    public BucketResetHandler redisBucketResetHandler() {
      return new RedisBucketResetHandler(
          () -> {
            throw new RuntimeException("no real store");
          });
    }

    @Bean
    public Bucket4jRateLimiter bucket4jRateLimiter() {
      return new Bucket4jRateLimiter();
    }
  }
}
