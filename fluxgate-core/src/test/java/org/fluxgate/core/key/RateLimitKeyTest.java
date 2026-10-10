package org.fluxgate.core.key;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RateLimitKey}.
 *
 * <p>RateLimitKey is an immutable class that wraps a string key used for rate limit bucket
 * identification.
 */
@DisplayName("RateLimitKey Tests")
class RateLimitKeyTest {

  // ==================== Factory Method Tests ====================

  @Nested
  @DisplayName("Factory Method Tests")
  class FactoryMethodTests {

    @Test
    @DisplayName("of() should create key with given value")
    void of_shouldCreateKeyWithGivenValue() {
      // given
      String keyValue = "192.168.1.1";

      // when
      RateLimitKey key = RateLimitKey.of(keyValue);

      // then
      assertNotNull(key);
      assertEquals(keyValue, key.key());
      assertEquals(keyValue, key.value());
    }

    @Test
    @DisplayName("of() should throw NullPointerException for null key value")
    void of_shouldThrowNullPointerExceptionForNullKeyValue() {
      // given / when / then
      assertThrows(NullPointerException.class, () -> RateLimitKey.of(null));
    }

    @Test
    @DisplayName("of() should allow empty string")
    void of_shouldAllowEmptyString() {
      // given / when
      RateLimitKey key = RateLimitKey.of("");

      // then
      assertEquals("", key.key());
      assertEquals("", key.value());
    }
  }

  // ==================== Constructor Tests ====================

  @Nested
  @DisplayName("Constructor Tests")
  class ConstructorTests {

    @Test
    @DisplayName("constructor should create key with given value")
    void constructor_shouldCreateKeyWithGivenValue() {
      // given
      String keyValue = "user-123";

      // when
      RateLimitKey key = new RateLimitKey(keyValue);

      // then
      assertEquals(keyValue, key.key());
    }
  }

  // ==================== Equality Tests ====================

  @Nested
  @DisplayName("Equality Tests")
  class EqualityTests {

    @Test
    @DisplayName("equals should return true for same key value")
    void equals_shouldReturnTrueForSameKeyValue() {
      // given
      String keyValue = "api-key-xyz";
      RateLimitKey key1 = RateLimitKey.of(keyValue);
      RateLimitKey key2 = RateLimitKey.of(keyValue);

      // when / then
      assertEquals(key1, key2);
      assertEquals(key2, key1);
    }

    @Test
    @DisplayName("equals should return false for different key values")
    void equals_shouldReturnFalseForDifferentKeyValues() {
      // given
      RateLimitKey key1 = RateLimitKey.of("key-1");
      RateLimitKey key2 = RateLimitKey.of("key-2");

      // when / then
      assertNotEquals(key1, key2);
      assertNotEquals(key2, key1);
    }

    @Test
    @DisplayName("equals should return true for same reference")
    void equals_shouldReturnTrueForSameReference() {
      // given
      RateLimitKey key = RateLimitKey.of("test-key");

      // when / then
      assertEquals(key, key);
    }

    @Test
    @DisplayName("equals should return false for null")
    void equals_shouldReturnFalseForNull() {
      // given
      RateLimitKey key = RateLimitKey.of("test-key");

      // when / then
      assertNotEquals(null, key);
    }

    @Test
    @DisplayName("equals should return false for different type")
    void equals_shouldReturnFalseForDifferentType() {
      // given
      RateLimitKey key = RateLimitKey.of("test-key");
      String notAKey = "test-key";

      // when / then
      assertNotEquals(key, notAKey);
    }

    @Test
    @DisplayName("constructor should throw NullPointerException for null key values")
    void constructor_shouldThrowNullPointerExceptionForNullKeyValues() {
      // given / when / then
      assertThrows(NullPointerException.class, () -> new RateLimitKey(null));
    }
  }

  // ==================== HashCode Tests ====================

  @Nested
  @DisplayName("HashCode Tests")
  class HashCodeTests {

    @Test
    @DisplayName("hashCode should be same for equal keys")
    void hashCode_shouldBeSameForEqualKeys() {
      // given
      String keyValue = "consistent-key";
      RateLimitKey key1 = RateLimitKey.of(keyValue);
      RateLimitKey key2 = RateLimitKey.of(keyValue);

      // when / then
      assertEquals(key1.hashCode(), key2.hashCode());
    }

