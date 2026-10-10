package org.fluxgate.spring.reload.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.Optional;
import org.fluxgate.control.notify.RuleChangeMessage;
import org.fluxgate.core.reload.RuleReloadEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract between the publisher (fluxgate-control-support) and the verifier in {@link
 * RedisPubSubReloadStrategy}.
 *
 * <p>The publisher signs with {@link RuleChangeMessage#signed(String, String)} and serialises with
 * a plain {@link ObjectMapper}, exactly like {@code RedisRuleChangeNotifier}. The strategy must
 * accept that string untouched and reject every tampered, replayed or foreign variant of it. The
 * canonical form is deliberately not re-implemented here: a drift in either module breaks this
 * test.
 */
class RuleChangeMessageContractTest {

  private static final String SECRET = "contract-test-secret";
  private static final String CHANNEL = "fluxgate:rule-reload";

  private final ObjectMapper mapper = new ObjectMapper();

  private static RedisPubSubReloadStrategy verifier(String secret, String channel) {
    return new RedisPubSubReloadStrategy(
        "redis://localhost:6379",
        channel,
        false,
        Duration.ofSeconds(5),
        Duration.ofSeconds(5),
        secret,
        Duration.ofSeconds(60));
  }

  private String published(RuleChangeMessage message, String secret, String channel)
      throws Exception {
    return mapper.writeValueAsString(message.signed(secret, channel));
  }

  private String tamper(String json, String field, Object value) throws Exception {
    ObjectNode node = (ObjectNode) mapper.readTree(json);
    node.set(field, mapper.valueToTree(value));
    return mapper.writeValueAsString(node);
  }

  @Test
  @DisplayName("A rule set change signed by the publisher is accepted")
  void acceptsSignedRuleSetChange() throws Exception {
    String json =
        published(RuleChangeMessage.forRuleSet("orders", "fluxgate-control"), SECRET, CHANNEL);

    JsonNode tree = mapper.readTree(json);
    assertThat(tree.path("version").asInt()).isEqualTo(2);
    assertThat(tree.path("nonce").asText()).isNotBlank();
    assertThat(tree.path("signature").asText()).isNotBlank();

    Optional<RuleReloadEvent> event = verifier(SECRET, CHANNEL).parseMessage(json);

    assertThat(event).isPresent();
    assertThat(event.get().getRuleSetId()).isEqualTo("orders");
    assertThat(event.get().isFullReload()).isFalse();
  }

  @Test
  @DisplayName("A full reload signed by the publisher is accepted")
  void acceptsSignedFullReload() throws Exception {
    String json = published(RuleChangeMessage.fullReload("fluxgate-control"), SECRET, CHANNEL);

    Optional<RuleReloadEvent> event = verifier(SECRET, CHANNEL).parseMessage(json);

    assertThat(event).isPresent();
    assertThat(event.get().isFullReload()).isTrue();
  }

  @Test
  @DisplayName("The same published message is rejected the second time (replay)")
  void rejectsReplay() throws Exception {
    String json =
        published(RuleChangeMessage.forRuleSet("orders", "fluxgate-control"), SECRET, CHANNEL);
    RedisPubSubReloadStrategy strategy = verifier(SECRET, CHANNEL);

    assertThat(strategy.parseMessage(json)).isPresent();
    assertThat(strategy.parseMessage(json)).isEmpty();
  }

  @Test
  @DisplayName("A message signed for another channel is rejected")
  void rejectsOtherChannel() throws Exception {
    String json =
        published(
            RuleChangeMessage.forRuleSet("orders", "fluxgate-control"), SECRET, "other:channel");

    assertThat(verifier(SECRET, CHANNEL).parseMessage(json)).isEmpty();
  }

  @Test
  @DisplayName("A message signed with a different secret is rejected")
  void rejectsWrongSecret() throws Exception {
    String json =
        published(
            RuleChangeMessage.forRuleSet("orders", "fluxgate-control"), "wrong-secret", CHANNEL);

    assertThat(verifier(SECRET, CHANNEL).parseMessage(json)).isEmpty();
  }

  @Test
  @DisplayName("Tampering with the nonce is rejected")
  void rejectsTamperedNonce() throws Exception {
    String json =
        published(RuleChangeMessage.forRuleSet("orders", "fluxgate-control"), SECRET, CHANNEL);

    assertThat(verifier(SECRET, CHANNEL).parseMessage(tamper(json, "nonce", "another-nonce")))
        .isEmpty();
  }

  @Test
  @DisplayName("Tampering with the payload is rejected")
  void rejectsTamperedPayload() throws Exception {
    String json =
        published(RuleChangeMessage.forRuleSet("orders", "fluxgate-control"), SECRET, CHANNEL);
    RedisPubSubReloadStrategy strategy = verifier(SECRET, CHANNEL);

    assertThat(strategy.parseMessage(tamper(json, "ruleSetId", "payments"))).isEmpty();
    assertThat(strategy.parseMessage(tamper(json, "fullReload", true))).isEmpty();
    assertThat(strategy.parseMessage(tamper(json, "source", "attacker"))).isEmpty();
    assertThat(strategy.parseMessage(tamper(json, "timestamp", System.currentTimeMillis() + 1)))
        .isEmpty();
    // None of the forgeries poisoned the replay cache for the genuine message.
    assertThat(strategy.parseMessage(json)).isPresent();
  }

  @Test
  @DisplayName("An unsigned publisher message is rejected by a verifying strategy")
  void rejectsUnsigned() throws Exception {
    String json = mapper.writeValueAsString(RuleChangeMessage.fullReload("fluxgate-control"));

    assertThat(verifier(SECRET, CHANNEL).parseMessage(json)).isEmpty();
  }
}
