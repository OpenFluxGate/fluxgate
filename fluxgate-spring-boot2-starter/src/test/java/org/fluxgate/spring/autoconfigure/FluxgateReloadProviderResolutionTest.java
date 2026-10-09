package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.fluxgate.core.reload.CachingRuleSetProvider;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Provider resolution when several {@link RateLimitRuleSetProvider} beans exist. */
class FluxgateReloadProviderResolutionTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(FluxgateReloadAutoConfiguration.class))
          .withPropertyValues("fluxgate.reload.enabled=true");

  @Test
  void twoUnnamedProvidersFailStartupWithAClearMessage() {
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
  void primaryProviderIsCached() {
    runner
        .withUserConfiguration(PrimaryProvidersConfig.class)
        .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(CachingRuleSetProvider.class));
  }

  @Configuration(proxyBeanMethods = false)
  static class TwoProvidersConfig {

    @Bean
    RateLimitRuleSetProvider providerOne() {
      return id -> Optional.empty();
    }

    @Bean
    RateLimitRuleSetProvider providerTwo() {
      return id -> Optional.empty();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class PrimaryProvidersConfig {

    @Bean
    @Primary
    RateLimitRuleSetProvider providerOne() {
      return id -> Optional.empty();
    }

    @Bean
    RateLimitRuleSetProvider providerTwo() {
      return id -> Optional.empty();
    }
  }
}
