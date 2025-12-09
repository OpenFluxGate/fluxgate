package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.fluxgate.spring.metrics.FluxgateMetrics;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Tests for {@link FluxgateMetricsAutoConfiguration}. */
class FluxgateMetricsAutoConfigurationTest {

  @Configuration
  @EnableConfigurationProperties(FluxgateProperties.class)
  static class TestConfig {
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(FluxgateMetricsAutoConfiguration.class))
          .withUserConfiguration(TestConfig.class);

  @Test
  void shouldCreateFluxgateMetricsByDefault() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(FluxgateMetrics.class);
        });
  }

  @Test
  void shouldNotCreateFluxgateMetricsWhenDisabled() {
    contextRunner
        .withPropertyValues("fluxgate.metrics.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(FluxgateMetrics.class);
            });
  }

  @Test
  void shouldCreateFluxgateMetricsWithMeterRegistry() {
    contextRunner.run(
        context -> {
          FluxgateMetrics metrics = context.getBean(FluxgateMetrics.class);
          assertThat(metrics).isNotNull();
        });
  }
}
