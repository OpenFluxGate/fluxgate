package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.fluxgate.spring.metrics.MicrometerMetricsRecorder;
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
  void shouldCreateMicrometerMetricsRecorderByDefault() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(MicrometerMetricsRecorder.class);
        });
  }

  @Test
  void shouldNotCreateMicrometerMetricsRecorderWhenDisabled() {
    contextRunner
        .withPropertyValues("fluxgate.metrics.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(MicrometerMetricsRecorder.class);
            });
  }

  @Test
  void shouldCreateMicrometerMetricsRecorderWithMeterRegistry() {
    contextRunner.run(
        context -> {
          MicrometerMetricsRecorder recorder = context.getBean(MicrometerMetricsRecorder.class);
          assertThat(recorder).isNotNull();
        });
  }
}
