package org.fluxgate.control.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RedisRuleChangeNotifierTest {

  @Test
  void shouldRequireRedisUri() {
    assertThatThrownBy(() -> new RedisRuleChangeNotifier(null, "channel"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("redisUri");
  }

  @Test
  void shouldRequireChannel() {
    assertThatThrownBy(() -> new RedisRuleChangeNotifier("redis://localhost:6379", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("channel");
  }

  @Test
  void shouldRequireTimeout() {
    assertThatThrownBy(
            () -> new RedisRuleChangeNotifier("redis://localhost:6379", "channel", null, "source"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("timeout");
  }

  @Test
  void shouldRequireSource() {
    assertThatThrownBy(
            () ->
                new RedisRuleChangeNotifier(
                    "redis://localhost:6379", "channel", Duration.ofSeconds(5), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("source");
  }

  @Test
  void shouldDetectClusterMode() {
    // Cluster mode is detected by comma-separated URIs
    RedisRuleChangeNotifier notifier =
        new RedisRuleChangeNotifier(
            "redis://node1:6379,redis://node2:6379", "channel", Duration.ofSeconds(5), "source");

    // Should not throw - cluster mode detected
    assertThat(notifier).isNotNull();
    notifier.close();
  }

  @Test
  void shouldThrowWhenClosedAndNotify() {
    RedisRuleChangeNotifier notifier =
        new RedisRuleChangeNotifier("redis://localhost:6379", "channel");
    notifier.close();

    assertThatThrownBy(() -> notifier.notifyChange("rule-id"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
  }

  @Test
  void shouldThrowWhenClosedAndNotifyFullReload() {
    RedisRuleChangeNotifier notifier =
        new RedisRuleChangeNotifier("redis://localhost:6379", "channel");
    notifier.close();

    assertThatThrownBy(() -> notifier.notifyFullReload())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
  }

  @Test
  void shouldRequireRuleSetIdForNotifyChange() {
    RedisRuleChangeNotifier notifier =
        new RedisRuleChangeNotifier("redis://localhost:6379", "channel");

    try {
      assertThatThrownBy(() -> notifier.notifyChange(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("ruleSetId");
    } finally {
      notifier.close();
    }
  }

  @Test
  void shouldAllowMultipleClose() {
    RedisRuleChangeNotifier notifier =
        new RedisRuleChangeNotifier("redis://localhost:6379", "channel");

    // Should not throw
    notifier.close();
    notifier.close();
    notifier.close();
  }
}
