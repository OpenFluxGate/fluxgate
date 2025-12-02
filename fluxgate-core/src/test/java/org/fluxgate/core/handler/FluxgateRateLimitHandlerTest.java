package org.fluxgate.core.handler;

import org.fluxgate.core.context.RequestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FluxgateRateLimitHandler} interface.
 * <p>
 * FluxgateRateLimitHandler is the core interface for rate limit handling.
 * Implementations decide how to perform rate limiting:
 * - API-based: Call external FluxGate API server
 * - Redis direct: Use Redis rate limiter directly
 * - Standalone: In-memory rate limiting (for testing)
 */
@DisplayName("FluxgateRateLimitHandler Tests")
class FluxgateRateLimitHandlerTest {

    // ==================== ALLOW_ALL Handler Tests ====================

    @Nested
    @DisplayName("ALLOW_ALL Handler Tests")
    class AllowAllHandlerTests {

        @Test
        @DisplayName("ALLOW_ALL should always return allowed response")
        void allowAll_shouldAlwaysReturnAllowedResponse() {
            // given
            FluxgateRateLimitHandler handler = FluxgateRateLimitHandler.ALLOW_ALL;
            RequestContext context = RequestContext.builder()
                    .clientIp("192.168.1.1")
                    .endpoint("/api/test")
                    .method("GET")
                    .build();

            // when
            RateLimitResponse response = handler.tryConsume(context, "any-ruleset");

            // then
            assertTrue(response.isAllowed());
        }

        @Test
        @DisplayName("ALLOW_ALL should return -1 for remaining tokens (unknown)")
        void allowAll_shouldReturnMinusOneForRemainingTokens() {
            // given
            FluxgateRateLimitHandler handler = FluxgateRateLimitHandler.ALLOW_ALL;
            RequestContext context = RequestContext.builder().build();

            // when
            RateLimitResponse response = handler.tryConsume(context, "test-ruleset");

            // then
            assertEquals(-1, response.getRemainingTokens());
        }

        @Test
        @DisplayName("ALLOW_ALL should return 0 for retry after")
        void allowAll_shouldReturnZeroForRetryAfter() {
            // given
            FluxgateRateLimitHandler handler = FluxgateRateLimitHandler.ALLOW_ALL;
            RequestContext context = RequestContext.builder().build();

            // when
            RateLimitResponse response = handler.tryConsume(context, "test-ruleset");

            // then
            assertEquals(0, response.getRetryAfterMillis());
        }

        @Test
        @DisplayName("ALLOW_ALL should work with any context")
        void allowAll_shouldWorkWithAnyContext() {
            // given
            FluxgateRateLimitHandler handler = FluxgateRateLimitHandler.ALLOW_ALL;

            // Various contexts
            RequestContext minimalContext = RequestContext.builder().build();
            RequestContext fullContext = RequestContext.builder()
                    .clientIp("10.0.0.1")
                    .userId("user-123")
                    .apiKey("api-key")
                    .endpoint("/api/v1/users")
                    .method("POST")
                    .attribute("tenantId", "tenant-abc")
                    .build();

            // when / then
            assertTrue(handler.tryConsume(minimalContext, "ruleset-1").isAllowed());
            assertTrue(handler.tryConsume(fullContext, "ruleset-2").isAllowed());
        }

        @Test
        @DisplayName("ALLOW_ALL should work with any rule set ID")
        void allowAll_shouldWorkWithAnyRuleSetId() {
            // given
            FluxgateRateLimitHandler handler = FluxgateRateLimitHandler.ALLOW_ALL;
            RequestContext context = RequestContext.builder().build();

            // when / then
            assertTrue(handler.tryConsume(context, "").isAllowed());
            assertTrue(handler.tryConsume(context, "api-limits").isAllowed());
            assertTrue(handler.tryConsume(context, "user-rate-limits").isAllowed());
            assertTrue(handler.tryConsume(context, null).isAllowed());
        }
    }

    // ==================== Interface Contract Tests ====================

    @Nested
    @DisplayName("Interface Contract Tests")
    class InterfaceContractTests {

