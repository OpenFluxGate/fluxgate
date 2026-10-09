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

    @Test
    @DisplayName("allowed() should default limit and band index to unknown")
    void allowed_shouldDefaultBindingBandToUnknown() {
      // when
      BucketState state = BucketState.allowed(7, 1_000L);

      // then
      assertEquals(-1L, state.limit());
      assertEquals(BucketState.UNKNOWN_BAND_INDEX, state.bandIndex());
    }

    @Test
    @DisplayName("allowed() should carry the binding band's capacity and index")
    void allowed_shouldCarryBindingBand() {
      // when
      BucketState state = BucketState.allowed(7, 1_000L, 100L, 2);

      // then
      assertTrue(state.consumed());
      assertEquals(100L, state.limit());
      assertEquals(2, state.bandIndex());
    }

    @Test
    @DisplayName("rejected() should carry the rejecting band's capacity and index")
    void rejected_shouldCarryRejectingBand() {
      // when
      BucketState state = BucketState.rejected(0, 5_000L, 1_000L, 10L, 1);

      // then
      assertFalse(state.consumed());
      assertEquals(10L, state.limit());
      assertEquals(1, state.bandIndex());
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
    @DisplayName("equals should return true for same instance")
    void equalsShouldReturnTrueForSameInstance() {
      // given
      BucketState state = new BucketState(true, 50, 0, 12345L);

      // when / then
      assertEquals(state, state);
    }

    @Test
    @DisplayName("equals should return false for null")
    void equalsShouldReturnFalseForNull() {
      // given
      BucketState state = new BucketState(true, 50, 0, 12345L);

      // when / then
      assertNotEquals(state, null);
    }

    @Test
    @DisplayName("equals should return false for different type")
    void equalsShouldReturnFalseForDifferentType() {
      // given
      BucketState state = new BucketState(true, 50, 0, 12345L);

      // when / then
      assertNotEquals(state, "not a BucketState");
    }

    @Test
    @DisplayName("equals should return false for different consumed")
    void equalsShouldReturnFalseForDifferentConsumed() {
      // given
      BucketState state1 = new BucketState(true, 50, 0, 12345L);
      BucketState state2 = new BucketState(false, 50, 0, 12345L);

      // when / then
      assertNotEquals(state1, state2);
    }

    @Test
    @DisplayName("equals should return false for different remainingTokens")
    void equalsShouldReturnFalseForDifferentRemainingTokens() {
      // given
      BucketState state1 = new BucketState(true, 50, 0, 12345L);
      BucketState state2 = new BucketState(true, 100, 0, 12345L);

      // when / then
      assertNotEquals(state1, state2);
    }

    @Test
    @DisplayName("equals should return false for different nanosToWaitForRefill")
    void equalsShouldReturnFalseForDifferentNanosToWait() {
      // given
      BucketState state1 = new BucketState(false, 0, 1000L, 12345L);
      BucketState state2 = new BucketState(false, 0, 2000L, 12345L);

      // when / then
      assertNotEquals(state1, state2);
    }

    @Test
    @DisplayName("equals should return false for different resetTimeMillis")
    void equalsShouldReturnFalseForDifferentResetTime() {
      // given
      BucketState state1 = new BucketState(true, 50, 0, 12345L);
      BucketState state2 = new BucketState(true, 50, 0, 67890L);

      // when / then
      assertNotEquals(state1, state2);
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
