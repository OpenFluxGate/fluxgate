package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.handler.ResilientRateLimiter;
import org.fluxgate.spring.metrics.FluxgateMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Item 7: FluxGate meters against a real {@link SimpleMeterRegistry} created by Boot's own metrics
 * auto-configuration, with all auto-configurations in their natural order.
 */
class FluxgateMetricsWiringTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  MetricsAutoConfiguration.class,
                  SimpleMetricsExportAutoConfiguration.class,
                  CompositeMeterRegistryAutoConfiguration.class,
                  FluxgateResilienceAutoConfiguration.class,
                  FluxgateRateLimiterAutoConfiguration.class,
                  FluxgateMetricsAutoConfiguration.class,
                  FluxgateMetricsCompositeAutoConfiguration.class));

  private static RequestContext request(String endpoint) {
    return RequestContext.builder().endpoint(endpoint).method("GET").clientIp("127.0.0.1").build();
  }

  @Test
  void registersFluxgateMetricsAndTheFailureRecorder() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(FluxgateMetrics.class);
          assertThat(context).hasSingleBean(ResilientRateLimiter.FailureRecorder.class);
        });
  }

  @Test
  void emitsTheLimiterFailureCounterWhenTheLimiterFails() {
    RateLimiter broken = mock(RateLimiter.class);
    when(broken.tryConsume(any(), any(), anyLong()))
        .thenThrow(new IllegalStateException("redis down"));
    when(broken.tryConsume(any(), any(), anyLong(), any()))
        .thenThrow(new IllegalStateException("redis down"));

    runner
        .withBean(
            FluxgateRateLimiterAutoConfiguration.DELEGATE_RATE_LIMITER_BEAN_NAME,
            RateLimiter.class,
            () -> broken)
        .withPropertyValues(
            "fluxgate.resilience.retry.enabled=false", "fluxgate.ratelimit.failure-behavior=ALLOW")
        .run(
            context -> {
              ResilientRateLimiter limiter = context.getBean(ResilientRateLimiter.class);
              RateLimitResult result = limiter.tryConsume(request("/a"), null, 1L);
              assertThat(result.isAllowed()).isTrue();

              MeterRegistry registry = context.getBean(MeterRegistry.class);
              assertThat(
                      registry
                          .find("fluxgate.limiter.failures")
                          .tag("action", "fail_open")
                          .counter())
                  .isNotNull()
                  .satisfies(counter -> assertThat(counter.count()).isEqualTo(1.0));
            });
  }

  @Test
  void registersTheBucketEvictionCounterForTheInMemoryLimiter() {
    runner
        .withPropertyValues("fluxgate.ratelimit.mode=IN_MEMORY")
        .run(
            context -> {
              MeterRegistry registry = context.getBean(MeterRegistry.class);
              assertThat(registry.find("fluxgate.limiter.bucket_evictions").functionCounter())
                  .isNotNull();
            });
  }

  @Test
  void endpointTagCapLeavesRoomForOtherAndUnknown() {
    runner
        .withPropertyValues("fluxgate.metrics.max-endpoint-tags=2")
        .run(
            context -> {
              MeterRegistry registry = context.getBean(MeterRegistry.class);
              for (String endpoint : new String[] {"/a", "/b", "other", "unknown", "/c"}) {
                registry
                    .counter("fluxgate.requests", Tags.of("endpoint", endpoint, "result", "x"))
                    .increment();
              }

              assertThat(registry.find("fluxgate.requests").counters())
                  .extracting(counter -> counter.getId().getTag("endpoint"))
                  .containsExactlyInAnyOrder("/a", "/b", "other", "unknown");
            });
  }

  @Test
  void recorderOverflowStillReachesTheRegistryAsOther() {
    runner
        .withPropertyValues("fluxgate.metrics.max-endpoint-tags=2")
        .run(
            context -> {
              MeterRegistry registry = context.getBean(MeterRegistry.class);
              RateLimitMetricsRecorder recorder =
                  context.getBean("micrometerMetricsRecorder", RateLimitMetricsRecorder.class);
              ((org.fluxgate.spring.metrics.MicrometerMetricsRecorder) recorder)
                  .recordDuration("rs", "/a", "GET", Duration.ofMillis(1));
              ((org.fluxgate.spring.metrics.MicrometerMetricsRecorder) recorder)
                  .recordDuration("rs", "/b", "GET", Duration.ofMillis(1));
              ((org.fluxgate.spring.metrics.MicrometerMetricsRecorder) recorder)
                  .recordDuration("rs", "/c", "GET", Duration.ofMillis(1));

              assertThat(registry.find("fluxgate.requests.duration").timers())
                  .extracting(timer -> timer.getId().getTag("endpoint"))
                  .containsExactlyInAnyOrder("/a", "/b", "other");
            });
  }

  @Test
  void aSingleRecorderIsDestroyedOnce() {
    AtomicInteger closed = new AtomicInteger();
    ConfigurableApplicationContext[] holder = new ConfigurableApplicationContext[1];
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FluxgateMetricsCompositeAutoConfiguration.class))
        .withBean("onlyRecorder", ClosingRecorder.class, () -> new ClosingRecorder(closed))
        .run(context -> holder[0] = context);

    assertThat(holder[0].isActive()).isFalse();
    assertThat(closed).hasValue(1);
  }

  @Test
  void yamlRuleSetsGetTheMetricsRecorder() {
    runner
        .withPropertyValues(
            "fluxgate.ratelimit.mode=IN_MEMORY",
            "fluxgate.ratelimit.rule-sets[0].id=yaml",
            "fluxgate.ratelimit.rule-sets[0].rules[0].id=r1",
            "fluxgate.ratelimit.rule-sets[0].rules[0].bands[0].capacity=10",
            "fluxgate.ratelimit.rule-sets[0].rules[0].bands[0].window=1m")
        .run(
            context -> {
              RateLimitRuleSet ruleSet =
                  context.getBean(RateLimitRuleSetProvider.class).findById("yaml").orElseThrow();
              assertThat(ruleSet.getMetricsRecorder()).isNotNull();
            });
  }

  /** A recorder with a close method Spring infers as its destroy method. */
  static class ClosingRecorder implements RateLimitMetricsRecorder, AutoCloseable {

    private final AtomicInteger closed;

    ClosingRecorder(AtomicInteger closed) {
      this.closed = closed;
    }

    @Override
    public void record(RequestContext context, RateLimitResult result) {}

    @Override
    public void close() {
      closed.incrementAndGet();
    }
  }
}
