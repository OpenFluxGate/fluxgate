package org.fluxgate.redis.store;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BucketState}.
 *
 * <p>BucketState is an immutable class that represents the result of a token bucket consume
 * operation.
 */
@DisplayName("BucketState Tests")
class BucketStateTest {

  // ==================== Factory Method Tests ====================

  @Nested
  @DisplayName("Factory Method Tests")
  class FactoryMethodTests {

    @Test
    @DisplayName("allowed() should create allowed state")
    void allowed_shouldCreateAllowedState() {
      // given
      long remainingTokens = 99;
      long resetTimeMillis = System.currentTimeMillis() + 60000;

      // when
      BucketState state = BucketState.allowed(remainingTokens, resetTimeMillis);

      // then
      assertTrue(state.consumed());
      assertEquals(remainingTokens, state.remainingTokens());
      assertEquals(0, state.nanosToWaitForRefill());
      assertEquals(resetTimeMillis, state.resetTimeMillis());
    }

    @Test
    @DisplayName("rejected() should create rejected state")
    void rejected_shouldCreateRejectedState() {
      // given
      long remainingTokens = 0;
      long nanosToWait = 5_000_000_000L; // 5 seconds
      long resetTimeMillis = System.currentTimeMillis() + 5000;

      // when
      BucketState state = BucketState.rejected(remainingTokens, nanosToWait, resetTimeMillis);

      // then
      assertFalse(state.consumed());
      assertEquals(remainingTokens, state.remainingTokens());
      assertEquals(nanosToWait, state.nanosToWaitForRefill());
      assertEquals(resetTimeMillis, state.resetTimeMillis());
    }
  }

  // ==================== Accessor Tests ====================

  @Nested
  @DisplayName("Accessor Tests")
  class AccessorTests {

    @Test
    @DisplayName("consumed() should return true for allowed state")
    void consumed_shouldReturnTrueForAllowed() {
      // given / when
      BucketState state = BucketState.allowed(10, System.currentTimeMillis());

      // then
      assertTrue(state.consumed());
    }

    @Test
    @DisplayName("consumed() should return false for rejected state")
    void consumed_shouldReturnFalseForRejected() {
      // given / when
      BucketState state = BucketState.rejected(0, 1000000, System.currentTimeMillis());

      // then
      assertFalse(state.consumed());
    }

    @Test
    @DisplayName("remainingTokens() should return correct value")
    void remainingTokens_shouldReturnCorrectValue() {
      // given / when
      BucketState state = new BucketState(true, 42, 0, System.currentTimeMillis());

      // then
      assertEquals(42, state.remainingTokens());
    }

    @Test
    @DisplayName("nanosToWaitForRefill() should return correct value")
    void nanosToWaitForRefill_shouldReturnCorrectValue() {
      // given
      long expectedNanos = 3_500_000_000L;

      // when
      BucketState state = new BucketState(false, 0, expectedNanos, System.currentTimeMillis());

      // then
      assertEquals(expectedNanos, state.nanosToWaitForRefill());
    }

    @Test
    @DisplayName("resetTimeMillis() should return correct value")
    void resetTimeMillis_shouldReturnCorrectValue() {
      // given
      long expectedResetTime = System.currentTimeMillis() + 60000;

      // when
      BucketState state = new BucketState(true, 50, 0, expectedResetTime);

      // then
      assertEquals(expectedResetTime, state.resetTimeMillis());
    }
  }

  // ==================== getRetryAfterSeconds Tests ====================

  @Nested
  @DisplayName("getRetryAfterSeconds Tests")
  class GetRetryAfterSecondsTests {

    @Test
    @DisplayName("should return 0 for allowed state")
    void getRetryAfterSeconds_shouldReturnZeroForAllowed() {
      // given
      BucketState state = BucketState.allowed(100, System.currentTimeMillis());

      // when / then
      assertEquals(0, state.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("should return 1 second for 1 nano")
    void getRetryAfterSeconds_shouldRoundUpForSmallNanos() {
      // given: 1 nanosecond should round up to 1 second
      BucketState state = BucketState.rejected(0, 1, System.currentTimeMillis());

      // when / then
      assertEquals(1, state.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("should return 1 second for 999_999_999 nanos")
    void getRetryAfterSeconds_shouldRoundUpForAlmostOneSecond() {
      // given: just under 1 second should still be 1 second
      BucketState state = BucketState.rejected(0, 999_999_999L, System.currentTimeMillis());

      // when / then
      assertEquals(1, state.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("should return 1 second for exactly 1_000_000_000 nanos")
    void getRetryAfterSeconds_shouldReturnOneForExactlyOneSecond() {
      // given
      BucketState state = BucketState.rejected(0, 1_000_000_000L, System.currentTimeMillis());

      // when / then
      assertEquals(1, state.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("should return 2 seconds for 1_000_000_001 nanos")
    void getRetryAfterSeconds_shouldRoundUpToTwoSeconds() {
      // given: just over 1 second should round up to 2 seconds
      BucketState state = BucketState.rejected(0, 1_000_000_001L, System.currentTimeMillis());

      // when / then
      assertEquals(2, state.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("should return correct value for 5 seconds")
    void getRetryAfterSeconds_shouldReturnFiveSeconds() {
      // given
      BucketState state = BucketState.rejected(0, 5_000_000_000L, System.currentTimeMillis());

      // when / then
      assertEquals(5, state.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("should handle large nanos values")
    void getRetryAfterSeconds_shouldHandleLargeValues() {
      // given: 1 hour = 3600 seconds = 3_600_000_000_000 nanos
      long oneHourNanos = 3_600_000_000_000L;
      BucketState state = BucketState.rejected(0, oneHourNanos, System.currentTimeMillis());

      // when / then
      assertEquals(3600, state.getRetryAfterSeconds());
    }
  }

  // ==================== Edge Case Tests ====================

  @Nested
  @DisplayName("Edge Case Tests")
  class EdgeCaseTests {

    @Test
    @DisplayName("should handle zero remaining tokens")
    void shouldHandleZeroRemainingTokens() {
      // given / when
      BucketState state = BucketState.allowed(0, System.currentTimeMillis());

      // then
      assertEquals(0, state.remainingTokens());
      assertTrue(state.consumed());
    }

    @Test
    @DisplayName("should handle large remaining tokens")
    void shouldHandleLargeRemainingTokens() {
      // given / when
      BucketState state = BucketState.allowed(Long.MAX_VALUE, System.currentTimeMillis());

      // then
      assertEquals(Long.MAX_VALUE, state.remainingTokens());
    }

    @Test
    @DisplayName("should handle zero nanos to wait")
    void shouldHandleZeroNanosToWait() {
      // given / when
      BucketState state = BucketState.rejected(0, 0, System.currentTimeMillis());

      // then
      assertEquals(0, state.nanosToWaitForRefill());
      assertEquals(0, state.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("should support equality")
    void shouldSupportEquality() {
      // given
      long resetTime = System.currentTimeMillis();
      BucketState state1 = new BucketState(true, 50, 0, resetTime);
      BucketState state2 = new BucketState(true, 50, 0, resetTime);

      // when / then
      assertEquals(state1, state2);
      assertEquals(state1.hashCode(), state2.hashCode());
    }

    @Test
    @DisplayName("should support toString")
    void shouldSupportToString() {
      // given
      BucketState state = new BucketState(true, 100, 0, 1234567890L);

      // when
      String result = state.toString();

      // then
      assertTrue(result.contains("BucketState"));
      assertTrue(result.contains("true"));
      assertTrue(result.contains("100"));
    }
  }
}
