package org.fluxgate.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for {@link HmacSigner}, the shared signature contract of the rule reload channel. */
class HmacSignerTest {

  private static final String SECRET = "unit-test-only-secret";

  @Test
  @DisplayName("A signature produced with the secret verifies with the same secret")
  void shouldRoundTrip() {
    String canonical = HmacSigner.canonicalRuleChange(1, "orders", false, 1_700_000_000_000L, "cp");

    String signature = HmacSigner.sign(SECRET, canonical);

    assertThat(signature).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(HmacSigner.verify(SECRET, canonical, signature)).isTrue();
  }

  @Test
  @DisplayName("Verification is case insensitive and tolerates surrounding whitespace")
  void shouldNormaliseTheReceivedSignature() {
    String canonical = HmacSigner.canonicalRuleChange(1, "orders", false, 1L, "cp");
    String signature = HmacSigner.sign(SECRET, canonical);

    assertThat(HmacSigner.verify(SECRET, canonical, "  " + signature.toUpperCase() + "\n"))
        .isTrue();
  }

  @Test
  @DisplayName("A tampered canonical form does not verify")
  void shouldRejectATamperedMessage() {
    String signed = HmacSigner.canonicalRuleChange(1, "orders", false, 1L, "cp");
    String tampered = HmacSigner.canonicalRuleChange(1, "orders", true, 1L, "cp");

    String signature = HmacSigner.sign(SECRET, signed);

    assertThat(HmacSigner.verify(SECRET, tampered, signature)).isFalse();
  }

  @Test
  @DisplayName("A signature from another secret does not verify")
  void shouldRejectAForeignSignature() {
    String canonical = HmacSigner.canonicalRuleChange(1, "orders", false, 1L, "cp");

    String signature = HmacSigner.sign("another-secret", canonical);

    assertThat(HmacSigner.verify(SECRET, canonical, signature)).isFalse();
  }

  @Test
  @DisplayName("A missing secret, canonical form or signature is a failure, never a pass")
  void shouldNeverPassWithoutBothSides() {
    String canonical = HmacSigner.canonicalRuleChange(1, "orders", false, 1L, "cp");
    String signature = HmacSigner.sign(SECRET, canonical);

    assertThat(HmacSigner.verify(null, canonical, signature)).isFalse();
    assertThat(HmacSigner.verify("", canonical, signature)).isFalse();
    assertThat(HmacSigner.verify(SECRET, canonical, null)).isFalse();
    assertThat(HmacSigner.verify(SECRET, canonical, "   ")).isFalse();
    assertThat(HmacSigner.verify(SECRET, null, signature)).isFalse();
  }

  @Test
  @DisplayName("The canonical form keeps one field per separator, including for a full reload")
  void shouldBuildAStableCanonicalForm() {
    assertThat(HmacSigner.canonicalRuleChange(1, null, true, 42L, "cp")).isEqualTo("1||true|42|cp");
    assertThat(HmacSigner.canonicalRuleChange(1, "orders", false, 42L, null))
        .isEqualTo("1|orders|false|42|");
  }

  @Test
  void shouldRejectNullArgumentsWhenSigning() {
    assertThatThrownBy(() -> HmacSigner.sign(null, "x"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("secret");
    assertThatThrownBy(() -> HmacSigner.sign(SECRET, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("canonical");
  }

  @Test
  @DisplayName("v2: a separator inside a field cannot shift the boundary of its neighbour")
  void v2ShouldLengthPrefixEveryField() {
    String first = HmacSigner.canonicalRuleChangeV2(2, "ch", "a|b", false, 1L, "c", "n");
    String second = HmacSigner.canonicalRuleChangeV2(2, "ch", "a", false, 1L, "b|c", "n");

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  @DisplayName("v2: a null field and an empty field have different encodings")
  void v2ShouldDistinguishNullFromEmpty() {
    assertThat(HmacSigner.canonicalRuleChangeV2(2, "ch", null, true, 1L, "cp", "n"))
        .isNotEqualTo(HmacSigner.canonicalRuleChangeV2(2, "ch", "", true, 1L, "cp", "n"));
    assertThat(HmacSigner.canonicalRuleChangeV2(2, "ch", null, true, 42L, "cp", "n-1"))
        .isEqualTo("fluxgate-rule-change|1:2|2:ch|-|4:true|2:42|2:cp|3:n-1");
  }

  @Test
  @DisplayName("v2: the channel and the nonce are covered by the signature")
  void v2ShouldBindChannelAndNonce() {
    String signed = HmacSigner.canonicalRuleChangeV2(2, "prod", "orders", false, 1L, "cp", "n1");
    String signature = HmacSigner.sign(SECRET, signed);

    assertThat(HmacSigner.verify(SECRET, signed, signature)).isTrue();
    assertThat(
            HmacSigner.verify(
                SECRET,
                HmacSigner.canonicalRuleChangeV2(2, "staging", "orders", false, 1L, "cp", "n1"),
                signature))
        .isFalse();
    assertThat(
            HmacSigner.verify(
                SECRET,
                HmacSigner.canonicalRuleChangeV2(2, "prod", "orders", false, 1L, "cp", "n2"),
                signature))
        .isFalse();
  }

  @Test
  @DisplayName("v2: field lengths are counted in UTF-8 bytes")
  void v2ShouldCountUtf8Bytes() {
    assertThat(HmacSigner.canonicalRuleChangeV2(2, "ch", "\uc8fc\ubb38", false, 1L, null, null))
        .contains("|6:\uc8fc\ubb38|");
  }

  @Test
  @DisplayName("Secrets are trimmed and a blank secret is absent, on every side")
  void shouldNormaliseSecrets() {
    assertThat(HmacSigner.normalizeSecret(null)).isNull();
    assertThat(HmacSigner.normalizeSecret("  \n")).isNull();
    assertThat(HmacSigner.normalizeSecret(" abc\n")).isEqualTo("abc");
  }

  @Test
  @DisplayName("A secret shorter than 32 bytes is reported as weak")
  void shouldFlagWeakSecrets() {
    assertThat(HmacSigner.isWeakSecret(null)).isFalse();
    assertThat(HmacSigner.isWeakSecret("short")).isTrue();
    assertThat(HmacSigner.isWeakSecret("0123456789abcdef0123456789abcde")).isTrue();
    assertThat(HmacSigner.isWeakSecret("0123456789abcdef0123456789abcdef")).isFalse();
  }
}
