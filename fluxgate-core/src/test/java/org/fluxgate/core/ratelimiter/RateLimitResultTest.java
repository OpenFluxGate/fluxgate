package org.fluxgate.core.ratelimiter;

import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.RateLimitKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RateLimitResult}.
 * <p>
 * RateLimitResult represents the result of a single rate limit evaluation.
 */
@DisplayName("RateLimitResult Tests")
class RateLimitResultTest {

    // Helper methods
    private RateLimitKey createKey(String value) {
        return RateLimitKey.of(value);
    }

    private RateLimitRule createRule() {
        return RateLimitRule.builder("test-rule")
                .name("Test Rule")
                .scope(LimitScope.PER_IP)
                .keyStrategyId("ip")
                .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
                .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).build())
                .build();
    }

    // ==================== Factory Method Tests ====================

    @Nested
    @DisplayName("Factory Method Tests")
    class FactoryMethodTests {

        @Test
        @DisplayName("allowed() should create result with isAllowed=true")
        void allowed_shouldCreateAllowedResult() {
            // given
            RateLimitKey key = createKey("192.168.1.1");
            RateLimitRule rule = createRule();
            long remainingTokens = 99;
            long nanosToWait = 0;

            // when
            RateLimitResult result = RateLimitResult.allowed(key, rule, remainingTokens, nanosToWait);

            // then
            assertTrue(result.isAllowed());
            assertEquals(remainingTokens, result.getRemainingTokens());
            assertEquals(nanosToWait, result.getNanosToWaitForRefill());
            assertEquals(key, result.getKey());
            assertEquals(rule, result.getMatchedRule());
        }

        @Test
        @DisplayName("allowed() should accept various remaining token values")
        void allowed_shouldAcceptVariousRemainingTokenValues() {
            // given
            RateLimitKey key = createKey("test-key");
            RateLimitRule rule = createRule();

            // when / then - zero remaining
            RateLimitResult zeroResult = RateLimitResult.allowed(key, rule, 0, 1000);
            assertEquals(0, zeroResult.getRemainingTokens());
            assertTrue(zeroResult.isAllowed());

            // when / then - large remaining
            RateLimitResult largeResult = RateLimitResult.allowed(key, rule, Long.MAX_VALUE, 0);
            assertEquals(Long.MAX_VALUE, largeResult.getRemainingTokens());
        }

        @Test
        @DisplayName("rejected() should create result with isAllowed=false")
        void rejected_shouldCreateRejectedResult() {
            // given
            RateLimitKey key = createKey("192.168.1.1");
            RateLimitRule rule = createRule();
            long nanosToWait = 60_000_000_000L; // 60 seconds

            // when
            RateLimitResult result = RateLimitResult.rejected(key, rule, nanosToWait);

            // then
            assertFalse(result.isAllowed());
            assertEquals(0L, result.getRemainingTokens());
            assertEquals(nanosToWait, result.getNanosToWaitForRefill());
            assertEquals(key, result.getKey());
            assertEquals(rule, result.getMatchedRule());
        }

        @Test
        @DisplayName("allowedWithoutRule() should create unlimited result")
        void allowedWithoutRule_shouldCreateUnlimitedResult() {
            // when
            RateLimitResult result = RateLimitResult.allowedWithoutRule();

            // then
            assertTrue(result.isAllowed());
            assertNull(result.getMatchedRule());
            assertNull(result.getKey());
            assertEquals(Long.MAX_VALUE, result.getRemainingTokens());
            assertEquals(0, result.getNanosToWaitForRefill());
        }
    }

    // ==================== Builder Tests ====================

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("builder should create result with all fields")
        void builder_shouldCreateResultWithAllFields() {
            // given
            RateLimitKey key = createKey("user-123");
            RateLimitRule rule = createRule();
            boolean allowed = true;
            long remainingTokens = 50;
            long nanosToWait = 500_000_000L;

            // when
            RateLimitResult result = RateLimitResult.builder(key)
                    .allowed(allowed)
                    .remainingTokens(remainingTokens)
                    .nanosToWaitForRefill(nanosToWait)
                    .matchedRule(rule)
                    .build();

            // then
            assertEquals(allowed, result.isAllowed());
            assertEquals(key, result.getKey());
            assertEquals(rule, result.getMatchedRule());
            assertEquals(remainingTokens, result.getRemainingTokens());
            assertEquals(nanosToWait, result.getNanosToWaitForRefill());
        }

        @Test
        @DisplayName("builder should throw IllegalStateException for rejected result with null key")
        void builder_shouldThrowWhenRejectedWithNullKey() {
            // given / when / then
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> RateLimitResult.builder(null)
                            .allowed(false)
                            .build()
            );
            assertTrue(exception.getMessage().contains("key must not be null for rejected result"));
        }

        @Test
        @DisplayName("builder should allow null key for allowed result")
        void builder_shouldAllowNullKeyForAllowedResult() {
            // given / when
            RateLimitResult result = RateLimitResult.builder(null)
                    .allowed(true)
                    .remainingTokens(100)
                    .build();

            // then
            assertTrue(result.isAllowed());
            assertNull(result.getKey());
        }

        @Test
        @DisplayName("builder should use default values")
        void builder_shouldUseDefaultValues() {
            // given / when
            RateLimitResult result = RateLimitResult.builder(createKey("test"))
                    .allowed(true)
                    .build();

            // then
            assertEquals(-1L, result.getRemainingTokens()); // default
            assertEquals(-1L, result.getNanosToWaitForRefill()); // default
            assertNull(result.getMatchedRule()); // default null
        }
    }

    // ==================== Getter Tests ====================

    @Nested
    @DisplayName("Getter Tests")
    class GetterTests {

        @Test
        @DisplayName("isAllowed should return correct boolean value")
        void isAllowed_shouldReturnCorrectValue() {
            // given
            RateLimitKey key = createKey("test");

            // when / then - allowed
            RateLimitResult allowedResult = RateLimitResult.builder(key)
                    .allowed(true)
                    .build();
            assertTrue(allowedResult.isAllowed());

            // when / then - rejected
            RateLimitResult rejectedResult = RateLimitResult.builder(key)
                    .allowed(false)
                    .build();
            assertFalse(rejectedResult.isAllowed());
        }

        @Test
        @DisplayName("getRemainingTokens should return -1 when unknown")
        void getRemainingTokens_shouldReturnMinusOneWhenUnknown() {
            // given / when
            RateLimitResult result = RateLimitResult.builder(createKey("test"))
                    .allowed(true)
                    .build();

            // then
            assertEquals(-1L, result.getRemainingTokens());
        }

        @Test
        @DisplayName("getNanosToWaitForRefill should return -1 when unknown")
        void getNanosToWaitForRefill_shouldReturnMinusOneWhenUnknown() {
            // given / when
            RateLimitResult result = RateLimitResult.builder(createKey("test"))
                    .allowed(true)
                    .build();

            // then
            assertEquals(-1L, result.getNanosToWaitForRefill());
        }

        @Test
        @DisplayName("getNanosToWaitForRefill should return 0 when immediately reusable")
        void getNanosToWaitForRefill_shouldReturnZeroWhenImmediatelyReusable() {
            // given / when
            RateLimitResult result = RateLimitResult.allowed(createKey("test"), createRule(), 99, 0);

            // then
            assertEquals(0L, result.getNanosToWaitForRefill());
        }

        @Test
        @DisplayName("getMatchedRule should return null when not applicable")
        void getMatchedRule_shouldReturnNullWhenNotApplicable() {
            // given / when
            RateLimitResult result = RateLimitResult.allowedWithoutRule();

            // then
            assertNull(result.getMatchedRule());
        }
    }

    // ==================== toString Tests ====================

    @Nested
    @DisplayName("toString Tests")
    class ToStringTests {

        @Test
        @DisplayName("toString should contain key fields")
        void toString_shouldContainKeyFields() {
            // given
            RateLimitKey key = createKey("192.168.1.100");
            RateLimitResult result = RateLimitResult.builder(key)
                    .allowed(true)
                    .remainingTokens(75)
                    .nanosToWaitForRefill(1000)
                    .build();

            // when
            String str = result.toString();

            // then
            assertTrue(str.contains("RateLimitResult"));
            assertTrue(str.contains("allowed=true"));
            assertTrue(str.contains("remainingTokens=75"));
            assertTrue(str.contains("key="));
        }

        @Test
        @DisplayName("toString should handle null matchedRule")
        void toString_shouldHandleNullMatchedRule() {
            // given
            RateLimitResult result = RateLimitResult.allowedWithoutRule();

            // when
            String str = result.toString();

            // then
            assertTrue(str.contains("matchedRule=null"));
        }
    }

    // ==================== Edge Case Tests ====================

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("should handle very large nanosToWait values")
        void shouldHandleLargeNanosToWaitValues() {
            // given
            long largeNanos = Long.MAX_VALUE;

            // when
            RateLimitResult result = RateLimitResult.rejected(
                    createKey("test"),
                    createRule(),
                    largeNanos
            );

            // then
            assertEquals(largeNanos, result.getNanosToWaitForRefill());
        }

        @Test
        @DisplayName("should handle zero nanosToWait for rejected result")
        void shouldHandleZeroNanosToWaitForRejected() {
            // This is an edge case where bucket is empty but will refill immediately
            // given / when
            RateLimitResult result = RateLimitResult.rejected(
                    createKey("test"),
                    createRule(),
                    0L
            );

            // then
            assertFalse(result.isAllowed());
            assertEquals(0L, result.getNanosToWaitForRefill());
        }
    }
}
