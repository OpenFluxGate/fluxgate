package org.fluxgate.core.util;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 signing for the rule change notifications carried over Redis Pub/Sub.
 *
 * <p>The reload channel is an ordinary Redis channel: anyone who can reach the Redis can {@code
 * PUBLISH} to it, and a single crafted message triggers a full reload that drops every token
 * bucket. A shared secret turns "can reach Redis" into "knows the secret": the publisher signs the
 * canonical form of the message and the subscriber recomputes it, so a message it did not authorise
 * is ignored instead of obeyed.
 *
 * <p>The canonical form is defined here rather than in either module, because the publisher ({@code
 * fluxgate-control-support}) and the subscriber (the Spring Boot starters) must agree on it byte
 * for byte and do not see each other's classes.
 *
 * <p>Signatures are compared with {@link MessageDigest#isEqual(byte[], byte[])}, which does not
 * return early on the first differing byte.
 */
public final class HmacSigner {

  /** MAC algorithm used for every signature. */
  public static final String ALGORITHM = "HmacSHA256";

  /**
   * Minimum secret length, in UTF-8 bytes, that is not reported as weak.
   *
   * <p>HMAC-SHA256 has a 256-bit block of useful key material; a shorter shared secret is the
   * cheapest part of the scheme to brute-force offline from one captured message.
   */
  public static final int MIN_RECOMMENDED_SECRET_BYTES = 32;

  /** Domain tag that starts every version 2 canonical form. */
  static final String RULE_CHANGE_V2_TAG = "fluxgate-rule-change";

  private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

  private HmacSigner() {
    // Utility class
  }

  /**
   * Builds the version 1 canonical string a rule change signature is computed over.
   *
   * <p>Kept so version 1 messages can still be verified. It does not bind the channel or a nonce
   * and its separators are not escaped; publishers use {@link #canonicalRuleChangeV2} instead.
   *
   * <p>Format: {@code version|ruleSetId|fullReload|timestamp|source}. A null {@code ruleSetId} or
   * {@code source} contributes an empty field, so the separators always line up and a full reload
   * (which carries no rule set id) has exactly one representation.
   *
   * @param version the message schema version
   * @param ruleSetId the changed rule set, or null for a full reload
   * @param fullReload whether every rule set should be reloaded
   * @param timestamp creation time in epoch millis
   * @param source identifier of the publishing application
   * @return the canonical string to sign or verify
   */
  public static String canonicalRuleChange(
      int version, String ruleSetId, boolean fullReload, long timestamp, String source) {
    return version
        + "|"
        + (ruleSetId != null ? ruleSetId : "")
        + "|"
        + fullReload
        + "|"
        + timestamp
        + "|"
        + (source != null ? source : "");
  }

  /**
   * Builds the version 2 canonical string a rule change signature is computed over.
   *
   * <p>Unlike the version 1 form, every field is length-prefixed ({@code <utf8 bytes>:<value>}) and
   * a null field is encoded as {@code -}, so no value - whatever separators it contains - can shift
   * the boundary of its neighbour, and an empty string stays distinguishable from a missing field.
   * The form also binds the Redis {@code channel} the message is published on, so a message signed
   * for one environment cannot be replayed on another channel that shares the secret, and a
   * per-message {@code nonce} the subscriber deduplicates on.
   *
   * @param version the message schema version (2 or later)
   * @param channel the Pub/Sub channel the message is published on
   * @param ruleSetId the changed rule set, or null for a full reload
   * @param fullReload whether every rule set should be reloaded
   * @param timestamp creation time in epoch millis
   * @param source identifier of the publishing application
   * @param nonce unique per message identifier
   * @return the canonical string to sign or verify
   * @since 0.4.0
   */
  public static String canonicalRuleChangeV2(
      int version,
      String channel,
      String ruleSetId,
      boolean fullReload,
      long timestamp,
      String source,
      String nonce) {
    StringBuilder sb = new StringBuilder(128).append(RULE_CHANGE_V2_TAG);
    appendField(sb, Integer.toString(version));
    appendField(sb, channel);
    appendField(sb, ruleSetId);
    appendField(sb, Boolean.toString(fullReload));
    appendField(sb, Long.toString(timestamp));
    appendField(sb, source);
    appendField(sb, nonce);
    return sb.toString();
  }

  private static void appendField(StringBuilder sb, String value) {
    sb.append('|');
    if (value == null) {
      sb.append('-');
      return;
    }
    sb.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
  }

  /**
   * Normalises a configured shared secret the same way on the publishing and the verifying side.
   *
   * <p>Leading and trailing whitespace is removed - a secret read from a file or an environment
   * variable often carries a trailing newline on one side only, which would otherwise make every
   * signature fail - and a blank secret means "no secret".
   *
   * @param secret the configured secret, may be null
   * @return the trimmed secret, or null when it is null or blank
   * @since 0.4.0
   */
  public static String normalizeSecret(String secret) {
    if (secret == null) {
      return null;
    }
    String trimmed = secret.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * Whether a (normalised) secret is shorter than {@link #MIN_RECOMMENDED_SECRET_BYTES}.
   *
   * @param secret the secret, may be null
   * @return true when the secret is present but shorter than recommended
   * @since 0.4.0
   */
  public static boolean isWeakSecret(String secret) {
    return secret != null
        && secret.getBytes(StandardCharsets.UTF_8).length < MIN_RECOMMENDED_SECRET_BYTES;
  }

  /**
   * Signs a canonical string with the given secret.
   *
   * @param secret the shared secret, must not be null
   * @param canonical the canonical string, must not be null
   * @return the signature as lowercase hex
   * @throws IllegalStateException if the JVM does not provide HmacSHA256
   */
  public static String sign(String secret, String canonical) {
    Objects.requireNonNull(secret, "secret must not be null");
    Objects.requireNonNull(canonical, "canonical must not be null");

    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
      return toHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("Cannot compute an " + ALGORITHM + " signature", e);
    }
  }

  /**
   * Verifies a signature against a canonical string.
   *
   * <p>A missing or blank secret or signature is a verification failure, never a pass: the caller
   * decides whether an unsigned message is acceptable, and it must not be able to make that
   * decision by accident.
   *
   * @param secret the shared secret
   * @param canonical the canonical string the signature should cover
   * @param signature the hex signature received with the message
   * @return true if the signature matches
   */
  public static boolean verify(String secret, String canonical, String signature) {
    if (secret == null || secret.isEmpty() || signature == null || canonical == null) {
      return false;
    }

    String received = signature.trim().toLowerCase(Locale.ROOT);
    if (received.isEmpty()) {
      return false;
    }

    byte[] expected = sign(secret, canonical).getBytes(StandardCharsets.US_ASCII);
    return MessageDigest.isEqual(expected, received.getBytes(StandardCharsets.US_ASCII));
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(HEX_DIGITS[(b >> 4) & 0x0F]).append(HEX_DIGITS[b & 0x0F]);
    }
    return sb.toString();
  }
}
