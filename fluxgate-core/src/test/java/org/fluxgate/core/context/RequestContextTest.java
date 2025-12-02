package org.fluxgate.core.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RequestContext}.
 * <p>
 * RequestContext represents contextual information about the incoming request.
 * It allows custom KeyResolvers and strategies to determine which rate-limit bucket should be used.
 */
@DisplayName("RequestContext Tests")
class RequestContextTest {

    // ==================== Builder Tests ====================

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should create minimal valid context")
        void build_shouldCreateMinimalValidContext() {
            // given / when
            RequestContext context = RequestContext.builder().build();

            // then
            assertNotNull(context);
            assertNull(context.getClientIp());
            assertNull(context.getUserId());
            assertNull(context.getApiKey());
            assertNull(context.getEndpoint());
            assertNull(context.getMethod());
            assertNotNull(context.getAttributes());
            assertTrue(context.getAttributes().isEmpty());
        }

        @Test
        @DisplayName("should create fully populated context")
        void build_shouldCreateFullyPopulatedContext() {
            // given
            String clientIp = "192.168.1.100";
            String userId = "user-abc-123";
            String apiKey = "api-key-xyz";
            String endpoint = "/api/v1/users";
            String method = "POST";

            // when
            RequestContext context = RequestContext.builder()
                    .clientIp(clientIp)
                    .userId(userId)
                    .apiKey(apiKey)
                    .endpoint(endpoint)
                    .method(method)
                    .build();

            // then
            assertEquals(clientIp, context.getClientIp());
            assertEquals(userId, context.getUserId());
            assertEquals(apiKey, context.getApiKey());
            assertEquals(endpoint, context.getEndpoint());
            assertEquals(method, context.getMethod());
        }