    @Test
    @DisplayName("hashCode should be consistent across calls")
    void hashCode_shouldBeConsistentAcrossCalls() {
      // given
      RateLimitKey key = RateLimitKey.of("stable-key");

      // when
      int hash1 = key.hashCode();
      int hash2 = key.hashCode();
      int hash3 = key.hashCode();

      // then
      assertEquals(hash1, hash2);
      assertEquals(hash2, hash3);
    }

    @Test
    @DisplayName("hashCode should be different for different keys (likely)")
    void hashCode_shouldBeDifferentForDifferentKeys() {
      // given
      RateLimitKey key1 = RateLimitKey.of("key-alpha");
      RateLimitKey key2 = RateLimitKey.of("key-beta");

      // when / then
      // Note: Different keys might have same hashCode (hash collision), but it's very unlikely
      assertNotEquals(key1.hashCode(), key2.hashCode());
    }
  }

  // ==================== toString Tests ====================

  @Nested
  @DisplayName("toString Tests")
  class ToStringTests {

    @Test
    @DisplayName("toString should contain key value")
    void toString_shouldContainKeyValue() {
      // given
      String keyValue = "192.168.1.100";
      RateLimitKey key = RateLimitKey.of(keyValue);

      // when
      String result = key.toString();

      // then
      assertTrue(result.contains("RateLimitKey"));
      assertTrue(result.contains(keyValue));
    }

    @Test
    @DisplayName("toString should handle empty string key")
    void toString_shouldHandleEmptyStringKey() {
      // given
      RateLimitKey key = RateLimitKey.of("");

      // when
      String result = key.toString();

      // then
      assertNotNull(result);
      assertTrue(result.contains("RateLimitKey"));
    }
  }

  // ==================== value() Accessor Tests ====================

  @Nested
  @DisplayName("value() Accessor Tests")
  class ValueAccessorTests {

    @Test
    @DisplayName("value() should return same as key()")
    void value_shouldReturnSameAsKey() {
      // given
      String keyValue = "test-value";
      RateLimitKey key = RateLimitKey.of(keyValue);

      // when / then
      assertEquals(key.key(), key.value());
    }

    @Test
    @DisplayName("value() should return various key formats")
    void value_shouldReturnVariousKeyFormats() {
      // IP address format
      RateLimitKey ipKey = RateLimitKey.of("10.0.0.1");
      assertEquals("10.0.0.1", ipKey.value());

      // UUID format
      RateLimitKey uuidKey = RateLimitKey.of("550e8400-e29b-41d4-a716-446655440000");
      assertEquals("550e8400-e29b-41d4-a716-446655440000", uuidKey.value());

      // Composite format - separators survive, the path slashes are sanitised
      RateLimitKey compositeKey = RateLimitKey.of("user:123:endpoint:/api/v1/users");
      assertTrue(
          compositeKey.value().matches("h:user:123:endpoint:_api_v1_users:[0-9a-f]{16}"),
          compositeKey.value());
    }
  }

  // ==================== Use Case Tests ====================

  @Nested
  @DisplayName("Use Case Tests")
  class UseCaseTests {

    @Test
    @DisplayName("should work as HashMap key")
    void shouldWorkAsHashMapKey() {
      // given
      java.util.Map<RateLimitKey, Long> bucketCounts = new java.util.HashMap<>();
      RateLimitKey key1 = RateLimitKey.of("client-1");
      RateLimitKey key2 = RateLimitKey.of("client-2");
      RateLimitKey key1Duplicate = RateLimitKey.of("client-1");

      // when
      bucketCounts.put(key1, 100L);
      bucketCounts.put(key2, 200L);

      // then
      assertEquals(100L, bucketCounts.get(key1Duplicate));
      assertEquals(200L, bucketCounts.get(key2));
      assertEquals(2, bucketCounts.size());
    }

    @Test
    @DisplayName("should work in HashSet")
    void shouldWorkInHashSet() {
      // given
      java.util.Set<RateLimitKey> keys = new java.util.HashSet<>();

      // when
      keys.add(RateLimitKey.of("key-1"));
      keys.add(RateLimitKey.of("key-2"));
      keys.add(RateLimitKey.of("key-1")); // duplicate

      // then
      assertEquals(2, keys.size());
      assertTrue(keys.contains(RateLimitKey.of("key-1")));
      assertTrue(keys.contains(RateLimitKey.of("key-2")));
    }
  }

  // ==================== Sanitisation Tests ====================

  @Nested
  @DisplayName("Sanitisation Tests")
  class SanitisationTests {

