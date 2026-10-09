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

  private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

  private HmacSigner() {
    // Utility class
  }

  /**
   * Builds the canonical string a rule change signature is computed over.
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
