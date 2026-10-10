package org.fluxgate.spring.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MicrometerMetricsRecorder}. */
class MicrometerMetricsRecorderTest {

  private SimpleMeterRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
  }

  @Test
  void shouldCountRequestsByResult() {
    MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

    recorder.record(context("/api/users", "GET"), allowed(5));
    recorder.record(context("/api/users", "GET"), allowed(4));
    recorder.record(context("/api/users", "GET"), rejected());

    assertThat(counter("allowed")).isEqualTo(2.0);
    assertThat(counter("rejected")).isEqualTo(1.0);
  }

  @Test
  void shouldNotRegisterACollidingTotalCounter() {
    // fluxgate.requests.total and fluxgate.requests both export as fluxgate_requests_total under
    // Prometheus naming, with different label sets.
    new MicrometerMetricsRecorder(registry).record(context("/api/users", "GET"), allowed(5));

    assertThat(registry.find("fluxgate.requests.total").counter()).isNull();
  }

  @Test
  void shouldKeepTheRemainingTokensGaugeReadableAfterGc() {
    MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

    recorder.record(context("/api/users", "GET"), allowed(7));
    System.gc();

    Gauge gauge = registry.find("fluxgate.tokens.remaining").gauge();
    assertThat(gauge).isNotNull();
    assertThat(gauge.value()).isEqualTo(7.0);
  }

  @Test
  void shouldUpdateTheExistingGaugeRatherThanRegisterANewOne() {
    MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

    recorder.record(context("/api/users", "GET"), allowed(7));
    recorder.record(context("/api/users", "GET"), allowed(3));

    assertThat(registry.find("fluxgate.tokens.remaining").gauges()).hasSize(1);
    assertThat(registry.find("fluxgate.tokens.remaining").gauge().value()).isEqualTo(3.0);
  }

  @Test
  void shouldNotReportAnUnknownRemainingCountAsANumber() {
    MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

    recorder.record(context("/api/users", "GET"), allowed(-1));

    assertThat(registry.find("fluxgate.tokens.remaining").gauge()).isNull();
  }

  @Test
  void shouldNormalizeHighCardinalityPathSegments() {
    MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

    assertThat(recorder.normalizeEndpoint("/api/users/12345/orders"))
        .isEqualTo("/api/users/{id}/orders");
    assertThat(recorder.normalizeEndpoint("/api/jobs/3f2504e0-4f89-11d3-9a0c-0305e82c3301"))
        .isEqualTo("/api/jobs/{id}");
    assertThat(recorder.normalizeEndpoint("/api/docs/507f1f77bcf86cd799439011"))
        .isEqualTo("/api/docs/{id}");
    assertThat(recorder.normalizeEndpoint("/api/users/profile")).isEqualTo("/api/users/profile");
  }

  @Test
  void shouldCollapseDistinctIdsIntoOneMeter() {
    MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

    recorder.record(context("/api/users/1", "GET"), allowed(5));
    recorder.record(context("/api/users/2", "GET"), allowed(5));
    recorder.record(context("/api/users/3", "GET"), allowed(5));

    assertThat(registry.find("fluxgate.requests").counters()).hasSize(1);
    assertThat(registry.find("fluxgate.requests").counter().getId().getTag("endpoint"))
        .isEqualTo("/api/users/{id}");
  }

  @Test
  void shouldKeepRawEndpointsWhenNormalizationIsDisabled() {
    MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry, true, false, 1000);

    recorder.record(context("/api/users/1", "GET"), allowed(5));
    recorder.record(context("/api/users/2", "GET"), allowed(5));

    assertThat(registry.find("fluxgate.requests").counters()).hasSize(2);
  }

  @Test
  void shouldOmitTheEndpointTagWhenDisabled() {
    MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry, false, true, 1000);

    recorder.record(context("/api/users/1", "GET"), allowed(5));
    recorder.record(context("/api/orders/2", "GET"), allowed(5));

    assertThat(registry.find("fluxgate.requests").counters()).hasSize(1);
    assertThat(registry.find("fluxgate.requests").counter().getId().getTag("endpoint")).isNull();
  }

  @Test
  void shouldBoundTheNumberOfGaugeSeries() {
    MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry, true, false, 2);

    recorder.record(context("/a", "GET"), allowed(1));
    recorder.record(context("/b", "GET"), allowed(2));
    recorder.record(context("/c", "GET"), allowed(3));

    assertThat(registry.find("fluxgate.tokens.remaining").gauges()).hasSize(2);
  }

  @Test
  void shouldCollapseEndpointsBeyondTheTagCapIntoOneCounterSeries() {
    // N-13: maxEndpointTags used to bound the gauges only, so a caller inventing non-numeric paths
    // registered one counter per path and grew the registry without limit.
    MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry, true, true, 2);

    recorder.record(context("/api/aaa", "GET"), allowed(5));
    recorder.record(context("/api/aab", "GET"), allowed(5));
    recorder.record(context("/api/aac", "GET"), allowed(5));
    recorder.record(context("/api/aad", "GET"), allowed(5));

    assertThat(registry.find("fluxgate.requests").counters()).hasSize(3);
    assertThat(registry.find("fluxgate.requests").tag("endpoint", "other").counter().count())
        .isEqualTo(2.0);
  }

  @Test
  void shouldCollapseEndpointsBeyondTheTagCapIntoOneTimerSeries() {
    // N-13: the duration timer was unbounded as well.
    MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry, true, true, 1);

    recorder.recordDuration("api-rules", "/api/aaa", "GET", Duration.ofMillis(1));
    recorder.recordDuration("api-rules", "/api/aab", "GET", Duration.ofMillis(1));
    recorder.recordDuration("api-rules", "/api/aac", "GET", Duration.ofMillis(1));

    assertThat(registry.find("fluxgate.requests.duration").timers()).hasSize(2);
    assertThat(registry.find("fluxgate.requests.duration").tag("endpoint", "other").timer().count())
        .isEqualTo(2);
  }

  @Test
  void shouldShareTheEndpointTagBudgetBetweenCountersAndTimers() {
    // One invented path costs one series in total, not one per meter name.
    MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry, true, true, 1);

    recorder.record(context("/api/aaa", "GET"), allowed(5));
    recorder.recordDuration("api-rules", "/api/aab", "GET", Duration.ofMillis(1));

    assertThat(registry.find("fluxgate.requests").counter().getId().getTag("endpoint"))
        .isEqualTo("/api/aaa");
    assertThat(registry.find("fluxgate.requests.duration").timer().getId().getTag("endpoint"))
        .isEqualTo("other");
  }

  @Test
  void shouldRecordTheRequestDurationTimer() {
    MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

    recorder.recordDuration("api-rules", "/api/users", "GET", Duration.ofMillis(12));

    assertThat(registry.find("fluxgate.requests.duration").timer()).isNotNull();
    assertThat(registry.find("fluxgate.requests.duration").timer().count()).isEqualTo(1);
  }

  private double counter(String result) {
    return registry.find("fluxgate.requests").tag("result", result).counter().count();
  }

  private static RequestContext context(String endpoint, String method) {
    return RequestContext.builder().endpoint(endpoint).method(method).clientIp("127.0.0.1").build();
  }

  private static RateLimitResult allowed(long remainingTokens) {
    return RateLimitResult.builder(RateLimitKey.of("ip:127.0.0.1"))
        .allowed(true)
        .remainingTokens(remainingTokens)
        .build();
  }

  private static RateLimitResult rejected() {
    return RateLimitResult.builder(RateLimitKey.of("ip:127.0.0.1"))
        .allowed(false)
        .remainingTokens(0)
        .nanosToWaitForRefill(1_000_000L)
        .build();
  }
}