    @Test
    @DisplayName("allowed characters should pass through unchanged")
    void of_shouldKeepAllowedCharacters() {
      // given / when / then
      assertEquals(
          "user:a-b_c.d@e:0123456789", RateLimitKey.of("user:a-b_c.d@e:0123456789").value());
    }

    @Test
    @DisplayName("disallowed characters should be replaced by underscores")
    void of_shouldReplaceDisallowedCharacters() {
      // given / when / then
      assertTrue(RateLimitKey.of("a*b?c[d]e\\f").value().matches("h:a_b_c_d_e_f:[0-9a-f]{16}"));
      assertTrue(RateLimitKey.of("ip 10.0.0.1").value().matches("h:ip_10.0.0.1:[0-9a-f]{16}"));
    }

    @Test
    @DisplayName("values longer than 256 characters should be replaced by a SHA-256 hex digest")
    void of_shouldHashOverLongValues() {
      // given
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 257; i++) {
        sb.append('a');
      }

      // when
      RateLimitKey key = RateLimitKey.of(sb.toString());

      // then - marked as a digest so that no clean 64-hex value can collide with it
      assertTrue(key.value().matches("h:[0-9a-f]{64}"), key.value());
    }

    @Test
    @DisplayName("values of exactly 256 characters should be kept")
    void of_shouldKeepValuesAtTheLengthCap() {
      // given
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 256; i++) {
        sb.append('a');
      }
      String value = sb.toString();

