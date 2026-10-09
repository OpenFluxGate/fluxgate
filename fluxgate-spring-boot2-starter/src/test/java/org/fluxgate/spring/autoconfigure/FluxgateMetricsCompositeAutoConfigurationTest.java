package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.metrics.CompositeMetricsRecorder;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
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
  void shouldWrapSingleRecorderWhenOnlyOneAvailable() {
    contextRunner
        .withUserConfiguration(SingleRecorderConfig.class)
        .run(
            context -> {
              // Single and multiple recorders use the same best-effort boundary.
              RateLimitMetricsRecorder primary =
                  context.getBean("compositeMetricsRecorder", RateLimitMetricsRecorder.class);
              assertThat(primary).isInstanceOf(CompositeMetricsRecorder.class);
              assertThat(((CompositeMetricsRecorder) primary).size()).isEqualTo(1);
            });
  }

  @Test
  void shouldKeepAllowAndQuotaDenyDecisionsWhenTheOnlyRecorderThrows() {
    AtomicInteger recorded = new AtomicInteger();
    RateLimitMetricsRecorder failing =
        (request, result) -> {
          recorded.incrementAndGet();
          throw new IllegalStateException("telemetry backend unavailable");
        };
    contextRunner
        .withBean("throwingRecorder", RateLimitMetricsRecorder.class, () -> failing)
        .run(
            context -> {
              RateLimitMetricsRecorder primary =
                  context.getBean("compositeMetricsRecorder", RateLimitMetricsRecorder.class);
              RateLimitRuleSet rules =
                  RateLimitRuleSet.builder("orders")
                      .rules(
                          List.of(
                              RateLimitRule.builder("orders-rule")
                                  .enabled(true)
                                  .addBand(RateLimitBand.builder(Duration.ofHours(1), 1).build())
                                  .build()))
                      .keyResolver((request, rule) -> RateLimitKey.of("ip:10.0.0.1"))
                      .metricsRecorder(primary)
                      .build();
              RateLimitEngine engine =
                  RateLimitEngine.builder()
                      .ruleSetProvider(id -> Optional.of(rules))
                      .rateLimiter(new Bucket4jRateLimiter())
                      .build();
              RequestContext request =
                  RequestContext.builder().clientIp("10.0.0.1").endpoint("/orders").build();
              assertThat(engine.check("orders", request).isAllowed()).isTrue();
              assertThat(engine.check("orders", request).isAllowed()).isFalse();
              assertThat(recorded).hasValue(2);
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
