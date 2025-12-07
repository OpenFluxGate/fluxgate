package org.fluxgate.core.handler;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RateLimitResponse}.
 *
 * <p>RateLimitResponse contains the result of a rate limit check including: - Whether the request
 * is allowed - Remaining tokens in the bucket - Time to wait before retry (if rejected)
 */
@DisplayName("RateLimitResponse Tests")
class RateLimitResponseTest {

  // ==================== Factory Method Tests ====================

  @Nested
  @DisplayName("Factory Method Tests")
  class FactoryMethodTests {

    @Test
    @DisplayName("allowed() should create allowed response with specified values")
    void allowed_shouldCreateAllowedResponse() {
      // given
      long remainingTokens = 99;
      long retryAfterMillis = 0;

      // when
      RateLimitResponse response = RateLimitResponse.allowed(remainingTokens, retryAfterMillis);

      // then
      assertTrue(response.isAllowed());
      assertEquals(remainingTokens, response.getRemainingTokens());
      assertEquals(retryAfterMillis, response.getRetryAfterMillis());
    }

    @Test
    @DisplayName("allowed() should allow -1 for unknown remaining tokens")
    void allowed_shouldAllowMinusOneForUnknownRemainingTokens() {
      // given / when
      RateLimitResponse response = RateLimitResponse.allowed(-1, 0);

      // then
      assertTrue(response.isAllowed());
      assertEquals(-1, response.getRemainingTokens());
    }

    @Test
    @DisplayName("allowed() should allow zero remaining tokens")
    void allowed_shouldAllowZeroRemainingTokens() {
      // given / when
      RateLimitResponse response = RateLimitResponse.allowed(0, 1000);

      // then
      assertTrue(response.isAllowed());
      assertEquals(0, response.getRemainingTokens());
    }

    @Test
    @DisplayName("rejected() should create rejected response")
    void rejected_shouldCreateRejectedResponse() {
      // given
      long retryAfterMillis = 60000; // 60 seconds

      // when
      RateLimitResponse response = RateLimitResponse.rejected(retryAfterMillis);

      // then
      assertFalse(response.isAllowed());
      assertEquals(0, response.getRemainingTokens());
      assertEquals(retryAfterMillis, response.getRetryAfterMillis());
    }

    @Test
    @DisplayName("rejected() should handle zero retry time")
    void rejected_shouldHandleZeroRetryTime() {
      // given / when
      RateLimitResponse response = RateLimitResponse.rejected(0);

      // then
      assertFalse(response.isAllowed());
      assertEquals(0, response.getRetryAfterMillis());
    }

    @Test
    @DisplayName("rejected() should handle large retry time")
    void rejected_shouldHandleLargeRetryTime() {
      // given
      long largeRetryTime = 86400000L; // 24 hours in milliseconds

      // when
      RateLimitResponse response = RateLimitResponse.rejected(largeRetryTime);

      // then
      assertEquals(largeRetryTime, response.getRetryAfterMillis());
    }
  }

  // ==================== Getter Tests ====================

  @Nested
  @DisplayName("Getter Tests")
  class GetterTests {

    @Test
    @DisplayName("isAllowed should return true for allowed response")
    void isAllowed_shouldReturnTrueForAllowedResponse() {
      // given / when
      RateLimitResponse response = RateLimitResponse.allowed(50, 0);

      // then
      assertTrue(response.isAllowed());
    }

    @Test
    @DisplayName("isAllowed should return false for rejected response")
    void isAllowed_shouldReturnFalseForRejectedResponse() {
      // given / when
      RateLimitResponse response = RateLimitResponse.rejected(1000);

      // then
      assertFalse(response.isAllowed());
    }

    @Test
    @DisplayName("getRemainingTokens should return correct value")
    void getRemainingTokens_shouldReturnCorrectValue() {
      // given
      long expectedTokens = 75;

      // when
      RateLimitResponse response = RateLimitResponse.allowed(expectedTokens, 0);

      // then
      assertEquals(expectedTokens, response.getRemainingTokens());
    }