      // when / then
      assertEquals(value, RateLimitKey.of(value).value());
    }

    @Test
    @DisplayName("sanitisation should be deterministic")
    void of_shouldBeDeterministic() {
      // given
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 300; i++) {
        sb.append("x/");
      }

      // when / then - injective encoding cannot also be idempotent, but it is stable per input
      assertEquals(RateLimitKey.of(sb.toString()), RateLimitKey.of(sb.toString()));
      assertEquals(RateLimitKey.of("a+1"), RateLimitKey.of("a+1"));
      assertNotEquals(RateLimitKey.of("a+1"), RateLimitKey.of("a_1"));
    }
  }

  // ==================== Prefixed Factory Tests ====================

  @Nested
  @DisplayName("Prefixed Factory Tests")
  class PrefixedFactoryTests {

    private final LimitScopeKeyResolver resolver = new LimitScopeKeyResolver();

    private String resolveUser(String userId) {
      return resolver
          .resolve(
              org.fluxgate.core.context.RequestContext.builder().userId(userId).build(),
              org.fluxgate.core.config.RateLimitRule.builder("r")
                  .scope(org.fluxgate.core.config.LimitScope.PER_USER)
                  .addBand(
                      org.fluxgate.core.config.RateLimitBand.builder(
                              java.time.Duration.ofMinutes(1), 10)
                          .build())
                  .build())
          .value();
    }

    @Test
    @DisplayName("of(prefix, value) builds exactly the key the resolver builds")
    void of_prefixed_matchesResolver() {
      assertEquals(resolveUser("alice"), RateLimitKey.of("user:", "alice").value());
      assertEquals(resolveUser("a+1"), RateLimitKey.of("user:", "a+1").value());
      String longValue = "x".repeat(300);
      assertEquals(resolveUser(longValue), RateLimitKey.of("user:", longValue).value());
      assertTrue(RateLimitKey.of("user:", longValue).value().matches("user:h:[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("of(prefix, value) keeps the prefix outside the 256-character value limit")
    void of_prefixed_keepsPrefixOutsideTheLimit() {
      String value = "a".repeat(256);
      assertEquals("tenant-1:" + value, RateLimitKey.of("tenant-1:", value).value());
    }

    @Test
    @DisplayName("of(full) sanitises the whole string, prefix included")
    void of_full_sanitisesWholeString() {
      String key = RateLimitKey.of("user:a+1").value();
      assertTrue(key.startsWith("h:user:a_1:"), key);
      assertNotEquals(RateLimitKey.of("user:", "a+1").value(), key);
    }

    @Test
    @DisplayName("of(prefix, value) rejects a prefix with disallowed characters or null input")
    void of_prefixed_rejectsBadInput() {
      assertThrows(IllegalArgumentException.class, () -> RateLimitKey.of("us er:", "a"));
      assertThrows(IllegalArgumentException.class, () -> RateLimitKey.of("{tag}:", "a"));
      assertThrows(IllegalArgumentException.class, () -> RateLimitKey.of("h:", "a"));
      assertThrows(
          IllegalArgumentException.class, () -> RateLimitKey.of("p".repeat(65) + ":", "a"));
      assertThrows(NullPointerException.class, () -> RateLimitKey.of(null, "a"));
      assertThrows(NullPointerException.class, () -> RateLimitKey.of("user:", null));
    }

    @Test
    @DisplayName("of(prefix, value) requires a non-empty prefix ending with ':'")
    void of_prefixed_requiresColonTerminatedPrefix() {
      assertThrows(IllegalArgumentException.class, () -> RateLimitKey.of("", "a"));
      assertThrows(IllegalArgumentException.class, () -> RateLimitKey.of("h", "a"));
      assertThrows(IllegalArgumentException.class, () -> RateLimitKey.of("user", "a"));
      assertThrows(IllegalArgumentException.class, () -> RateLimitKey.of(":", "a"));
      assertEquals("tenant:a:", RateLimitKey.of("tenant:a:", "").value());
      assertEquals("denied:ip:1.2.3.4", RateLimitKey.of("denied:ip:", "1.2.3.4").value());
    }
  }

  @Nested
  @DisplayName("withPrefix Tests")
  class WithPrefixTests {

    @Test
    @DisplayName("withPrefix keeps an h:-marked key verbatim instead of encoding it again")
    void withPrefix_keepsEncodedKeyVerbatim() {
      RateLimitKey encoded = RateLimitKey.of("a+1");
      assertTrue(encoded.value().startsWith("h:a_1:"), encoded.value());

      assertEquals(
          "denied:" + encoded.value(), RateLimitKey.withPrefix("denied:", encoded).value());
    }

    @Test
    @DisplayName("withPrefix keeps the longest prefixed key verbatim")
    void withPrefix_keepsLongestPrefixedKeyVerbatim() {
      RateLimitKey longest = RateLimitKey.of("p".repeat(63) + ":", "c".repeat(256));
      assertEquals(320, longest.value().length());

      assertEquals(
          "denied:" + longest.value(), RateLimitKey.withPrefix("denied:", longest).value());
    }

    @Test
    @DisplayName("withPrefix keeps a nested prefixed key verbatim")
    void withPrefix_keepsNestedPrefixedKeyVerbatim() {
      RateLimitKey longest = RateLimitKey.of("p".repeat(63) + ":", "c".repeat(256));
      RateLimitKey nested = RateLimitKey.withPrefix("x:", longest);
      assertEquals(322, nested.value().length());

      assertEquals("denied:" + nested.value(), RateLimitKey.withPrefix("denied:", nested).value());
    }

    @Test
    @DisplayName("withPrefix keeps a nested key apart from the hash of its value")
    void withPrefix_nestedKeyDoesNotCollideWithHashedValue() {
      RateLimitKey longest = RateLimitKey.of("p".repeat(63) + ":", "c".repeat(256));
      RateLimitKey nested = RateLimitKey.withPrefix("x:", longest);
      RateLimitKey hashed = RateLimitKey.of(nested.value());
      assertEquals("h:", hashed.value().substring(0, 2));
      assertNotEquals(nested, hashed);

      assertNotEquals(
          RateLimitKey.withPrefix("denied:", nested), RateLimitKey.withPrefix("denied:", hashed));
    }

    @Test
    @DisplayName("withPrefix keeps distinct keys distinct")
    void withPrefix_isInjective() {
      RateLimitKey raw = RateLimitKey.of("a+1");
      RateLimitKey encodedLookalike = RateLimitKey.of(raw.value());
      assertNotEquals(raw, encodedLookalike);

      assertNotEquals(
          RateLimitKey.withPrefix("denied:", raw),
          RateLimitKey.withPrefix("denied:", encodedLookalike));
    }

    @Test
    @DisplayName("withPrefix validates the prefix like of(prefix, value)")
    void withPrefix_rejectsBadPrefix() {
      RateLimitKey key = RateLimitKey.of("user:", "a");
      assertThrows(IllegalArgumentException.class, () -> RateLimitKey.withPrefix("h:", key));
      assertThrows(IllegalArgumentException.class, () -> RateLimitKey.withPrefix("denied", key));
      assertThrows(NullPointerException.class, () -> RateLimitKey.withPrefix(null, key));
      assertThrows(NullPointerException.class, () -> RateLimitKey.withPrefix("denied:", null));
    }
  }
}
