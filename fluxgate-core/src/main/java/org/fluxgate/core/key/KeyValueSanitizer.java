package org.fluxgate.core.key;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Sanitises raw key values before they become part of a {@link RateLimitKey}.
 *
 * <p>Key values originate from untrusted request data (headers, forwarded IPs, custom attributes).
 * Without sanitisation an attacker can inflate the storage key space, smuggle glob metacharacters
 * into {@code SCAN} patterns, or break key parsing with control characters.
 *
 * <p>The output only ever contains {@code [A-Za-z0-9._:@-]} and is at most {@link #MAX_LENGTH}
 * characters long. The mapping is <em>injective</em> short of a hash collision, so two identities
 * cannot share a bucket or an allow/deny entry because sanitising made them look alike:
 *
 * <ul>
 *   <li>A value of at most {@link #MAX_LENGTH} allowed characters that does not start with {@code
 *       h:} is returned unchanged.
 *   <li>Any other value of at most {@code MAX_LENGTH - 19} characters becomes {@code h:}, the value
 *       with every disallowed character replaced by {@code _}, {@code :} and the first 16 hex
 *       digits of the SHA-256 of the original value, e.g. {@code a+1} becomes {@code h:a_1:<16
 *       hex>}. The 16 hex digits are a SHA-256 digest truncated to 64 bits: two such values only
 *       collide if they have the same restricted form <em>and</em> the same 64-bit digest prefix.
 *   <li>Longer values become {@code h:} followed by the full 64-digit SHA-256 hex of the original
 *       value.
 * </ul>
 *
 * <p>The {@code h:} marker is what keeps the mapping injective: an unchanged value never starts
 * with it, and every rewritten value does. As a consequence sanitising is <em>not</em> idempotent:
 * sanitising a rewritten value rewrites it again (no injective mapping can be idempotent unless it
 * is the identity). Sanitise raw values exactly once.
 */
public final class KeyValueSanitizer {

  /** Maximum sanitised length; longer or rewritten values are hashed to stay within it. */
  public static final int MAX_LENGTH = 256;

  /** Marks a rewritten value; an unchanged value never starts with it. */
  private static final String MARKER = "h:";

  /** Hex digits of the digest appended to a value whose characters were replaced. */
  private static final int SHORT_DIGEST_LENGTH = 16;

  /** {@code h:} + restricted value + {@code :} + short digest. */
  private static final int REWRITE_OVERHEAD = MARKER.length() + 1 + SHORT_DIGEST_LENGTH;

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

    if (value.length() <= MAX_LENGTH && isClean(value) && !value.startsWith(MARKER)) {
      return value;
    }

    String digest = sha256Hex(value);
    if (value.length() <= MAX_LENGTH - REWRITE_OVERHEAD) {
      return MARKER + restrictCharset(value) + ':' + digest.substring(0, SHORT_DIGEST_LENGTH);
    }
    return MARKER + digest;
  }

  /**
   * Tells whether a value has the shape of a rewritten value, {@code h:<restricted>:<16 hex>} or
   * {@code h:<64 hex>}, as produced by {@link #sanitize(String)}. Used to pass configured keys that
   * are already in encoded form through unchanged instead of encoding them a second time.
   */
  static boolean isEncoded(String value) {
    if (value == null
        || !value.startsWith(MARKER)
        || value.length() > MAX_LENGTH
        || !isClean(value)) {
      return false;
    }
    if (value.length() == MARKER.length() + 64 && isHex(value, MARKER.length(), value.length())) {
      return true;
    }
    int separator = value.length() - SHORT_DIGEST_LENGTH - 1;
    return separator > MARKER.length()
        && value.charAt(separator) == ':'
        && isHex(value, separator + 1, value.length());
  }

  /** Tells whether the value consists of allowed characters only. */
  static boolean isClean(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (!isAllowed(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isHex(String value, int from, int to) {
    for (int i = from; i < to; i++) {
      char c = value.charAt(i);
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
        return false;
      }
    }
    return true;
  }

  private static String restrictCharset(String value) {
    StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      sb.append(isAllowed(c) ? c : REPLACEMENT);
    }
    return sb.toString();
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

  /**
   * SHA-256 over the UTF-16 code units of the value. Hashing the code units rather than an encoded
   * byte form keeps distinct strings distinct even when they contain unpaired surrogates, which a
   * charset encoder would collapse into the same replacement bytes.
   */
  private static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] units = new byte[value.length() * 2];
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        units[i * 2] = (byte) (c >>> 8);
        units[i * 2 + 1] = (byte) c;
      }
      byte[] hash = digest.digest(units);
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
