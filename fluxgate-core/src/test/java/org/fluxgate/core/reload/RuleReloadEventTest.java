package org.fluxgate.core.reload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleReloadEventTest {

  @Test
  void shouldCreateEventForSpecificRuleSet() {
    RuleReloadEvent event = RuleReloadEvent.forRuleSet("my-rule-set", ReloadSource.PUBSUB);

    assertThat(event.getRuleSetId()).isEqualTo("my-rule-set");
    assertThat(event.getSource()).isEqualTo(ReloadSource.PUBSUB);
    assertThat(event.isFullReload()).isFalse();
    assertThat(event.getTimestamp()).isNotNull();
    assertThat(event.getMetadata()).isEmpty();
  }

  @Test
  void shouldCreateFullReloadEvent() {
    RuleReloadEvent event = RuleReloadEvent.fullReload(ReloadSource.POLLING);

    assertThat(event.getRuleSetId()).isNull();
    assertThat(event.getSource()).isEqualTo(ReloadSource.POLLING);
    assertThat(event.isFullReload()).isTrue();
  }

  @Test
  void shouldBuildEventWithAllFields() {
    Instant timestamp = Instant.now();
    Map<String, Object> metadata = Map.of("key", "value");

    RuleReloadEvent event =
        RuleReloadEvent.builder()
            .ruleSetId("test-rule")
            .source(ReloadSource.MANUAL)
            .timestamp(timestamp)
            .metadata(metadata)
            .build();

    assertThat(event.getRuleSetId()).isEqualTo("test-rule");
    assertThat(event.getSource()).isEqualTo(ReloadSource.MANUAL);
    assertThat(event.getTimestamp()).isEqualTo(timestamp);
    assertThat(event.getMetadata()).containsEntry("key", "value");
  }

  @Test
  void shouldRequireSource() {
    assertThatThrownBy(() -> RuleReloadEvent.builder().build())
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void shouldHaveToString() {
    RuleReloadEvent event = RuleReloadEvent.forRuleSet("test", ReloadSource.API);

    String str = event.toString();

    assertThat(str).contains("test");
    assertThat(str).contains("API");
  }

  @Test
  void shouldHaveToStringForFullReload() {
    RuleReloadEvent event = RuleReloadEvent.fullReload(ReloadSource.STARTUP);

    String str = event.toString();

    assertThat(str).contains("ALL");
    assertThat(str).contains("STARTUP");
  }
}
