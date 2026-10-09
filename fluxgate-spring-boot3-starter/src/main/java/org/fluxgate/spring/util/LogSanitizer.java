package org.fluxgate.spring.util;

/**
 * Sanitizes untrusted request data before it is written to logs or the SLF4J MDC.
 *
 * <p>Header and query string values are fully attacker controlled. Writing them verbatim allows log
 * injection: a value containing {@code \r\n} forges additional log lines, and control characters
 * corrupt JSON encoders and log shippers. This utility replaces every control character with {@code
 * _} and caps the value length so a single request cannot flood the log.
 */
public final class LogSanitizer {

  /** Maximum number of characters kept from a sanitized value. */
  public static final int MAX_LENGTH = 512;

  private LogSanitizer() {
    // Utility class
  }

  /**
   * Sanitizes a value for logging or MDC storage.
   *
   * <p>Control characters (CR, LF, NUL and everything else below {@code 0x20}) are replaced by
   * {@code _}, and the result is truncated to {@link #MAX_LENGTH} characters.
   *
   * @param value the raw value, may be null
   * @return the sanitized value, or null when the input was null
   */
  public static String sanitize(String value) {
    return sanitize(value, MAX_LENGTH);
  }

  /**
   * Sanitizes a value for logging or MDC storage using a custom length cap.
   *
   * @param value the raw value, may be null
   * @param maxLength maximum number of characters to keep (must be positive)
   * @return the sanitized value, or null when the input was null
   */
  public static String sanitize(String value, int maxLength) {
    if (value == null) {
      return null;
    }
    int limit = Math.min(value.length(), Math.max(1, maxLength));
    StringBuilder sb = new StringBuilder(limit);
    for (int i = 0; i < limit; i++) {
      char c = value.charAt(i);
      sb.append(c < 0x20 || c == 0x7F ? '_' : c);
    }
    return sb.toString();
  }
}
