package org.fluxgate.redis.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link RuleSetData}. */
class RuleSetDataTest {

  @Test
  void shouldCreateWithDefaultConstructor() {
    RuleSetData data = new RuleSetData();

    assertThat(data.getRuleSetId()).isNull();
    assertThat(data.getCapacity()).isZero();
    assertThat(data.getWindowSeconds()).isZero();
    assertThat(data.getKeyStrategyId()).isNull();
    assertThat(data.getCreatedAt()).isZero();
  }

  @Test
  void shouldCreateWithParameterizedConstructor() {
    RuleSetData data = new RuleSetData("test-rule", 100, 60, "clientIp");

    assertThat(data.getRuleSetId()).isEqualTo("test-rule");
    assertThat(data.getCapacity()).isEqualTo(100);
    assertThat(data.getWindowSeconds()).isEqualTo(60);
    assertThat(data.getKeyStrategyId()).isEqualTo("clientIp");
    assertThat(data.getCreatedAt()).isGreaterThan(0);
  }

  @Test
  void shouldSetAndGetRuleSetId() {
    RuleSetData data = new RuleSetData();
    data.setRuleSetId("new-rule");

    assertThat(data.getRuleSetId()).isEqualTo("new-rule");
  }

  @Test
  void shouldSetAndGetCapacity() {
    RuleSetData data = new RuleSetData();
    data.setCapacity(500);

    assertThat(data.getCapacity()).isEqualTo(500);
  }

  @Test
  void shouldSetAndGetWindowSeconds() {
    RuleSetData data = new RuleSetData();
    data.setWindowSeconds(120);

    assertThat(data.getWindowSeconds()).isEqualTo(120);
  }

  @Test
  void shouldSetAndGetKeyStrategyId() {
    RuleSetData data = new RuleSetData();
    data.setKeyStrategyId("userId");

    assertThat(data.getKeyStrategyId()).isEqualTo("userId");
  }

  @Test
  void shouldSetAndGetCreatedAt() {
    RuleSetData data = new RuleSetData();
    long timestamp = System.currentTimeMillis();
    data.setCreatedAt(timestamp);

    assertThat(data.getCreatedAt()).isEqualTo(timestamp);
  }

  @Test
  void shouldReturnFormattedToString() {
    RuleSetData data = new RuleSetData("api-limit", 1000, 3600, "apiKey");
    data.setCreatedAt(1234567890L);

    String str = data.toString();

    assertThat(str).contains("ruleSetId='api-limit'");
    assertThat(str).contains("capacity=1000");
    assertThat(str).contains("windowSeconds=3600");
    assertThat(str).contains("keyStrategyId='apiKey'");
    assertThat(str).contains("createdAt=1234567890");
  }
}
