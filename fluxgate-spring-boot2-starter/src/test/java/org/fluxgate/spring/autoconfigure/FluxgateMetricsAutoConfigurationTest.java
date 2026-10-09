package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
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

  @Test
  void shouldRegisterTheEndpointTagLimitMeterFilter() {
    contextRunner.run(
        context -> assertThat(context).hasBean("fluxgateEndpointTagLimitMeterFilter"));
  }

  // ===== N14: max-endpoint-tags <= 0 means unbounded (no MeterFilter bean) =====

  @Test
  void shouldNotRegisterEndpointTagLimitMeterFilterWhenMaxEndpointTagsIsZero() {
    contextRunner
        .withPropertyValues("fluxgate.metrics.max-endpoint-tags=0")
        .run(context -> assertThat(context).doesNotHaveBean("fluxgateEndpointTagLimitMeterFilter"));
  }

  @Test
  void shouldNotRegisterEndpointTagLimitMeterFilterWhenMaxEndpointTagsIsNegative() {
    contextRunner
        .withPropertyValues("fluxgate.metrics.max-endpoint-tags=-1")
        .run(context -> assertThat(context).doesNotHaveBean("fluxgateEndpointTagLimitMeterFilter"));
  }

  @Test
  void shouldRegisterEndpointTagLimitMeterFilterWhenMaxEndpointTagsIsPositive() {
    contextRunner
        .withPropertyValues("fluxgate.metrics.max-endpoint-tags=5")
        .run(context -> assertThat(context).hasBean("fluxgateEndpointTagLimitMeterFilter"));
  }

  @Test
  void shouldCapTheNumberOfEndpointTagValues() {
    contextRunner
        .withPropertyValues("fluxgate.metrics.max-endpoint-tags=2")
        .run(
            context -> {
              MeterRegistry registry = context.getBean(MeterRegistry.class);
              registry.config().meterFilter(context.getBean(MeterFilter.class));
              MicrometerMetricsRecorder recorder = context.getBean(MicrometerMetricsRecorder.class);

              // Normalization is disabled for this assertion by using non-id segments.
              recorder.record(context("/a"), allowed());
              recorder.record(context("/b"), allowed());
              recorder.record(context("/c"), allowed());

              assertThat(registry.find("fluxgate.requests").counters()).hasSize(2);
            });
  }

  @Test
  void shouldHonourIncludeEndpointFalse() {
    contextRunner
        .withPropertyValues("fluxgate.metrics.include-endpoint=false")
        .run(
            context -> {
              MeterRegistry registry = context.getBean(MeterRegistry.class);
              MicrometerMetricsRecorder recorder = context.getBean(MicrometerMetricsRecorder.class);

              recorder.record(context("/a"), allowed());
              recorder.record(context("/b"), allowed());

              assertThat(registry.find("fluxgate.requests").counters()).hasSize(1);
            });
  }

  private static org.fluxgate.core.context.RequestContext context(String endpoint) {
    return org.fluxgate.core.context.RequestContext.builder()
        .endpoint(endpoint)
        .method("GET")
        .clientIp("127.0.0.1")
        .build();
  }

  private static org.fluxgate.core.ratelimiter.RateLimitResult allowed() {
    return org.fluxgate.core.ratelimiter.RateLimitResult.builder(
            org.fluxgate.core.key.RateLimitKey.of("ip:127.0.0.1"))
        .allowed(true)
        .remainingTokens(5)
        .build();
  }
}