    @Test
    @DisplayName("getRemainingTokens should return 0 for rejected response")
    void getRemainingTokens_shouldReturnZeroForRejectedResponse() {
      // given / when
      RateLimitResponse response = RateLimitResponse.rejected(5000);

      // then
      assertEquals(0, response.getRemainingTokens());
    }

    @Test
    @DisplayName("getRetryAfterMillis should return correct value")
    void getRetryAfterMillis_shouldReturnCorrectValue() {
      // given
      long expectedRetryAfter = 30000;

      // when
      RateLimitResponse response = RateLimitResponse.rejected(expectedRetryAfter);

      // then
      assertEquals(expectedRetryAfter, response.getRetryAfterMillis());
    }
  }

  // ==================== toString Tests ====================

  @Nested
  @DisplayName("toString Tests")
  class ToStringTests {

    @Test
    @DisplayName("toString should contain all fields for allowed response")
    void toString_shouldContainAllFieldsForAllowedResponse() {
      // given
      RateLimitResponse response = RateLimitResponse.allowed(99, 500);

      // when
      String result = response.toString();

      // then
      assertTrue(result.contains("RateLimitResponse"));
      assertTrue(result.contains("allowed=true"));
      assertTrue(result.contains("remainingTokens=99"));
      assertTrue(result.contains("retryAfterMillis=500"));
    }

    @Test
    @DisplayName("toString should contain all fields for rejected response")
    void toString_shouldContainAllFieldsForRejectedResponse() {
      // given
      RateLimitResponse response = RateLimitResponse.rejected(10000);

      // when
      String result = response.toString();

      // then
      assertTrue(result.contains("allowed=false"));
      assertTrue(result.contains("remainingTokens=0"));
      assertTrue(result.contains("retryAfterMillis=10000"));
    }
  }

  // ==================== Edge Case Tests ====================

  @Nested
  @DisplayName("Edge Case Tests")
  class EdgeCaseTests {

    @Test
    @DisplayName("should handle max long value for remaining tokens")
    void shouldHandleMaxLongValueForRemainingTokens() {
      // given / when
      RateLimitResponse response = RateLimitResponse.allowed(Long.MAX_VALUE, 0);

      // then
      assertEquals(Long.MAX_VALUE, response.getRemainingTokens());
    }

    @Test
    @DisplayName("should handle max long value for retry after")
    void shouldHandleMaxLongValueForRetryAfter() {
      // given / when
      RateLimitResponse response = RateLimitResponse.rejected(Long.MAX_VALUE);

      // then
      assertEquals(Long.MAX_VALUE, response.getRetryAfterMillis());
    }

    @Test
    @DisplayName("should allow negative retry after (edge case)")
    void shouldAllowNegativeRetryAfter() {
      // Note: Negative values might not be semantically correct but should be handled
      // given / when
      RateLimitResponse response = RateLimitResponse.allowed(50, -1);

      // then
      assertEquals(-1, response.getRetryAfterMillis());
    }
  }

  // ==================== Use Case Tests ====================

  @Nested
  @DisplayName("Use Case Tests")
  class UseCaseTests {

    @Test
    @DisplayName("should work in conditional flow for allowed response")
    void shouldWorkInConditionalFlowForAllowedResponse() {
      // given
      RateLimitResponse response = RateLimitResponse.allowed(5, 0);

      // when / then
      if (response.isAllowed()) {
        assertTrue(response.getRemainingTokens() >= 0);
      } else {
        fail("Response should be allowed");
      }
    }

    @Test
    @DisplayName("should work in conditional flow for rejected response")
    void shouldWorkInConditionalFlowForRejectedResponse() {
      // given
      RateLimitResponse response = RateLimitResponse.rejected(5000);

      // when / then
      if (!response.isAllowed()) {
        assertTrue(response.getRetryAfterMillis() > 0);
      } else {
        fail("Response should be rejected");
      }
    }

    @Test
    @DisplayName("should support rate limit header generation")
    void shouldSupportRateLimitHeaderGeneration() {
      // given
      RateLimitResponse response = RateLimitResponse.rejected(30000);

      // when - simulate HTTP Retry-After header (in seconds)
      long retryAfterSeconds = response.getRetryAfterMillis() / 1000;

      // then
      assertEquals(30, retryAfterSeconds);
    }
  }
}
