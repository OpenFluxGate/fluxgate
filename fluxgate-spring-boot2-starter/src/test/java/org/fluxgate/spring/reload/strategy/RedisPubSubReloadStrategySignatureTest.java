package org.fluxgate.spring.reload.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.fluxgate.core.reload.RuleReloadEvent;
import org.fluxgate.core.util.HmacSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Signature verification tests for {@link RedisPubSubReloadStrategy} (H-3).
 *
 * <p>Parsing hardening stops an accidental message; it does nothing against {@code PUBLISH
 * fluxgate:rule-reload '*'}. With a secret configured, only a message this data plane's control
 * plane actually signed - and signed recently - may drop every token bucket.
 */
class RedisPubSubReloadStrategySignatureTest {

  private static final String SECRET = "unit-test-only-secret";

  private final RedisPubSubReloadStrategy verifying = strategy(SECRET, Duration.ofMinutes(5));

  private final RedisPubSubReloadStrategy permissive = strategy(null, null);

  private static RedisPubSubReloadStrategy strategy(String secret, Duration maxMessageAge) {
    return new RedisPubSubReloadStrategy(
        "redis://localhost:6379",
        "fluxgate:rule-reload",
        false,
        Duration.ofSeconds(5),
        Duration.ofSeconds(5),
        secret,
        maxMessageAge);
  }

  private static String signedMessage(String ruleSetId, boolean fullReload, long timestamp) {
    return signedMessage(ruleSetId, fullReload, timestamp, SECRET);
  }

  private static String signedMessage(
      String ruleSetId, boolean fullReload, long timestamp, String secret) {
    String canonical =
        HmacSigner.canonicalRuleChange(1, ruleSetId, fullReload, timestamp, "fluxgate-control");
    return message(ruleSetId, fullReload, timestamp, HmacSigner.sign(secret, canonical));
  }

  private static String message(
      String ruleSetId, boolean fullReload, long timestamp, String signature) {
    StringBuilder sb = new StringBuilder("{\"version\":1,\"ruleSetId\":");
    sb.append(ruleSetId == null ? "null" : "\"" + ruleSetId + "\"");
    sb.append(",\"fullReload\":").append(fullReload);
    sb.append(",\"timestamp\":").append(timestamp);
    sb.append(",\"source\":\"fluxgate-control\"");
    if (signature != null) {
      sb.append(",\"signature\":\"").append(signature).append('"');
    }
    return sb.append('}').toString();
  }

  @Test
  @DisplayName("A correctly signed and fresh message is acted on")
  void shouldAcceptASignedMessage() {
    Optional<RuleReloadEvent> event =
        verifying.parseMessage(signedMessage("orders", false, System.currentTimeMillis()));

    assertThat(event).isPresent();
    assertThat(event.get().getRuleSetId()).isEqualTo("orders");
  }

  @Test
  @DisplayName("A correctly signed full reload is acted on")
  void shouldAcceptASignedFullReload() {
    Optional<RuleReloadEvent> event =
        verifying.parseMessage(signedMessage(null, true, System.currentTimeMillis()));

    assertThat(event).isPresent();
    assertThat(event.get().isFullReload()).isTrue();
  }

  @Test
  @DisplayName("Tampering with a signed field invalidates the message")
  void shouldRejectATamperedMessage() {
    String signed = signedMessage("orders", false, System.currentTimeMillis());
    String tampered = signed.replace("\"orders\"", "\"payments\"");

    assertThat(verifying.parseMessage(tampered)).isEmpty();
  }

  @Test
  @DisplayName("Escalating a signed rule set change into a full reload is rejected")
  void shouldRejectAnEscalatedMessage() {
    String signed = signedMessage("orders", false, System.currentTimeMillis());
    String escalated = signed.replace("\"fullReload\":false", "\"fullReload\":true");

    assertThat(verifying.parseMessage(escalated)).isEmpty();
  }

  @Test
  @DisplayName("A message signed with another secret is rejected")
  void shouldRejectAForeignSignature() {
    String foreign =
        signedMessage("orders", false, System.currentTimeMillis(), "some-other-secret");

    assertThat(verifying.parseMessage(foreign)).isEmpty();
  }

  @Test
  @DisplayName("A replayed message outside the window is rejected")
  void shouldRejectAStaleMessage() {
    long tenMinutesAgo = System.currentTimeMillis() - Duration.ofMinutes(10).toMillis();

    assertThat(verifying.parseMessage(signedMessage("orders", false, tenMinutesAgo))).isEmpty();
  }

  @Test
  @DisplayName("A message timestamped far in the future is rejected")
  void shouldRejectAFutureMessage() {
    long inTenMinutes = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis();

    assertThat(verifying.parseMessage(signedMessage("orders", false, inTenMinutes))).isEmpty();
  }

  @Test
  @DisplayName("An unsigned JSON message is rejected once a secret is configured")
  void shouldRejectAnUnsignedJsonMessage() {
    assertThat(verifying.parseMessage(message("orders", false, System.currentTimeMillis(), null)))
        .isEmpty();
    assertThat(verifying.parseMessage("{\"ruleSetId\":\"orders\"}")).isEmpty();
    assertThat(verifying.parseMessage("{\"fullReload\":true}")).isEmpty();
  }

  @Test
  @DisplayName("The legacy plain-text forms are rejected once a secret is configured")
  void shouldRejectThePlainTextForms() {
    assertThat(verifying.parseMessage("*")).isEmpty();
    assertThat(verifying.parseMessage("orders")).isEmpty();
  }

  @Test
  @DisplayName("Without a secret the previous behaviour is unchanged")
  void shouldKeepTheLegacyBehaviourWithoutASecret() {
    assertThat(permissive.isVerifyingSignatures()).isFalse();
    assertThat(permissive.parseMessage("*")).isPresent();
    assertThat(permissive.parseMessage("orders")).isPresent();
    assertThat(permissive.parseMessage("{\"fullReload\":true}")).isPresent();

    // A signed message is still a valid message; the signature is simply not checked.
    long staleTimestamp = System.currentTimeMillis() - Duration.ofDays(1).toMillis();
    assertThat(permissive.parseMessage(signedMessage("orders", false, staleTimestamp))).isPresent();
  }

  @Test
  @DisplayName("A blank secret means no verification, and the replay window has a default")
  void shouldTreatABlankSecretAsAbsent() {
    RedisPubSubReloadStrategy blank = strategy("   ", null);

    assertThat(blank.isVerifyingSignatures()).isFalse();
    assertThat(blank.getMaxMessageAge())
        .isEqualTo(RedisPubSubReloadStrategy.DEFAULT_MAX_MESSAGE_AGE);
    assertThat(blank.parseMessage("*")).isPresent();
  }

  @Test
  @DisplayName("Control characters in a rejected payload never reach the log verbatim (N-11)")
  void shouldSanitiseTheLoggedPayload() {
    // Only asserts that a CRLF payload is still ignored rather than acted on; LogSanitizer itself
    // is covered by its own test.
    assertThat(verifying.parseMessage("{\"ruleSetId\":\"orders\\r\\nFAKE LOG LINE\"}")).isEmpty();
    assertThat(permissive.parseMessage("orders\r\nFAKE LOG LINE")).isPresent();
  }
}
