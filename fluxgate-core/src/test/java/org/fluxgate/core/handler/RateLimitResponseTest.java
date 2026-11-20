package org.fluxgate.core.handler;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitResult;
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

  // ==================== OnLimitExceedPolicy Tests ====================

  @Nested
  @DisplayName("OnLimitExceedPolicy Tests")
  class OnLimitExceedPolicyTests {

    @Test
    @DisplayName("rejected() without policy should default to REJECT_REQUEST")
    void rejected_shouldDefaultToRejectRequestPolicy() {
      // given / when
      RateLimitResponse response = RateLimitResponse.rejected(1000);

      // then
      assertEquals(OnLimitExceedPolicy.REJECT_REQUEST, response.getOnLimitExceedPolicy());
    }

    @Test
    @DisplayName("rejected() with WAIT_FOR_REFILL policy should set policy correctly")
    void rejected_shouldSetWaitForRefillPolicy() {
      // given / when
      RateLimitResponse response =
          RateLimitResponse.rejected(1000, OnLimitExceedPolicy.WAIT_FOR_REFILL);

      // then
      assertEquals(OnLimitExceedPolicy.WAIT_FOR_REFILL, response.getOnLimitExceedPolicy());
    }

    @Test
    @DisplayName("rejected() with REJECT_REQUEST policy should set policy correctly")
    void rejected_shouldSetRejectRequestPolicy() {
      // given / when
      RateLimitResponse response =
          RateLimitResponse.rejected(1000, OnLimitExceedPolicy.REJECT_REQUEST);

      // then
      assertEquals(OnLimitExceedPolicy.REJECT_REQUEST, response.getOnLimitExceedPolicy());
    }

    @Test
    @DisplayName("allowed() should have null policy")
    void allowed_shouldHaveNullPolicy() {
      // given / when
      RateLimitResponse response = RateLimitResponse.allowed(100, 0);

      // then
      assertNull(response.getOnLimitExceedPolicy());
    }
  }

  // ==================== shouldWaitForRefill Tests ====================

  @Nested
  @DisplayName("shouldWaitForRefill Tests")
  class ShouldWaitForRefillTests {

    @Test
    @DisplayName("shouldWaitForRefill() should return true for rejected with WAIT_FOR_REFILL")
    void shouldWaitForRefill_shouldReturnTrueForWaitPolicy() {
      // given / when
      RateLimitResponse response =
          RateLimitResponse.rejected(1000, OnLimitExceedPolicy.WAIT_FOR_REFILL);

      // then
      assertTrue(response.shouldWaitForRefill());
    }

    @Test
    @DisplayName("shouldWaitForRefill() should return false for rejected with REJECT_REQUEST")
    void shouldWaitForRefill_shouldReturnFalseForRejectPolicy() {
      // given / when
      RateLimitResponse response =
          RateLimitResponse.rejected(1000, OnLimitExceedPolicy.REJECT_REQUEST);

      // then
      assertFalse(response.shouldWaitForRefill());
    }

    @Test
    @DisplayName("shouldWaitForRefill() should return false for allowed response")
    void shouldWaitForRefill_shouldReturnFalseForAllowedResponse() {
      // given / when
      RateLimitResponse response = RateLimitResponse.allowed(100, 0);

      // then
      assertFalse(response.shouldWaitForRefill());
    }

    @Test
    @DisplayName("shouldWaitForRefill() should return false for rejected without policy (null)")
    void shouldWaitForRefill_shouldReturnFalseForNullPolicy() {
      // given / when - using the simple rejected() which defaults to REJECT_REQUEST
      RateLimitResponse response = RateLimitResponse.rejected(1000);

      // then
      assertFalse(response.shouldWaitForRefill());
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

  // ==================== from(RateLimitResult) Tests ====================

  @Nested
  @DisplayName("from(RateLimitResult) Tests")
  class FromResultTests {

    private RateLimitRule createRule(OnLimitExceedPolicy policy) {
      return RateLimitRule.builder("test-rule")
          .scope(LimitScope.PER_IP)
          .keyStrategyId("ip")
          .onLimitExceedPolicy(policy)
          .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).label("per-minute").build())
          .build();
    }

    @Test
    @DisplayName("from() should round nanoseconds up to milliseconds")
    void from_shouldRoundNanosUpToMillis() {
      // given - a single nanosecond must not collapse into Retry-After: 0
      RateLimitKey key = RateLimitKey.of("ip:10.0.0.1");
      RateLimitRule rule = createRule(OnLimitExceedPolicy.REJECT_REQUEST);

      // when / then
      assertEquals(
          1L,
          RateLimitResponse.from(RateLimitResult.rejected(key, rule, 1L)).getRetryAfterMillis());
      assertEquals(
          1L,
          RateLimitResponse.from(RateLimitResult.rejected(key, rule, 999_999L))
              .getRetryAfterMillis());
      assertEquals(
          1L,
          RateLimitResponse.from(RateLimitResult.rejected(key, rule, 1_000_000L))
              .getRetryAfterMillis());
      assertEquals(
          2L,
          RateLimitResponse.from(RateLimitResult.rejected(key, rule, 1_000_001L))
              .getRetryAfterMillis());
    }

    @Test
    @DisplayName("from() should keep 0 as 0 and -1 as -1")
    void from_shouldKeepZeroAndUnknown() {
      // given
      RateLimitKey key = RateLimitKey.of("ip:10.0.0.1");
      RateLimitRule rule = createRule(OnLimitExceedPolicy.REJECT_REQUEST);

      // when / then
      assertEquals(
          0L,
          RateLimitResponse.from(RateLimitResult.rejected(key, rule, 0L)).getRetryAfterMillis());
      assertEquals(
          -1L,
          RateLimitResponse.from(RateLimitResult.rejected(key, rule, -1L)).getRetryAfterMillis());
    }

    @Test
    @DisplayName("from() should carry policy, limit, reset and the real remaining tokens")
    void from_shouldCarryMetadata() {
      // given
      RateLimitKey key = RateLimitKey.of("ip:10.0.0.1");
      RateLimitRule rule = createRule(OnLimitExceedPolicy.WAIT_FOR_REFILL);
      RateLimitResult result =
          RateLimitResult.rejected(key, rule, 3L, 2_500_000L, 100L, 1_700_000_000_000L);

      // when
      RateLimitResponse response = RateLimitResponse.from(result);

      // then
      assertFalse(response.isAllowed());
      assertEquals(3L, response.getRemainingTokens());
      assertEquals(3L, response.getRetryAfterMillis());
      assertEquals(100L, response.getLimit());
      assertEquals(1_700_000_000_000L, response.getResetTimeMillis());
      assertEquals(OnLimitExceedPolicy.WAIT_FOR_REFILL, response.getOnLimitExceedPolicy());
      assertTrue(response.shouldWaitForRefill());
    }

    @Test
    @DisplayName("from() should reject a null result")
    void from_shouldRejectNullResult() {
      // given / when / then
      assertThrows(NullPointerException.class, () -> RateLimitResponse.from(null));
    }

    @Test
    @DisplayName("limit and resetTimeMillis should default to unknown")
    void factories_shouldDefaultLimitAndResetToUnknown() {
      // given / when / then
      assertEquals(-1L, RateLimitResponse.allowed(10, 0).getLimit());
      assertEquals(-1L, RateLimitResponse.allowed(10, 0).getResetTimeMillis());
      assertEquals(-1L, RateLimitResponse.rejected(1000).getLimit());
      assertEquals(-1L, RateLimitResponse.rejected(1000).getResetTimeMillis());
    }

    @Test
    @DisplayName("allowed() and rejected() overloads should carry limit and reset")
    void factories_shouldCarryLimitAndReset() {
      // given / when
      RateLimitResponse allowed = RateLimitResponse.allowed(9, 0, 10, 1_700_000_000_000L);
      RateLimitResponse rejected =
          RateLimitResponse.rejected(
              1000, OnLimitExceedPolicy.REJECT_REQUEST, 0, 10, 1_700_000_000_000L);

      // then
      assertEquals(10L, allowed.getLimit());
      assertEquals(1_700_000_000_000L, allowed.getResetTimeMillis());
      assertEquals(10L, rejected.getLimit());
      assertEquals(1_700_000_000_000L, rejected.getResetTimeMillis());
    }

    @Test
    @DisplayName("equals and hashCode should cover every field")
    void equals_shouldBeValueBased() {
      // given
      RateLimitResponse first = RateLimitResponse.allowed(9, 0, 10, 1_700_000_000_000L);
      RateLimitResponse second = RateLimitResponse.allowed(9, 0, 10, 1_700_000_000_000L);

      // when / then
      assertEquals(first, second);
      assertEquals(first.hashCode(), second.hashCode());
      assertNotEquals(first, RateLimitResponse.allowed(9, 0, 20, 1_700_000_000_000L));
      assertNotEquals(null, first);
      assertNotEquals(first, "not-a-response");
    }
  }

  // ==================== Window / Band Label Tests ====================

  @Nested
  @DisplayName("Window / Band Label Tests")
  class WindowAndBandLabelTests {

    private RateLimitRule multiBandRule() {
      return RateLimitRule.builder("test-rule")
          .scope(LimitScope.PER_IP)
          .keyStrategyId("ip")
          .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
          .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).label("per-minute").build())
          .addBand(RateLimitBand.builder(Duration.ofHours(1), 1000).label("per-hour").build())
          .build();
    }

    @Test
    @DisplayName("windowSeconds and bandLabel should default to unknown")
    void factories_shouldDefaultWindowAndBandLabelToUnknown() {
      // given / when / then
      assertEquals(-1L, RateLimitResponse.allowed(10, 0).getWindowSeconds());
      assertNull(RateLimitResponse.allowed(10, 0).getBandLabel());
      assertEquals(-1L, RateLimitResponse.rejected(1000).getWindowSeconds());
      assertNull(RateLimitResponse.rejected(1000).getBandLabel());
      assertEquals(-1L, RateLimitResponse.allowed(10, 0, 100, 1L).getWindowSeconds());
      assertEquals(
          -1L,
          RateLimitResponse.rejected(1000, OnLimitExceedPolicy.REJECT_REQUEST, 0, 100, 1L)
              .getWindowSeconds());
    }

    @Test
    @DisplayName("from() should derive the window of the band named by the result")
    void from_shouldDeriveWindowSecondsFromBandLabel() {
      // given - the hourly band rejected the request
      RateLimitResult result =
          RateLimitResult.builder(RateLimitKey.of("ip:10.0.0.1"))
              .allowed(false)
              .matchedRule(multiBandRule())
              .bandLabel("per-hour")
              .limit(1000)
              .nanosToWaitForRefill(1_000_000L)
              .build();

      // when
      RateLimitResponse response = RateLimitResponse.from(result);

      // then - yields RateLimit-Policy: 1000;w=3600
      assertEquals("per-hour", response.getBandLabel());
      assertEquals(3600L, response.getWindowSeconds());
      assertEquals(1000L, response.getLimit());
    }

    @Test
    @DisplayName("from() should pick the window of the per-minute band when that one binds")
    void from_shouldDeriveWindowSecondsForShorterBand() {
      // given
      RateLimitResult result =
          RateLimitResult.builder(RateLimitKey.of("ip:10.0.0.1"))
              .allowed(true)
              .matchedRule(multiBandRule())
              .bandLabel("per-minute")
              .limit(100)
              .nanosToWaitForRefill(0)
              .build();

      // when / then
      assertEquals(60L, RateLimitResponse.from(result).getWindowSeconds());
    }

    @Test
    @DisplayName("from() should match a derived key label on an unlabelled band")
    void from_shouldMatchDerivedKeyLabel() {
      // given - the band carries no label, so its key label is derived
      RateLimitRule rule =
          RateLimitRule.builder("test-rule")
              .scope(LimitScope.PER_IP)
              .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).build())
              .build();
      RateLimitResult result =
          RateLimitResult.builder(RateLimitKey.of("ip:10.0.0.1"))
              .allowed(true)
              .matchedRule(rule)
              .bandLabel("100-per-60s")
              .build();

      // when / then
      assertEquals(60L, RateLimitResponse.from(result).getWindowSeconds());
    }

    @Test
    @DisplayName("from() should round a sub-second window up to one second")
    void from_shouldRoundSubSecondWindowUpToOneSecond() {
      // given - a 500ms band; truncation reported w=0, which no client can act on
      RateLimitRule rule =
          RateLimitRule.builder("burst-rule")
              .scope(LimitScope.PER_IP)
              .addBand(RateLimitBand.builder(Duration.ofMillis(500), 10).build())
              .build();
      RateLimitResult result =
          RateLimitResult.builder(RateLimitKey.of("ip:10.0.0.1"))
              .allowed(false)
              .matchedRule(rule)
              .bandLabel("10-per-500ms")
              .limit(10)
              .build();

      // when / then
      assertEquals(1L, RateLimitResponse.from(result).getWindowSeconds());
    }

    @Test
    @DisplayName("from() should round a fractional-second window up")
    void from_shouldRoundFractionalSecondWindowUp() {
      // given - 1500ms reported w=1 before, which understated the window
      RateLimitRule rule =
          RateLimitRule.builder("burst-rule")
              .scope(LimitScope.PER_IP)
              .addBand(RateLimitBand.builder(Duration.ofMillis(1500), 10).build())
              .build();
      RateLimitResult result =
          RateLimitResult.builder(RateLimitKey.of("ip:10.0.0.1"))
              .allowed(true)
              .matchedRule(rule)
              .bandLabel("10-per-1500ms")
              .build();

      // when / then
      assertEquals(2L, RateLimitResponse.from(result).getWindowSeconds());
    }

    @Test
    @DisplayName("from() should report unknown window when the band label is absent")
    void from_shouldReportUnknownWindowWithoutBandLabel() {
      // given - 2b did not tag the binding band
      RateLimitResult result =
          RateLimitResult.builder(RateLimitKey.of("ip:10.0.0.1"))
              .allowed(true)
              .matchedRule(multiBandRule())
              .build();

      // when / then
      assertEquals(-1L, RateLimitResponse.from(result).getWindowSeconds());
      assertNull(RateLimitResponse.from(result).getBandLabel());
    }

    @Test
    @DisplayName("from() should report unknown window when the band label matches no band")
    void from_shouldReportUnknownWindowForUnmatchedLabel() {
      // given
      RateLimitResult result =
          RateLimitResult.builder(RateLimitKey.of("ip:10.0.0.1"))
              .allowed(true)
              .matchedRule(multiBandRule())
              .bandLabel("per-decade")
              .build();

      // when / then
      assertEquals(-1L, RateLimitResponse.from(result).getWindowSeconds());
      assertEquals("per-decade", RateLimitResponse.from(result).getBandLabel());
    }

    @Test
    @DisplayName("from() should report unknown window when no rule was matched")
    void from_shouldReportUnknownWindowWithoutRule() {
      // given / when / then
      assertEquals(
          -1L, RateLimitResponse.from(RateLimitResult.allowedWithoutRule()).getWindowSeconds());
    }
  }

  // ==================== Builder Tests ====================

  @Nested
  @DisplayName("Builder Tests")
  class BuilderTests {

    @Test
    @DisplayName("builder should default every unknown value to -1 or null")
    void builder_shouldDefaultToUnknown() {
      // given / when
      RateLimitResponse response = RateLimitResponse.builder().build();

      // then
      assertFalse(response.isAllowed());
      assertEquals(-1L, response.getRemainingTokens());
      assertEquals(-1L, response.getRetryAfterMillis());
      assertEquals(-1L, response.getLimit());
      assertEquals(-1L, response.getResetTimeMillis());
      assertEquals(-1L, response.getWindowSeconds());
      assertNull(response.getBandLabel());
      assertNull(response.getOnLimitExceedPolicy());
    }

    @Test
    @DisplayName("builder should set every field")
    void builder_shouldSetEveryField() {
      // given / when
      RateLimitResponse response =
          RateLimitResponse.builder()
              .allowed(true)
              .remainingTokens(9)
              .retryAfterMillis(0)
              .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
              .limit(10)
              .resetTimeMillis(1_700_000_000_000L)
              .windowSeconds(60)
              .bandLabel("per-minute")
              .build();

      // then
      assertTrue(response.isAllowed());
      assertEquals(9L, response.getRemainingTokens());
      assertEquals(0L, response.getRetryAfterMillis());
      assertEquals(OnLimitExceedPolicy.REJECT_REQUEST, response.getOnLimitExceedPolicy());
      assertEquals(10L, response.getLimit());
      assertEquals(1_700_000_000_000L, response.getResetTimeMillis());
      assertEquals(60L, response.getWindowSeconds());
      assertEquals("per-minute", response.getBandLabel());
    }

    @Test
    @DisplayName("equals, hashCode and toString should cover the new fields")
    void builder_equalsShouldCoverNewFields() {
      // given
      RateLimitResponse base =
          RateLimitResponse.builder()
              .allowed(true)
              .limit(10)
              .windowSeconds(60)
              .bandLabel("m")
              .build();
      RateLimitResponse same =
          RateLimitResponse.builder()
              .allowed(true)
              .limit(10)
              .windowSeconds(60)
              .bandLabel("m")
              .build();

      // when / then
      assertEquals(base, same);
      assertEquals(base.hashCode(), same.hashCode());
      assertNotEquals(
          base,
          RateLimitResponse.builder()
              .allowed(true)
              .limit(10)
              .windowSeconds(3600)
              .bandLabel("m")
              .build());
      assertNotEquals(
          base,
          RateLimitResponse.builder()
              .allowed(true)
              .limit(10)
              .windowSeconds(60)
              .bandLabel("h")
              .build());
      assertTrue(base.toString().contains("windowSeconds=60"));
      assertTrue(base.toString().contains("bandLabel='m'"));
    }
  }

  @Nested
  @DisplayName("Overflow-safe rounding")
  class OverflowTests {

    @Test
    @DisplayName("a huge wait does not overflow into a negative Retry-After")
    void hugeWaitStaysPositive() {
      RateLimitResult result =
          RateLimitResult.builder(RateLimitKey.of("ip:10.0.0.1"))
              .allowed(false)
              .nanosToWaitForRefill(Long.MAX_VALUE)
              .build();

      assertEquals(
          Long.MAX_VALUE / 1_000_000L + 1, RateLimitResponse.from(result).getRetryAfterMillis());
    }

    @Test
    @DisplayName("a huge band window does not overflow into a negative window")
    void hugeWindowStaysPositive() {
      RateLimitBand band =
          RateLimitBand.builder(Duration.ofMillis(Long.MAX_VALUE), 1).label("x").build();
      RateLimitRule rule = RateLimitRule.builder("r").addBand(band).build();
      RateLimitResult result =
          RateLimitResult.builder(RateLimitKey.of("ip:10.0.0.1"))
              .allowed(false)
              .matchedRule(rule)
              .bandLabel("x")
              .build();

      assertEquals(Long.MAX_VALUE / 1000L + 1, RateLimitResponse.from(result).getWindowSeconds());
    }
  }
}
