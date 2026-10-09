package org.fluxgate.control.notify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RuleChangeNotifierMetrics}.
 *
 * <p>{@code getFailedNotifications()} is the counter an operator alerts on, so the three outcomes
 * have to stay independent: a notification that succeeded on its second attempt was retried, not
 * lost.
 */
class RuleChangeNotifierMetricsTest {

  @Test
  void shouldStartAtZero() {
    RuleChangeNotifierMetrics metrics = new RuleChangeNotifierMetrics();

    assertThat(metrics.getPublishedNotifications()).isZero();
    assertThat(metrics.getRetriedNotifications()).isZero();
    assertThat(metrics.getFailedNotifications()).isZero();
  }

  @Test
  @DisplayName("Each outcome increments only its own counter")
  void shouldCountEachOutcomeSeparately() {
    RuleChangeNotifierMetrics metrics = new RuleChangeNotifierMetrics();

    metrics.recordPublished();
    metrics.recordPublished();
    metrics.recordRetry();
    metrics.recordFailed();

    assertThat(metrics.getPublishedNotifications()).isEqualTo(2);
    assertThat(metrics.getRetriedNotifications()).isEqualTo(1);
    assertThat(metrics.getFailedNotifications()).isEqualTo(1);
  }

  @Test
  @DisplayName("toString carries every counter, so one log line is enough to triage")
  void shouldRenderEveryCounter() {
    RuleChangeNotifierMetrics metrics = new RuleChangeNotifierMetrics();
    metrics.recordPublished();
    metrics.recordRetry();
    metrics.recordRetry();
    metrics.recordFailed();

    assertThat(metrics.toString())
        .isEqualTo("RuleChangeNotifierMetrics{published=1, retried=2, failed=1}");
  }
}
