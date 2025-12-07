package org.fluxgate.core.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OnLimitExceedPolicy} enum.
 *
 * <p>OnLimitExceedPolicy defines how the engine should behave when a limit is exceeded: -
 * REJECT_REQUEST: Immediately reject the request - WAIT_FOR_REFILL: Block the caller until enough
 * tokens are available
 */
@DisplayName("OnLimitExceedPolicy Enum Tests")
class OnLimitExceedPolicyTest {

  // ==================== valueOf / values() Sanity Tests ====================

  @Test
  @DisplayName("valueOf should return correct enum constant for valid names")
  void valueOf_shouldReturnCorrectEnumConstant() {
    // given / when / then
    assertEquals(OnLimitExceedPolicy.REJECT_REQUEST, OnLimitExceedPolicy.valueOf("REJECT_REQUEST"));
    assertEquals(
        OnLimitExceedPolicy.WAIT_FOR_REFILL, OnLimitExceedPolicy.valueOf("WAIT_FOR_REFILL"));
  }

  @Test
  @DisplayName("valueOf should throw IllegalArgumentException for invalid name")
  void valueOf_shouldThrowForInvalidName() {
    // given / when / then
    assertThrows(IllegalArgumentException.class, () -> OnLimitExceedPolicy.valueOf("INVALID"));
    assertThrows(
        IllegalArgumentException.class,
        () -> OnLimitExceedPolicy.valueOf("reject_request")); // case-sensitive
  }

  @Test
  @DisplayName("values should return all two enum constants")
  void values_shouldReturnAllConstants() {
    // given / when
    OnLimitExceedPolicy[] values = OnLimitExceedPolicy.values();

    // then
    assertEquals(2, values.length);
    assertArrayEquals(
        new OnLimitExceedPolicy[] {
          OnLimitExceedPolicy.REJECT_REQUEST, OnLimitExceedPolicy.WAIT_FOR_REFILL
        },
        values);
  }

  // ==================== Semantic Tests ====================

  @Test
  @DisplayName("REJECT_REQUEST should represent immediate rejection")
  void rejectRequest_shouldRepresentImmediateRejection() {
    // REJECT_REQUEST means the request should be immediately rejected
    // with an appropriate error response (e.g., HTTP 429 Too Many Requests)
    OnLimitExceedPolicy policy = OnLimitExceedPolicy.REJECT_REQUEST;
    assertEquals("REJECT_REQUEST", policy.name());
    assertEquals(0, policy.ordinal());
  }

  @Test
  @DisplayName("WAIT_FOR_REFILL should represent blocking until tokens available")
  void waitForRefill_shouldRepresentBlockingBehavior() {
    // WAIT_FOR_REFILL means the caller should be blocked until
    // enough tokens are available in the bucket
    // Note: Actual waiting logic is implemented at the server API level
    OnLimitExceedPolicy policy = OnLimitExceedPolicy.WAIT_FOR_REFILL;
    assertEquals("WAIT_FOR_REFILL", policy.name());
    assertEquals(1, policy.ordinal());
  }

  // ==================== Stability Tests ====================

  @Test
  @DisplayName("enum constants should be stable for serialization compatibility")
  void enumConstants_shouldBeStable() {
    // This test ensures ordinal values don't change unexpectedly
    // Important for serialization/deserialization compatibility
    assertEquals(0, OnLimitExceedPolicy.REJECT_REQUEST.ordinal());
    assertEquals(1, OnLimitExceedPolicy.WAIT_FOR_REFILL.ordinal());
  }
}
