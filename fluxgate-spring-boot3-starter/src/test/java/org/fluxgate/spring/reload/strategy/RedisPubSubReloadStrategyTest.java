package org.fluxgate.spring.reload.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.fluxgate.core.reload.ReloadSource;
import org.fluxgate.core.reload.RuleReloadEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Message parsing tests for {@link RedisPubSubReloadStrategy}.
 *
 * <p>The channel is unauthenticated and, on a shared Redis, reachable by unrelated publishers. A
 * message that cannot be understood used to be treated as a full reload, which deleted every
 * bucket; these tests pin it to "log and ignore".
 */
class RedisPubSubReloadStrategyTest {

  private final RedisPubSubReloadStrategy strategy =
      new RedisPubSubReloadStrategy(
          "redis://localhost:6379",
          "fluxgate:rule-reload",
          false,
          Duration.ofSeconds(5),
          Duration.ofSeconds(5));

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "   ",
        "{",
        "{\"ruleSetId\":}",
        "[\"orders\"]",
        "{}",
        "{\"ruleSetId\":\"\"}",
        "{\"ruleSetId\":null}",
        "{\"fullReload\":false}",
        "{\"version\":2,\"fullReload\":true}",
        "{\"version\":99,\"ruleSetId\":\"orders\"}",
        "{\"unrelated\":\"payload from another app\"}"
      })
  void shouldIgnoreMessagesItCannotUnderstand(String message) {
    assertThat(strategy.parseMessage(message)).isEmpty();
  }

  @Test
  void shouldIgnoreANullMessage() {
    assertThat(strategy.parseMessage(null)).isEmpty();
  }

  @Test
  void shouldTreatTheStarLiteralAsAFullReload() {
    Optional<RuleReloadEvent> event = strategy.parseMessage("*");

    assertThat(event).isPresent();
    assertThat(event.get().isFullReload()).isTrue();
    assertThat(event.get().getSource()).isEqualTo(ReloadSource.PUBSUB);
  }

  @Test
  void shouldTreatAnExplicitJsonFullReloadAsAFullReload() {
    Optional<RuleReloadEvent> event = strategy.parseMessage("{\"fullReload\":true}");

    assertThat(event).isPresent();
    assertThat(event.get().isFullReload()).isTrue();
  }

  @Test
  void shouldAcceptAJsonMessageCarryingTheKnownSchemaVersion() {
    Optional<RuleReloadEvent> event =
        strategy.parseMessage("{\"version\":1,\"ruleSetId\":\"orders\",\"fullReload\":false}");

    assertThat(event).isPresent();
    assertThat(event.get().isFullReload()).isFalse();
    assertThat(event.get().getRuleSetId()).isEqualTo("orders");
  }

  @Test
  void shouldAcceptAJsonMessageWithoutAVersionForBackwardCompatibility() {
    Optional<RuleReloadEvent> event = strategy.parseMessage("{\"ruleSetId\":\"orders\"}");

    assertThat(event).isPresent();
    assertThat(event.get().getRuleSetId()).isEqualTo("orders");
  }

  @Test
  void shouldAcceptAPlainRuleSetId() {
    Optional<RuleReloadEvent> event = strategy.parseMessage(" orders ");

    assertThat(event).isPresent();
    assertThat(event.get().getRuleSetId()).isEqualTo("orders");
    assertThat(event.get().isFullReload()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "redis://:pa,ss@localhost:6379",
        "redis://user:p,w@localhost:6379/1",
      })
  void aCommaInTheCredentialsIsNotACluster(String uri) throws Exception {
    assertThat(isCluster(uri)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "redis://n1:6379,redis://n2:6379",
        "redis://:secret@n1:6379,redis://:secret@n2:6379",
      })
  void severalNodesAreACluster(String uri) throws Exception {
    assertThat(isCluster(uri)).isTrue();
  }

  private static boolean isCluster(String uri) throws Exception {
    RedisPubSubReloadStrategy s =
        new RedisPubSubReloadStrategy(
            uri, "fluxgate:rule-reload", false, Duration.ofSeconds(5), Duration.ofSeconds(5));
    java.lang.reflect.Field field = RedisPubSubReloadStrategy.class.getDeclaredField("isCluster");
    field.setAccessible(true);
    return field.getBoolean(s);
  }
}
