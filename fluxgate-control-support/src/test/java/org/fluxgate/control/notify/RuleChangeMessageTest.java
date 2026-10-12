package org.fluxgate.control.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fluxgate.core.util.HmacSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RuleChangeMessageTest {

  private static final String SECRET = "unit-test-only-secret";
  private static final String CHANNEL = "fluxgate:rule-reload";

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

  @Test
  @DisplayName("An unsigned message carries no signature and omits the field from the JSON")
  void shouldStayUnsignedByDefault() throws Exception {
    RuleChangeMessage message = RuleChangeMessage.forRuleSet("orders", "studio");

    assertThat(message.getSignature()).isNull();
    assertThat(objectMapper.writeValueAsString(message)).doesNotContain("signature");
  }

  @Test
  @DisplayName("A signed message verifies against the same secret")
  void shouldSignWithTheSharedSecret() {
    RuleChangeMessage signed =
        RuleChangeMessage.forRuleSet("orders", "studio").signed(SECRET, CHANNEL);

    assertThat(signed.getSignature()).isNotNull();
    assertThat(HmacSigner.verify(SECRET, signed.canonicalForm(CHANNEL), signed.getSignature()))
        .isTrue();
    assertThat(
            HmacSigner.verify(
                "another-secret", signed.canonicalForm(CHANNEL), signed.getSignature()))
        .isFalse();
  }

  @Test
  @DisplayName("Signing keeps every other field, so the canonical form round-trips through JSON")
  void shouldKeepTheSignatureThroughSerialization() throws Exception {
    RuleChangeMessage original = RuleChangeMessage.fullReload("studio").signed(SECRET, CHANNEL);

    String json = objectMapper.writeValueAsString(original);
    RuleChangeMessage deserialized = objectMapper.readValue(json, RuleChangeMessage.class);

    assertThat(deserialized.getSignature()).isEqualTo(original.getSignature());
    assertThat(deserialized.canonicalForm(CHANNEL)).isEqualTo(original.canonicalForm(CHANNEL));
    assertThat(
            HmacSigner.verify(
                SECRET, deserialized.canonicalForm(CHANNEL), deserialized.getSignature()))
        .isTrue();
  }

  @Test
  @DisplayName("Changing a signed field invalidates the signature")
  void shouldNotVerifyATamperedMessage() {
    RuleChangeMessage signed =
        RuleChangeMessage.forRuleSet("orders", "studio").signed(SECRET, CHANNEL);

    RuleChangeMessage tampered =
        new RuleChangeMessage(
            signed.getVersion(),
            null,
            true,
            signed.getTimestamp(),
            signed.getSource(),
            signed.getNonce(),
            signed.getSignature());

    assertThat(HmacSigner.verify(SECRET, tampered.canonicalForm(CHANNEL), tampered.getSignature()))
        .isFalse();
  }

  @Test
  @SuppressWarnings("deprecation")
  @DisplayName("The deprecated 6-argument constructor refuses a version 2 signature")
  void sixArgConstructor_v2Signature_isRejected() {
    assertThatThrownBy(
            () ->
                new RuleChangeMessage(
                    RuleChangeMessage.SCHEMA_VERSION, "orders", false, 1L, "studio", "abcd"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nonce");
  }

  @Test
  @SuppressWarnings("deprecation")
  @DisplayName(
      "The deprecated 6-argument constructor still builds unsigned v2 and signed v1 messages")
  void sixArgConstructor_unsignedV2AndSignedV1_areAccepted() {
    RuleChangeMessage unsigned =
        new RuleChangeMessage(
            RuleChangeMessage.SCHEMA_VERSION, "orders", false, 1L, "studio", null);
    RuleChangeMessage legacy =
        new RuleChangeMessage(
            RuleChangeMessage.LEGACY_SCHEMA_VERSION, "orders", false, 1L, "studio", "abcd");

    assertThat(unsigned.getNonce()).isNotNull();
    assertThat(unsigned.getSignature()).isNull();
    assertThat(legacy.getNonce()).isNull();
    assertThat(legacy.getSignature()).isEqualTo("abcd");
  }

  @Test
  @DisplayName("A signed v2 message survives a JSON round trip with its nonce and still verifies")
  void signedV2_jsonRoundTrip_keepsNonceAndVerifies() throws Exception {
    RuleChangeMessage signed =
        RuleChangeMessage.forRuleSet("orders", "studio").signed(SECRET, CHANNEL);

    RuleChangeMessage read =
        objectMapper.readValue(objectMapper.writeValueAsString(signed), RuleChangeMessage.class);

    assertThat(read.getNonce()).isEqualTo(signed.getNonce());
    assertThat(HmacSigner.verify(SECRET, read.canonicalForm(CHANNEL), read.getSignature()))
        .isTrue();
  }

  @Test
  void shouldRejectSigningWithoutASecret() {
    RuleChangeMessage message = RuleChangeMessage.forRuleSet("orders", "studio");

    assertThatThrownBy(() -> message.signed(null, CHANNEL))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("A payload from an older publisher that carries no version is read as version 1")
  void shouldDefaultTheVersionWhenThePayloadHasNone() throws Exception {
    String legacyJson =
        "{\"ruleSetId\":\"orders\",\"fullReload\":false,\"timestamp\":1,\"source\":\"studio\"}";

    RuleChangeMessage deserialized = objectMapper.readValue(legacyJson, RuleChangeMessage.class);

    assertThat(deserialized.getVersion()).isEqualTo(RuleChangeMessage.LEGACY_SCHEMA_VERSION);
    assertThat(deserialized.getNonce()).isNull();
    assertThat(deserialized.getRuleSetId()).isEqualTo("orders");
    assertThat(deserialized.getSignature()).isNull();
  }

  @Test
  @DisplayName("A non-positive version is not trusted either and falls back to version 1")
  void shouldDefaultTheVersionWhenItIsNotPositive() {
    RuleChangeMessage zero = new RuleChangeMessage(0, "orders", false, 1L, "studio");
    RuleChangeMessage negative = new RuleChangeMessage(-7, "orders", false, 1L, "studio");

    assertThat(zero.getVersion()).isEqualTo(RuleChangeMessage.LEGACY_SCHEMA_VERSION);
    assertThat(negative.getVersion()).isEqualTo(RuleChangeMessage.LEGACY_SCHEMA_VERSION);
    assertThat(zero.getSignature()).isNull();
  }

  @Test
  @DisplayName("An explicit future version is kept, so a consumer can drop what it cannot read")
  void shouldKeepAnExplicitVersion() {
    RuleChangeMessage future = new RuleChangeMessage(99, "orders", false, 1L, "studio");

    assertThat(future.getVersion()).isEqualTo(99);
    assertThat(future.canonicalForm(CHANNEL)).contains("99");
  }

  @Test
  @DisplayName("toString says whether the message is signed, for both message shapes")
  void shouldReportSignednessInToString() {
    RuleChangeMessage ruleSet = RuleChangeMessage.forRuleSet("orders", "studio");
    RuleChangeMessage fullReload = RuleChangeMessage.fullReload("studio");

    assertThat(ruleSet.toString()).contains("ruleSetId='orders'").contains("signed=false");
    assertThat(ruleSet.signed(SECRET, CHANNEL).toString())
        .contains("ruleSetId='orders'")
        .contains("signed=true");
    assertThat(fullReload.toString()).contains("fullReload=true").contains("signed=false");
    assertThat(fullReload.signed(SECRET, CHANNEL).toString())
        .contains("fullReload=true")
        .contains("signed=true");
  }

  @Test
  @DisplayName("New messages are version 2 and carry a fresh nonce each")
  void shouldCarryAUniqueNonce() throws Exception {
    RuleChangeMessage first = RuleChangeMessage.forRuleSet("orders", "studio");
    RuleChangeMessage second = RuleChangeMessage.forRuleSet("orders", "studio");

    assertThat(first.getVersion()).isEqualTo(2);
    assertThat(first.getNonce()).isNotBlank().isNotEqualTo(second.getNonce());
    assertThat(objectMapper.writeValueAsString(first)).contains("\"nonce\":\"" + first.getNonce());
  }

  @Test
  @DisplayName("The signature binds the channel and the nonce")
  void shouldBindChannelAndNonce() throws Exception {
    RuleChangeMessage signed =
        RuleChangeMessage.forRuleSet("orders", "studio").signed(SECRET, CHANNEL);

    assertThat(
            HmacSigner.verify(SECRET, signed.canonicalForm("other:channel"), signed.getSignature()))
        .isFalse();

    String json = objectMapper.writeValueAsString(signed);
    String otherNonce = json.replace(signed.getNonce(), "00000000-0000-0000-0000-000000000000");
    RuleChangeMessage tampered = objectMapper.readValue(otherNonce, RuleChangeMessage.class);
    assertThat(HmacSigner.verify(SECRET, tampered.canonicalForm(CHANNEL), tampered.getSignature()))
        .isFalse();
  }

  @Test
  @DisplayName("A deserialized version 2 payload without a nonce keeps no nonce")
  void shouldNotInventANonceForAPayload() throws Exception {
    RuleChangeMessage parsed =
        objectMapper.readValue(
            "{\"version\":2,\"ruleSetId\":\"orders\",\"fullReload\":false,\"timestamp\":1}",
            RuleChangeMessage.class);

    assertThat(parsed.getNonce()).isNull();
  }

  @Test
  @SuppressWarnings("deprecation")
  @DisplayName("The deprecated signed(secret) produces a verifiable version 1 message")
  void deprecatedSignShouldProduceAVersion1Message() {
    RuleChangeMessage legacy = RuleChangeMessage.forRuleSet("orders", "studio").signed(SECRET);

    assertThat(legacy.getVersion()).isEqualTo(RuleChangeMessage.LEGACY_SCHEMA_VERSION);
    assertThat(legacy.getNonce()).isNull();
    assertThat(
            HmacSigner.verify(
                SECRET,
                HmacSigner.canonicalRuleChange(1, "orders", false, legacy.getTimestamp(), "studio"),
                legacy.getSignature()))
        .isTrue();
    assertThatThrownBy(() -> RuleChangeMessage.fullReload("studio").canonicalForm())
        .isInstanceOf(IllegalStateException.class);
  }
}
