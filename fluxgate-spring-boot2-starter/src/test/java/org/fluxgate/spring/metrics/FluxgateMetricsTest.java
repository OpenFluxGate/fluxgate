package org.fluxgate.spring.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FluxgateMetrics}. */
class FluxgateMetricsTest {

  private MeterRegistry registry;
  private FluxgateMetrics metrics;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    metrics = new FluxgateMetrics(registry);
  }

  @Test
  void shouldRecordAllowedRequest() {
    // when
    metrics.recordRequest("test-rule", "/api/test", true, Duration.ofMillis(100));

    // then
    Counter totalCounter = registry.find("fluxgate.requests.total").counter();
    assertThat(totalCounter).isNotNull();
    assertThat(totalCounter.count()).isEqualTo(1.0);

    Counter allowedCounter = registry.find("fluxgate.requests").tag("result", "allowed").counter();
    assertThat(allowedCounter).isNotNull();
    assertThat(allowedCounter.count()).isEqualTo(1.0);

    Timer timer = registry.find("fluxgate.requests.duration").timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1);
  }

  @Test
  void shouldRecordRejectedRequest() {
    // when
    metrics.recordRequest("test-rule", "/api/test", false, Duration.ofMillis(50));

    // then
    Counter rejectedCounter =
        registry.find("fluxgate.requests").tag("result", "rejected").counter();
    assertThat(rejectedCounter).isNotNull();
    assertThat(rejectedCounter.count()).isEqualTo(1.0);
  }

  @Test
  void shouldRecordAllowedShortcut() {
    // when
    metrics.recordAllowed("rule1", "/endpoint");

    // then
    Counter allowedCounter = registry.find("fluxgate.requests").tag("result", "allowed").counter();
    assertThat(allowedCounter).isNotNull();
    assertThat(allowedCounter.count()).isEqualTo(1.0);
  }

  @Test
  void shouldRecordRejectedShortcut() {
    // when
    metrics.recordRejected("rule1", "/endpoint");

    // then
    Counter rejectedCounter =
        registry.find("fluxgate.requests").tag("result", "rejected").counter();
    assertThat(rejectedCounter).isNotNull();
    assertThat(rejectedCounter.count()).isEqualTo(1.0);
  }

  @Test
  void shouldRecordRemainingTokens() {
    // when
    metrics.recordRemainingTokens("rule1", 50);

    // then - gauge is registered
    assertThat(registry.find("fluxgate.tokens.remaining").gauge()).isNotNull();
  }

  @Test
  void shouldReuseCountersForSameKey() {
    // when
    metrics.recordAllowed("rule1", "/api/test");
    metrics.recordAllowed("rule1", "/api/test");
    metrics.recordAllowed("rule1", "/api/test");

    // then - should be single counter with count 3
    Counter counter = registry.find("fluxgate.requests").tag("result", "allowed").counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(3.0);
  }

  @Test
  void shouldCreateSeparateCountersForDifferentRuleSets() {
    // when
    metrics.recordAllowed("rule1", "/api");
    metrics.recordAllowed("rule2", "/api");

    // then
    Counter counter1 = registry.find("fluxgate.requests").tag("rule_set", "rule1").counter();
    Counter counter2 = registry.find("fluxgate.requests").tag("rule_set", "rule2").counter();

    assertThat(counter1.count()).isEqualTo(1.0);
    assertThat(counter2.count()).isEqualTo(1.0);
  }

  @Test
  void shouldSanitizeSpecialCharacters() {
    // when
    metrics.recordAllowed("rule:with:colons", "/api/test?param=value");

    // then - should not throw, special chars are sanitized
    Counter counter = registry.find("fluxgate.requests.total").counter();
    assertThat(counter).isNotNull();
  }

  @Test
  void shouldHandleNullEndpoint() {
    // when
    metrics.recordAllowed("rule1", null);

    // then
    Counter counter = registry.find("fluxgate.requests.total").counter();
    assertThat(counter).isNotNull();
  }

  @Test
  void shouldHandleEmptyEndpoint() {
    // when
    metrics.recordAllowed("rule1", "");

    // then
    Counter counter = registry.find("fluxgate.requests.total").counter();
    assertThat(counter).isNotNull();
  }

  @Test
  void shouldHandleNullRuleSetId() {
    // when
    metrics.recordAllowed(null, "/api");

    // then - should use "unknown" as fallback
    Counter counter = registry.find("fluxgate.requests").tag("rule_set", "unknown").counter();
    assertThat(counter).isNotNull();
  }
}
