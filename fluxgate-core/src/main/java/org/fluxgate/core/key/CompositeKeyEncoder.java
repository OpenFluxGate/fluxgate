package org.fluxgate.core.key;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Opt-in, versioned encoding of a named, ordered tuple for a CUSTOM attribute.
 *
 * <p>Returns {@code cs1_} plus unpadded Base64URL of SHA-256 (47 ASCII characters). Raw colons,
 * including IPv6, are accepted. Components are length-framed before hashing, not delimiter-joined.
 * SHA-256 is not encryption or anonymisation and has a theoretical collision risk.
 *
 * <p>No existing resolver, key or default is changed. Switching to this format creates a different
 * identity and may grant fresh quota; callers must plan counter and allow/deny transitions. Values
 * are not trimmed, case-folded or IP-normalised. Use trusted, validated application identities.
 *
 * @since 0.5.0
 */
public final class CompositeKeyEncoder {
  private static final int MAX_FIELDS = 8;
  private static final int MAX_VALUE_BYTES = 1024;

  private CompositeKeyEncoder() {}

  /**
   * Encodes required components in the explicit schema order, independent of map iteration order.
   *
   * <p>Schema and field names match {@code [a-z][a-z0-9_]{0,31}}. One to eight unique fields are
   * required; the map must have exactly those fields. Values must be nonblank, well-formed Unicode
   * and at most 1024 UTF-8 bytes each. Invalid input is rejected, never silently replaced or omitted.
   * Do not mutate the input collections during this call. Treat schema/order changes as migrations.
   *
   * @param schema application-owned schema identifier (version it when its meaning changes)
   * @param orderedFields ordered field names, not derived from an unordered map
   * @param values required raw values, including literal colons when present
   * @return a 47-character value for the CUSTOM attribute, not a complete Redis key
   * @throws IllegalArgumentException if the schema, fields or any value violate the contract
   */
  public static String encode(String schema, List<String> orderedFields, Map<String, String> values) {
    validateName(schema);
    if (orderedFields == null || orderedFields.isEmpty() || orderedFields.size() > MAX_FIELDS) {
      throw new IllegalArgumentException("Composite key requires 1 to 8 fields");
    }
    for (String field : orderedFields) {
      validateName(field);
    }
    HashSet<String> names = new HashSet<>(orderedFields);
    if (names.size() != orderedFields.size() || values == null || !values.keySet().equals(names)) {
      throw new IllegalArgumentException("Composite key requires unique, exactly matching fields");
    }
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeByte(1);
      out.writeByte(schema.length());
      out.write(schema.getBytes(StandardCharsets.US_ASCII));
      out.writeByte(orderedFields.size());
      for (String field : orderedFields) {
        String value = values.get(field);
        // Bound work before UTF-8 encoding. UTF-8 bytes are never fewer than UTF-16 code units.
        if (value == null || value.length() > MAX_VALUE_BYTES || value.isBlank()) {
          throw new IllegalArgumentException("Composite key component is missing or oversized");
        }
        byte[] raw = utf8(value);
        if (raw.length > MAX_VALUE_BYTES) {
          throw new IllegalArgumentException("Composite key component exceeds UTF-8 byte limit");
        }
        out.writeByte(field.length());
        out.write(field.getBytes(StandardCharsets.US_ASCII));
        out.writeInt(raw.length);
        out.write(raw);
      }
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
      return "cs1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Composite key encoding unavailable", impossible);
    }
  }

  private static void validateName(String name) {
    if (name == null || name.length() > 32 || !name.matches("[a-z][a-z0-9_]{0,31}")) {
      throw new IllegalArgumentException("Invalid composite key schema or field name");
    }
  }

  private static byte[] utf8(String value) {
    try {
      ByteBuffer buffer =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] result = new byte[buffer.remaining()];
      buffer.get(result);
      return result;
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException("Composite key component contains invalid Unicode");
    }
  }
}
