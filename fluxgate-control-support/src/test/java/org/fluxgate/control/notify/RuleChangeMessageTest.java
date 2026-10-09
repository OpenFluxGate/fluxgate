package org.fluxgate.control.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RuleChangeMessageTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldCreateMessageForRuleSet() {
    RuleChangeMessage message = RuleChangeMessage.forRuleSet("test-rule", "test-source");

    assertThat(message.getRuleSetId()).isEqualTo("test-rule");
    assertThat(message.isFullReload()).isFalse();
    assertThat(message.getSource()).isEqualTo("test-source");
    assertThat(message.getTimestamp()).isPositive();
  }

  @Test
  void shouldCreateFullReloadMessage() {
    RuleChangeMessage message = RuleChangeMessage.fullReload("test-source");

    assertThat(message.getRuleSetId()).isNull();
    assertThat(message.isFullReload()).isTrue();
    assertThat(message.getSource()).isEqualTo("test-source");
  }

  @Test
  void shouldThrowOnNullRuleSetId() {
    assertThatThrownBy(() -> RuleChangeMessage.forRuleSet(null, "source"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void shouldSerializeAndDeserialize() throws Exception {
    RuleChangeMessage original = RuleChangeMessage.forRuleSet("my-rule", "studio");

    String json = objectMapper.writeValueAsString(original);
    RuleChangeMessage deserialized = objectMapper.readValue(json, RuleChangeMessage.class);

    assertThat(deserialized.getRuleSetId()).isEqualTo(original.getRuleSetId());
    assertThat(deserialized.isFullReload()).isEqualTo(original.isFullReload());
    assertThat(deserialized.getSource()).isEqualTo(original.getSource());
    assertThat(deserialized.getTimestamp()).isEqualTo(original.getTimestamp());
  }

  @Test
  void shouldSerializeFullReloadMessage() throws Exception {
    RuleChangeMessage original = RuleChangeMessage.fullReload("admin");

    String json = objectMapper.writeValueAsString(original);
    RuleChangeMessage deserialized = objectMapper.readValue(json, RuleChangeMessage.class);

    assertThat(deserialized.getRuleSetId()).isNull();
    assertThat(deserialized.isFullReload()).isTrue();
    assertThat(deserialized.getSource()).isEqualTo("admin");
  }

  @Test
  void shouldHaveToStringForRuleSet() {
    RuleChangeMessage message = RuleChangeMessage.forRuleSet("test-rule", "source");

    String str = message.toString();

    assertThat(str).contains("test-rule");
    assertThat(str).contains("source");
  }

  @Test
  void shouldHaveToStringForFullReload() {
    RuleChangeMessage message = RuleChangeMessage.fullReload("source");

    String str = message.toString();

    assertThat(str).contains("fullReload=true");
    assertThat(str).contains("source");
  }
}
