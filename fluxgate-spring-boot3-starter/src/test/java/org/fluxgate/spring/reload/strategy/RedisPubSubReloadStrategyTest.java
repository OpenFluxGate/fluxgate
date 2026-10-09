package org.fluxgate.spring.reload.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.fluxgate.core.reload.ReloadSource;
import org.fluxgate.core.reload.RuleReloadEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

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

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void shouldReconcileCachesOnEverySubscriptionAcknowledgement() {
    RedisClient client = mock(RedisClient.class);
    StatefulRedisPubSubConnection<String, String> connection =
        mock(StatefulRedisPubSubConnection.class);
    RedisPubSubCommands<String, String> commands = mock(RedisPubSubCommands.class);
    when(client.connectPubSub()).thenReturn(connection);
    when(connection.sync()).thenReturn(commands);
    RedisPubSubReloadStrategy subscribed =
        new RedisPubSubReloadStrategy(client, "fluxgate:rule-reload", false, Duration.ofSeconds(5));
    List<RuleReloadEvent> events = new ArrayList<>();
    subscribed.addListener(events::add);
    try {
      subscribed.start();
      ArgumentCaptor<RedisPubSubListener<String, String>> listener =
          ArgumentCaptor.forClass((Class) RedisPubSubListener.class);
      verify(connection).addListener(listener.capture());
      listener.getValue().subscribed("fluxgate:rule-reload", 1);
      listener.getValue().subscribed("fluxgate:rule-reload", 1);
      assertThat(events)
          .hasSize(2)
          .allSatisfy(
              event -> {
                assertThat(event.isFullReload()).isTrue();
                assertThat(event.getSource()).isEqualTo(ReloadSource.PUBSUB);
              });
    } finally {
      subscribed.stop();
    }
  }

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
}