        @Test
        @DisplayName("should be functional interface")
        void shouldBeFunctionalInterface() {
            // FluxgateRateLimitHandler should work with lambda expressions
            // given
            FluxgateRateLimitHandler alwaysRejectHandler =
                    (context, ruleSetId) -> RateLimitResponse.rejected(5000);

            RequestContext context = RequestContext.builder()
                    .clientIp("10.0.0.1")
                    .build();

            // when
            RateLimitResponse response = alwaysRejectHandler.tryConsume(context, "test");

            // then
            assertFalse(response.isAllowed());
            assertEquals(5000, response.getRetryAfterMillis());
        }

        @Test
        @DisplayName("should support custom implementation")
        void shouldSupportCustomImplementation() {
            // given - custom counting handler
            final int[] counter = {0};
            FluxgateRateLimitHandler countingHandler = (context, ruleSetId) -> {
                counter[0]++;
                return counter[0] <= 3
                        ? RateLimitResponse.allowed(3 - counter[0], 0)
                        : RateLimitResponse.rejected(10000);
            };

            RequestContext context = RequestContext.builder().build();

            // when / then - first 3 requests allowed
            assertTrue(countingHandler.tryConsume(context, "test").isAllowed());
            assertTrue(countingHandler.tryConsume(context, "test").isAllowed());
            assertTrue(countingHandler.tryConsume(context, "test").isAllowed());

            // 4th request rejected
            RateLimitResponse response = countingHandler.tryConsume(context, "test");
            assertFalse(response.isAllowed());
            assertEquals(10000, response.getRetryAfterMillis());
        }

        @Test
        @DisplayName("should support context-aware implementation")
        void shouldSupportContextAwareImplementation() {
            // given - handler that checks context
            FluxgateRateLimitHandler contextAwareHandler = (context, ruleSetId) -> {
                // Allow VIP users
                if ("vip".equals(context.getAttributes().get("tier"))) {
                    return RateLimitResponse.allowed(1000, 0);
                }
                // Rate limit regular users
                return RateLimitResponse.rejected(30000);
            };

            RequestContext vipContext = RequestContext.builder()
                    .userId("vip-user")
                    .attribute("tier", "vip")
                    .build();

            RequestContext regularContext = RequestContext.builder()
                    .userId("regular-user")
                    .attribute("tier", "regular")
                    .build();

            // when / then
            assertTrue(contextAwareHandler.tryConsume(vipContext, "test").isAllowed());
            assertFalse(contextAwareHandler.tryConsume(regularContext, "test").isAllowed());
        }

        @Test
        @DisplayName("should support rule set aware implementation")
        void shouldSupportRuleSetAwareImplementation() {
            // given - handler that checks rule set ID
            FluxgateRateLimitHandler ruleSetAwareHandler = (context, ruleSetId) -> {
                return switch (ruleSetId) {
                    case "strict" -> RateLimitResponse.rejected(60000);
                    case "relaxed" -> RateLimitResponse.allowed(100, 0);
                    default -> RateLimitResponse.allowed(10, 0);
                };
            };

            RequestContext context = RequestContext.builder().build();

            // when / then
            assertFalse(ruleSetAwareHandler.tryConsume(context, "strict").isAllowed());
            assertTrue(ruleSetAwareHandler.tryConsume(context, "relaxed").isAllowed());
            assertEquals(100, ruleSetAwareHandler.tryConsume(context, "relaxed").getRemainingTokens());
            assertTrue(ruleSetAwareHandler.tryConsume(context, "default").isAllowed());
        }
    }

    // ==================== Edge Case Tests ====================

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("ALLOW_ALL should handle null context gracefully")
        void allowAll_shouldHandleNullContextGracefully() {
            // Note: ALLOW_ALL doesn't actually use the context, so null is safe
            // given
            FluxgateRateLimitHandler handler = FluxgateRateLimitHandler.ALLOW_ALL;

            // when
            RateLimitResponse response = handler.tryConsume(null, "test");

            // then
            assertTrue(response.isAllowed());
        }

        @Test
        @DisplayName("custom handler can throw exceptions")
        void customHandler_canThrowExceptions() {
            // given
            FluxgateRateLimitHandler failingHandler = (context, ruleSetId) -> {
                throw new RuntimeException("Rate limiter unavailable");
            };

            RequestContext context = RequestContext.builder().build();

            // when / then
            assertThrows(RuntimeException.class, () ->
                    failingHandler.tryConsume(context, "test")
            );
        }
    }
}
