package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.fluxgate.spring.handler.ResilientRateLimiter;
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

              // /c overflows the budget and is recorded under "other", which the filter lets
              // through: two real endpoints plus the overflow series.
              assertThat(registry.find("fluxgate.requests").counters())
                  .extracting(counter -> counter.getId().getTag("endpoint"))
                  .containsExactlyInAnyOrder("/a", "/b", "other");
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

  // ===== fluxgate.limiter.failures shares the recorder's endpoint tag policy =====

  private static final IllegalStateException REDIS_DOWN = new IllegalStateException("down");

  @Test
  void limiterFailuresAreCountedUnderOtherOnceTheEndpointBudgetIsFull() {
    contextRunner
        .withPropertyValues("fluxgate.metrics.max-endpoint-tags=2")
        .run(
            context -> {
              MeterRegistry registry = context.getBean(MeterRegistry.class);
              registry.config().meterFilter(context.getBean(MeterFilter.class));
              MicrometerMetricsRecorder recorder = context.getBean(MicrometerMetricsRecorder.class);
              ResilientRateLimiter.FailureRecorder failures =
                  context.getBean(ResilientRateLimiter.FailureRecorder.class);

              // the request meters spend the whole budget
              recorder.record(context("/a"), allowed());
              recorder.record(context("/b"), allowed());

              failures.recordLimiterFailure("rules", "/fresh/path", "fail_open", REDIS_DOWN);
              failures.recordLimiterFailure("rules", "/another/path", "fail_open", REDIS_DOWN);

              // neither denied by the meter filter nor given a third endpoint value
              Counter counter = registry.find("fluxgate.limiter.failures").counter();
              assertThat(counter).isNotNull();
              assertThat(counter.getId().getTag("endpoint")).isEqualTo("other");
              assertThat(counter.count()).isEqualTo(2.0);
              assertThat(registry.find("fluxgate.limiter.failures").counters()).hasSize(1);
            });
  }

  @Test
  void limiterFailuresUseTheRecordersEndpointValues() {
    contextRunner
        .withPropertyValues("fluxgate.metrics.max-endpoint-tags=2")
        .run(
            context -> {
              MeterRegistry registry = context.getBean(MeterRegistry.class);
              registry.config().meterFilter(context.getBean(MeterFilter.class));
              MicrometerMetricsRecorder recorder = context.getBean(MicrometerMetricsRecorder.class);

              recorder.record(context("/a"), allowed());
              context
                  .getBean(ResilientRateLimiter.FailureRecorder.class)
                  .recordLimiterFailure("rules", "/a", "fail_closed", REDIS_DOWN);

              assertThat(
                      registry
                          .find("fluxgate.limiter.failures")
                          .counter()
                          .getId()
                          .getTag("endpoint"))
                  .isEqualTo("/a");
            });
  }

  @Test
  void limiterFailureEndpointsAreNormalized() {
    contextRunner.run(
        context -> {
          MeterRegistry registry = context.getBean(MeterRegistry.class);
          context
              .getBean(ResilientRateLimiter.FailureRecorder.class)
              .recordLimiterFailure("rules", "/api/users/12345/orders", "fail_open", REDIS_DOWN);

          assertThat(
                  registry.find("fluxgate.limiter.failures").counter().getId().getTag("endpoint"))
              .isEqualTo("/api/users/{id}/orders");
        });
  }

  @Test
  void limiterFailuresHonourIncludeEndpointFalse() {
    contextRunner
        .withPropertyValues("fluxgate.metrics.include-endpoint=false")
        .run(
            context -> {
              MeterRegistry registry = context.getBean(MeterRegistry.class);
              ResilientRateLimiter.FailureRecorder failures =
                  context.getBean(ResilientRateLimiter.FailureRecorder.class);

              failures.recordLimiterFailure("rules", "/a", "fail_open", REDIS_DOWN);
              failures.recordLimiterFailure("rules", "/b", "fail_open", REDIS_DOWN);

              Counter counter = registry.find("fluxgate.limiter.failures").counter();
              assertThat(counter.getId().getTag("endpoint")).isNull();
              assertThat(counter.count()).isEqualTo(2.0);
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
