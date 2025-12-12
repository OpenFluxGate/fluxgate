package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.fluxgate.core.metrics.CompositeMetricsRecorder;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.spring.metrics.MicrometerMetricsRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Tests for {@link FluxgateMetricsCompositeAutoConfiguration}. */
class FluxgateMetricsCompositeAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(FluxgateMetricsCompositeAutoConfiguration.class));

  @Test
  void shouldReturnSingleRecorderWhenOnlyOneAvailable() {
    contextRunner
        .withUserConfiguration(SingleRecorderConfig.class)
        .run(
            context -> {
              // The compositeMetricsRecorder bean should return the single recorder directly
              RateLimitMetricsRecorder primary =
                  context.getBean("compositeMetricsRecorder", RateLimitMetricsRecorder.class);
              assertThat(primary).isInstanceOf(TestMetricsRecorder.class);
            });
  }

  @Test
  void shouldCreateCompositeWhenMultipleRecordersAvailable() {
    contextRunner
        .withUserConfiguration(MultipleRecordersConfig.class)
        .run(
            context -> {
              RateLimitMetricsRecorder primary =
                  context.getBean("compositeMetricsRecorder", RateLimitMetricsRecorder.class);
              assertThat(primary).isInstanceOf(CompositeMetricsRecorder.class);

              // Individual recorders should still be available
              assertThat(context.getBean("recorder1")).isInstanceOf(TestMetricsRecorder.class);
              assertThat(context.getBean("recorder2")).isInstanceOf(TestMetricsRecorder2.class);
            });
  }

  @Test
  void shouldNotCreateBeanWhenNoRecordersAvailable() {
    contextRunner.run(
        context -> {
          assertThat(context).doesNotHaveBean(RateLimitMetricsRecorder.class);
        });
  }

  @Configuration
  static class SingleRecorderConfig {
    @Bean
    RateLimitMetricsRecorder testRecorder() {
      return new TestMetricsRecorder();
    }
  }

  @Configuration
  static class MultipleRecordersConfig {
    @Bean
    RateLimitMetricsRecorder recorder1() {
      return new TestMetricsRecorder();
    }

    @Bean
    RateLimitMetricsRecorder recorder2() {
      return new TestMetricsRecorder2();
    }
  }

  static class TestMetricsRecorder implements RateLimitMetricsRecorder {
    @Override
    public void record(
        org.fluxgate.core.context.RequestContext context,
        org.fluxgate.core.ratelimiter.RateLimitResult result) {}
  }

  static class TestMetricsRecorder2 implements RateLimitMetricsRecorder {
    @Override
    public void record(
        org.fluxgate.core.context.RequestContext context,
        org.fluxgate.core.ratelimiter.RateLimitResult result) {}
  }
}
