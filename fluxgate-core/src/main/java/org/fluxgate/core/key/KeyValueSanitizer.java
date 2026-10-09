package org.fluxgate.core.key;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Sanitises raw key values before they become part of a {@link RateLimitKey}.
 *
 * <p>Key values originate from untrusted request data (headers, forwarded IPs, custom attributes).
 * Without sanitisation an attacker can inflate the storage key space, smuggle glob metacharacters
 * into {@code SCAN} patterns, or break key parsing with control characters.
 *
 * <p>Two rules are applied, in order:
 *
 * <ul>
 *   <li>Every character outside {@code [A-Za-z0-9._:@-]} is replaced by {@code _}
 *   <li>Values longer than {@link #MAX_LENGTH} characters are replaced by the SHA-256 hex digest of
 *       the restricted value (64 characters, stable across JVMs)
 * </ul>
 *
 * <p>The operation is effectively idempotent: sanitising an already sanitised value returns it
 * unchanged.
 */
public final class KeyValueSanitizer {

  /** Maximum key value length; longer values are replaced by their SHA-256 hex digest. */
  public static final int MAX_LENGTH = 256;

  /** Character substituted for every disallowed character. */
  private static final char REPLACEMENT = '_';

  private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

  private KeyValueSanitizer() {
    // Prevent instantiation
  }

  /**
   * Sanitises the given key value.
   *
   * @param value the raw key value, may be null or empty
   * @return the sanitised value, or the input itself when it is null or empty
   */
  public static String sanitize(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }

    String restricted = restrictCharset(value);
    if (restricted.length() <= MAX_LENGTH) {
      return restricted;
    }
    return sha256Hex(restricted);
  }

  private static String restrictCharset(String value) {
    StringBuilder sb = null;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (isAllowed(c)) {
        if (sb != null) {
          sb.append(c);
        }
        continue;
      }
      if (sb == null) {
        sb = new StringBuilder(value.length());
        sb.append(value, 0, i);
      }
      sb.append(REPLACEMENT);
    }
    return sb != null ? sb.toString() : value;
  }

  private static boolean isAllowed(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || c == '.'
        || c == '_'
        || c == ':'
        || c == '@'
        || c == '-';
  }

  private static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      char[] hex = new char[hash.length * 2];
      for (int i = 0; i < hash.length; i++) {
        int b = hash[i] & 0xFF;
        hex[i * 2] = HEX_DIGITS[b >>> 4];
        hex[i * 2 + 1] = HEX_DIGITS[b & 0x0F];
      }
      return new String(hex);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandated by the Java platform; this cannot happen.
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