        @Test
        @DisplayName("should allow partial context with only some fields")
        void build_shouldAllowPartialContext() {
            // given / when
            RequestContext context = RequestContext.builder()
                    .clientIp("10.0.0.1")
                    .endpoint("/health")
                    .build();

            // then
            assertEquals("10.0.0.1", context.getClientIp());
            assertEquals("/health", context.getEndpoint());
            assertNull(context.getUserId());
            assertNull(context.getApiKey());
            assertNull(context.getMethod());
        }
    }

    // ==================== Getter Tests ====================

    @Nested
    @DisplayName("Getter Tests")
    class GetterTests {

        @Test
        @DisplayName("getClientIp should return correct IP")
        void getClientIp_shouldReturnCorrectIp() {
            // given
            String expectedIp = "172.16.0.1";
            RequestContext context = RequestContext.builder()
                    .clientIp(expectedIp)
                    .build();

            // when / then
            assertEquals(expectedIp, context.getClientIp());
        }

        @Test
        @DisplayName("getUserId should return correct user ID")
        void getUserId_shouldReturnCorrectUserId() {
            // given
            String expectedUserId = "user-12345";
            RequestContext context = RequestContext.builder()
                    .userId(expectedUserId)
                    .build();

            // when / then
            assertEquals(expectedUserId, context.getUserId());
        }

        @Test
        @DisplayName("getApiKey should return correct API key")
        void getApiKey_shouldReturnCorrectApiKey() {
            // given
            String expectedApiKey = "sk-test-abc123";
            RequestContext context = RequestContext.builder()
                    .apiKey(expectedApiKey)
                    .build();

            // when / then
            assertEquals(expectedApiKey, context.getApiKey());
        }

        @Test
        @DisplayName("getEndpoint should return correct endpoint")
        void getEndpoint_shouldReturnCorrectEndpoint() {
            // given
            String expectedEndpoint = "/api/v2/products/123";
            RequestContext context = RequestContext.builder()
                    .endpoint(expectedEndpoint)
                    .build();

            // when / then
            assertEquals(expectedEndpoint, context.getEndpoint());
        }

        @Test
        @DisplayName("getMethod should return correct HTTP method")
        void getMethod_shouldReturnCorrectMethod() {
            // given / when / then - various HTTP methods
            assertEquals("GET", RequestContext.builder().method("GET").build().getMethod());
            assertEquals("POST", RequestContext.builder().method("POST").build().getMethod());
            assertEquals("PUT", RequestContext.builder().method("PUT").build().getMethod());
            assertEquals("DELETE", RequestContext.builder().method("DELETE").build().getMethod());
            assertEquals("PATCH", RequestContext.builder().method("PATCH").build().getMethod());
        }
    }

    // ==================== Attributes Tests ====================

    @Nested
    @DisplayName("Attributes Tests")
    class AttributesTests {

        @Test
        @DisplayName("should add single attribute")
        void attribute_shouldAddSingleAttribute() {
            // given / when
            RequestContext context = RequestContext.builder()
                    .attribute("tenantId", "tenant-123")
                    .build();

            // then
            assertEquals("tenant-123", context.getAttributes().get("tenantId"));
        }

        @Test
        @DisplayName("should add multiple attributes")
        void attribute_shouldAddMultipleAttributes() {
            // given / when
            RequestContext context = RequestContext.builder()
                    .attribute("tenantId", "tenant-123")
                    .attribute("region", "us-east-1")
                    .attribute("priority", 5)
                    .build();

            // then
            Map<String, Object> attrs = context.getAttributes();
            assertEquals(3, attrs.size());
            assertEquals("tenant-123", attrs.get("tenantId"));
            assertEquals("us-east-1", attrs.get("region"));
            assertEquals(5, attrs.get("priority"));
        }

        @Test
        @DisplayName("should allow various attribute value types")
        void attribute_shouldAllowVariousValueTypes() {
            // given / when
            RequestContext context = RequestContext.builder()
                    .attribute("stringAttr", "value")
                    .attribute("intAttr", 42)
                    .attribute("longAttr", 100L)
                    .attribute("boolAttr", true)
                    .attribute("doubleAttr", 3.14)
                    .build();

            // then
            Map<String, Object> attrs = context.getAttributes();
            assertEquals("value", attrs.get("stringAttr"));
            assertEquals(42, attrs.get("intAttr"));
            assertEquals(100L, attrs.get("longAttr"));
            assertEquals(true, attrs.get("boolAttr"));
            assertEquals(3.14, attrs.get("doubleAttr"));
        }

        @Test
        @DisplayName("should return empty map when no attributes set")
        void getAttributes_shouldReturnEmptyMapWhenNoAttributesSet() {
            // given / when
            RequestContext context = RequestContext.builder().build();

            // then
            assertNotNull(context.getAttributes());
            assertTrue(context.getAttributes().isEmpty());
        }
    }

    // ==================== Immutability Tests ====================

    @Nested
    @DisplayName("Immutability Tests")
    class ImmutabilityTests {

        @Test
        @DisplayName("attributes map should be unmodifiable")
        void getAttributes_shouldReturnUnmodifiableMap() {
            // given
            RequestContext context = RequestContext.builder()
                    .attribute("key", "value")
                    .build();

            // when
            Map<String, Object> attrs = context.getAttributes();

            // then
            assertThrows(UnsupportedOperationException.class, () ->
                    attrs.put("newKey", "newValue")
            );
        }

        @Test
        @DisplayName("attributes map should not allow removal")
        void getAttributes_shouldNotAllowRemoval() {
            // given
            RequestContext context = RequestContext.builder()
                    .attribute("key", "value")
                    .build();

            // when
            Map<String, Object> attrs = context.getAttributes();

            // then
            assertThrows(UnsupportedOperationException.class, () ->
                    attrs.remove("key")
            );
        }

        @Test
        @DisplayName("attributes map should not allow clear")
        void getAttributes_shouldNotAllowClear() {
            // given
            RequestContext context = RequestContext.builder()
                    .attribute("key", "value")
                    .build();

            // when
            Map<String, Object> attrs = context.getAttributes();

            // then
            assertThrows(UnsupportedOperationException.class, attrs::clear);
        }
    }

    // ==================== Use Case Tests ====================

    @Nested
    @DisplayName("Use Case Tests")
    class UseCaseTests {

        @Test
        @DisplayName("should support typical API request context")
        void shouldSupportTypicalApiRequestContext() {
            // given / when
            RequestContext context = RequestContext.builder()
                    .clientIp("203.0.113.50")
                    .userId("user-john-doe")
                    .apiKey("pk-live-123456")
                    .endpoint("/api/v1/payments")
                    .method("POST")
                    .attribute("requestId", "req-abc-123")
                    .attribute("correlationId", "corr-xyz-789")
                    .build();

            // then - all fields accessible for rate limiting decision
            assertNotNull(context.getClientIp());
            assertNotNull(context.getUserId());
            assertNotNull(context.getApiKey());
            assertNotNull(context.getEndpoint());
            assertNotNull(context.getMethod());
            assertEquals(2, context.getAttributes().size());
        }

        @Test
        @DisplayName("should support anonymous user request")
        void shouldSupportAnonymousUserRequest() {
            // given / when - no userId, no apiKey
            RequestContext context = RequestContext.builder()
                    .clientIp("198.51.100.25")
                    .endpoint("/public/health")
                    .method("GET")
                    .build();

            // then
            assertNotNull(context.getClientIp());
            assertNull(context.getUserId());
            assertNull(context.getApiKey());
        }

        @Test
        @DisplayName("should support multi-tenant context")
        void shouldSupportMultiTenantContext() {
            // given / when
            RequestContext context = RequestContext.builder()
                    .clientIp("10.0.0.1")
                    .userId("user-123")
                    .attribute("tenantId", "acme-corp")
                    .attribute("subscriptionTier", "premium")
                    .endpoint("/api/v1/data")
                    .method("GET")
                    .build();

            // then
            assertEquals("acme-corp", context.getAttributes().get("tenantId"));
            assertEquals("premium", context.getAttributes().get("subscriptionTier"));
        }
    }

    // ==================== Edge Case Tests ====================

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("should handle empty string values")
        void shouldHandleEmptyStringValues() {
            // given / when
            RequestContext context = RequestContext.builder()
                    .clientIp("")
                    .userId("")
                    .endpoint("")
                    .build();

            // then
            assertEquals("", context.getClientIp());
            assertEquals("", context.getUserId());
            assertEquals("", context.getEndpoint());
        }

        @Test
        @DisplayName("should handle special characters in values")
        void shouldHandleSpecialCharactersInValues() {
            // given / when
            RequestContext context = RequestContext.builder()
                    .endpoint("/api/v1/users?name=test&active=true")
                    .attribute("query", "SELECT * FROM users WHERE id = 1")
                    .build();

            // then
            assertEquals("/api/v1/users?name=test&active=true", context.getEndpoint());
            assertEquals("SELECT * FROM users WHERE id = 1", context.getAttributes().get("query"));
        }

        @Test
        @DisplayName("should handle long attribute values")
        void shouldHandleLongAttributeValues() {
            // given
            String longValue = "x".repeat(10000);

            // when
            RequestContext context = RequestContext.builder()
                    .attribute("longValue", longValue)
                    .build();

            // then
            assertEquals(longValue, context.getAttributes().get("longValue"));
        }
    }
}
