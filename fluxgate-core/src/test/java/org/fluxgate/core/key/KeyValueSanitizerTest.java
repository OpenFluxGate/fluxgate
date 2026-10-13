package org.fluxgate.core.key;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("KeyValueSanitizer Tests")
class KeyValueSanitizerTest {

  private static String repeat(char c, int times) {
    return String.valueOf(c).repeat(times);
  }

  @Test
  @DisplayName("clean values pass through unchanged")
  void cleanValuesAreUnchanged() {
    assertThat(KeyValueSanitizer.sanitize("user-1_a.b@c:d")).isEqualTo("user-1_a.b@c:d");
    assertThat(KeyValueSanitizer.sanitize(repeat('a', 256))).isEqualTo(repeat('a', 256));
  }

  @Test
  @DisplayName("a value with replaced characters never collides with the clean look-alike")
  void replacedCharactersDoNotCollide() {
    assertThat(KeyValueSanitizer.sanitize("a+1")).isNotEqualTo(KeyValueSanitizer.sanitize("a_1"));
    assertThat(KeyValueSanitizer.sanitize("a+1")).isNotEqualTo(KeyValueSanitizer.sanitize("a/1"));
    assertThat(KeyValueSanitizer.sanitize("a\uD800"))
        .isNotEqualTo(KeyValueSanitizer.sanitize("a\uD801"));
  }

  @Test
  @DisplayName("an encoded value cannot be forged with a clean input")
  void encodedFormCannotBeForged() {
    String encoded = KeyValueSanitizer.sanitize("a+1");

    assertThat(encoded).startsWith("h:");
    assertThat(KeyValueSanitizer.sanitize(encoded)).isNotEqualTo(encoded);
  }

  @Test
  @DisplayName("replaced characters keep a readable restricted form")
  void replacedCharactersStayReadable() {
    assertThat(KeyValueSanitizer.sanitize("10.0.0.1 */?[]"))
        .matches("h:10\\.0\\.0\\.1______:[0-9a-f]{16}");
  }

  @Test
  @DisplayName("over-long values are hashed with a marker")
  void overLongValuesAreHashed() {
    String a = KeyValueSanitizer.sanitize(repeat('a', 257));
    String b = KeyValueSanitizer.sanitize(repeat('a', 258));

    assertThat(a).matches("h:[0-9a-f]{64}");
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  @DisplayName("every output stays within the charset and length bound")
  void outputIsBounded() {
    for (String raw :
        new String[] {"a+1", "h:x", repeat('é', 255), repeat('x', 1000), "{*}", "\n"}) {
      String out = KeyValueSanitizer.sanitize(raw);
      assertThat(out).matches("[A-Za-z0-9._:@-]+");
      assertThat(out.length()).isLessThanOrEqualTo(KeyValueSanitizer.MAX_LENGTH);
    }
  }

  @Test
  @DisplayName("isEncoded recognises exactly the rewritten forms")
  void isEncodedRecognisesRewrittenForms() {
    for (String raw :
        new String[] {"a+1", "h:", "h:x", repeat('x', 300), "{*}", repeat('é', 237)}) {
      assertThat(KeyValueSanitizer.isEncoded(KeyValueSanitizer.sanitize(raw))).as(raw).isTrue();
    }
    for (String clean :
        new String[] {
          "alice",
          "h:",
          "h:x",
          "h:x:0123",
          "h:" + repeat('0', 63),
          "h:x:0123456789abcdeg",
          "h::0123456789abcdef",
          "h:a b:0123456789abcdef",
          "h:" + repeat('0', 65)
        }) {
      assertThat(KeyValueSanitizer.isEncoded(clean)).as(clean).isFalse();
    }
    assertThat(KeyValueSanitizer.isEncoded(null)).isFalse();
  }
}
