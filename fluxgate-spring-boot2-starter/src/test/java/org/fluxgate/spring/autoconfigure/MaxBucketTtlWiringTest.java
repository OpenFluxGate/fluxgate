package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Regression test for Task 2: max-bucket-ttl property binding. */
@DisplayName("MaxBucketTtl wiring")
class MaxBucketTtlWiringTest {

  @Test
  @DisplayName("max-bucket-ttl=2d binds to a 2-day Duration")
  void maxBucketTtlPropertyBound() {
    new ApplicationContextRunner()
        .withUserConfiguration(PropertiesOnlyConfig.class)
        .withPropertyValues("fluxgate.redis.max-bucket-ttl=2d")
        .run(
            ctx -> {
              FluxgateProperties props = ctx.getBean(FluxgateProperties.class);
              assertThat(props.getRedis().getMaxBucketTtl()).isEqualTo(Duration.ofDays(2));
            });
  }

  @Test
  @DisplayName("max-bucket-ttl defaults to 7 days")
  void maxBucketTtlDefault() {
    new ApplicationContextRunner()
        .withUserConfiguration(PropertiesOnlyConfig.class)
        .run(
            ctx -> {
              FluxgateProperties props = ctx.getBean(FluxgateProperties.class);
              assertThat(props.getRedis().getMaxBucketTtl()).isEqualTo(Duration.ofDays(7));
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(FluxgateProperties.class)
  static class PropertiesOnlyConfig {}
}
