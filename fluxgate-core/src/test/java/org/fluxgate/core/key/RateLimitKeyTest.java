package org.fluxgate.core.key;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RateLimitKey}.
 * <p>
 * RateLimitKey is a record that wraps a string key used for rate limit bucket identification.
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
        @DisplayName("of() should allow null key value")
        void of_shouldAllowNullKeyValue() {
            // Records allow null values by default
            // given / when
            RateLimitKey key = RateLimitKey.of(null);

            // then
            assertNotNull(key);
            assertNull(key.key());
            assertNull(key.value());
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
        @DisplayName("equals should handle null key values")
        void equals_shouldHandleNullKeyValues() {
            // given
            RateLimitKey key1 = RateLimitKey.of(null);
            RateLimitKey key2 = RateLimitKey.of(null);
            RateLimitKey key3 = RateLimitKey.of("not-null");

            // when / then
            assertEquals(key1, key2);
            assertNotEquals(key1, key3);
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
        @DisplayName("toString should handle null key")
        void toString_shouldHandleNullKey() {
            // given
            RateLimitKey key = RateLimitKey.of(null);

            // when
            String result = key.toString();

            // then
            assertNotNull(result);
            assertTrue(result.contains("null"));
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

            // Composite format
            RateLimitKey compositeKey = RateLimitKey.of("user:123:endpoint:/api/v1/users");
            assertEquals("user:123:endpoint:/api/v1/users", compositeKey.value());
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
}
