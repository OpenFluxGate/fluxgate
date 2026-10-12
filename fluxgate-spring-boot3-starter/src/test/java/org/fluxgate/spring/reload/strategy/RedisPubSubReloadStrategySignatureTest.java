package org.fluxgate.spring.reload.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.fluxgate.core.reload.RuleReloadEvent;
import org.fluxgate.core.util.HmacSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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

  private static final String CHANNEL = "fluxgate:rule-reload";

  /** A version 2 message, signed over the length-prefixed form bound to {@code signedChannel}. */
  private static String v2Message(
      String ruleSetId, boolean fullReload, long timestamp, String nonce, String signedChannel) {
    String canonical =
        HmacSigner.canonicalRuleChangeV2(
            2, signedChannel, ruleSetId, fullReload, timestamp, "fluxgate-control", nonce);
    StringBuilder sb = new StringBuilder("{\"version\":2,\"ruleSetId\":");
    sb.append(ruleSetId == null ? "null" : "\"" + ruleSetId + "\"");
    sb.append(",\"fullReload\":").append(fullReload);
    sb.append(",\"timestamp\":").append(timestamp);
    sb.append(",\"source\":\"fluxgate-control\"");
    if (nonce != null) {
      sb.append(",\"nonce\":\"").append(nonce).append('"');
    }
    sb.append(",\"signature\":\"").append(HmacSigner.sign(SECRET, canonical)).append('"');
    return sb.append('}').toString();
  }

  @Test
  @DisplayName("v2: a signed message with a nonce is acted on once")
  void shouldAcceptAVersion2MessageOnce() {
    String message =
        v2Message(
            "orders", false, System.currentTimeMillis(), UUID.randomUUID().toString(), CHANNEL);

    Optional<RuleReloadEvent> first = verifying.parseMessage(message);
    assertThat(first).isPresent();
    assertThat(first.get().getRuleSetId()).isEqualTo("orders");

    // The same bytes published again inside the window are a replay.
    assertThat(verifying.parseMessage(message)).isEmpty();
  }

  @Test
  @DisplayName("v2: two messages with different nonces are both acted on")
  void shouldAcceptDistinctNonces() {
    long now = System.currentTimeMillis();

    assertThat(verifying.parseMessage(v2Message(null, true, now, "nonce-a", CHANNEL))).isPresent();
    assertThat(verifying.parseMessage(v2Message(null, true, now, "nonce-b", CHANNEL))).isPresent();
  }

  @Test
  @DisplayName("v2: a message signed for another channel is rejected")
  void shouldRejectAMessageSignedForAnotherChannel() {
    String message =
        v2Message("orders", false, System.currentTimeMillis(), "nonce-1", "staging:rule-reload");

    assertThat(verifying.parseMessage(message)).isEmpty();
  }

  @Test
  @DisplayName("v2: a message without a nonce, or with a swapped nonce, is rejected")
  void shouldRejectAMissingOrTamperedNonce() {
    long now = System.currentTimeMillis();

    assertThat(verifying.parseMessage(v2Message("orders", false, now, null, CHANNEL))).isEmpty();
    String signed = v2Message("orders", false, now, "nonce-1", CHANNEL);
    assertThat(verifying.parseMessage(signed.replace("nonce-1", "nonce-2"))).isEmpty();
  }

  @Test
  @DisplayName("v1: replaying an identical signed legacy message is rejected")
  void shouldRejectAReplayedVersion1Message() {
    String message = signedMessage("orders", false, System.currentTimeMillis());

    assertThat(verifying.parseMessage(message)).isPresent();
    assertThat(verifying.parseMessage(message)).isEmpty();
  }

  @Test
  @DisplayName("A replay is only remembered by the instance that saw it, and only when authentic")
  void shouldNotRememberRejectedMessages() {
    long now = System.currentTimeMillis();
    String forged = v2Message("orders", false, now, "nonce-1", "other:channel");

    assertThat(verifying.parseMessage(forged)).isEmpty();
    // The forged copy did not poison the cache for the genuine message with the same nonce.
    assertThat(verifying.parseMessage(v2Message("orders", false, now, "nonce-1", CHANNEL)))
        .isPresent();
  }

  @Test
  @DisplayName("The default replay window is 60 seconds")
  void shouldDefaultToASixtySecondWindow() {
    RedisPubSubReloadStrategy defaults = strategy(SECRET, null);

    assertThat(defaults.getMaxMessageAge()).isEqualTo(Duration.ofSeconds(60));
    long twoMinutesAgo = System.currentTimeMillis() - Duration.ofMinutes(2).toMillis();
    assertThat(defaults.parseMessage(v2Message("orders", false, twoMinutesAgo, "n", CHANNEL)))
        .isEmpty();
  }

  @Test
  @DisplayName("The secret is trimmed like on the publishing side")
  void shouldTrimTheSecret() {
    RedisPubSubReloadStrategy padded = strategy("  " + SECRET + "\n", null);

    assertThat(
            padded.parseMessage(
                v2Message("orders", false, System.currentTimeMillis(), "n-1", CHANNEL)))
        .isPresent();
  }

  @Test
  @DisplayName("Without a secret a version 2 message is understood as well")
  void shouldUnderstandVersion2WithoutASecret() {
    assertThat(permissive.parseMessage("{\"version\":2,\"ruleSetId\":\"orders\",\"nonce\":\"n\"}"))
        .isPresent();
  }

  private static long legacyWarnings(ListAppender<ILoggingEvent> appender) {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .filter(e -> e.getFormattedMessage().contains("accept-legacy-signed"))
        .count();
  }

  @Test
  @DisplayName("v1 signed messages are accepted by default, with a single WARN")
  void shouldAcceptLegacySignedMessagesByDefaultAndWarnOnce() {
    Logger logger = (Logger) LoggerFactory.getLogger(RedisPubSubReloadStrategy.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      RedisPubSubReloadStrategy strategy = strategy(SECRET, null);
      assertThat(strategy.isAcceptingLegacySigned()).isTrue();

      long now = System.currentTimeMillis();
      assertThat(strategy.parseMessage(signedMessage("orders", false, now))).isPresent();
      assertThat(strategy.parseMessage(signedMessage("payments", false, now))).isPresent();

      assertThat(legacyWarnings(appender)).isEqualTo(1);
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  @DisplayName("accept-legacy-signed=false rejects v1 signed messages and keeps accepting v2")
  void shouldRejectLegacySignedMessagesWhenDisabled() {
    RedisPubSubReloadStrategy strategy = strategy(SECRET, null);
    strategy.setAcceptLegacySigned(false);

    long now = System.currentTimeMillis();
    assertThat(strategy.isAcceptingLegacySigned()).isFalse();
    assertThat(strategy.parseMessage(signedMessage("orders", false, now))).isEmpty();
    assertThat(strategy.parseMessage(v2Message("orders", false, now, "n-legacy-off", CHANNEL)))
        .isPresent();
  }
}
