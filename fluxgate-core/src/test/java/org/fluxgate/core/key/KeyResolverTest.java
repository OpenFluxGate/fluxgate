package org.fluxgate.core.key;

import org.fluxgate.core.context.RequestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link KeyResolver} interface.
 * <p>
 * KeyResolver is a functional interface that resolves a RateLimitKey from a RequestContext.
 * Tests include various implementation strategies.
 */
@DisplayName("KeyResolver Tests")
class KeyResolverTest {

    // ==================== Basic Contract Tests ====================

    @Nested
    @DisplayName("Basic Contract Tests")
    class BasicContractTests {

        @Test
        @DisplayName("should resolve key from context")
        void resolve_shouldResolveKeyFromContext() {
            // given
            KeyResolver resolver = context -> RateLimitKey.of(context.getClientIp());
            RequestContext context = RequestContext.builder()
                    .clientIp("192.168.1.1")
                    .build();

            // when
            RateLimitKey key = resolver.resolve(context);

            // then
            assertNotNull(key);
            assertEquals("192.168.1.1", key.value());
        }

        @Test
        @DisplayName("should be functional interface")
        void shouldBeFunctionalInterface() {
            // KeyResolver should work with lambda expressions
            // given
            KeyResolver ipResolver = ctx -> RateLimitKey.of(ctx.getClientIp());
            KeyResolver userResolver = ctx -> RateLimitKey.of(ctx.getUserId());
            KeyResolver apiKeyResolver = ctx -> RateLimitKey.of(ctx.getApiKey());

            RequestContext context = RequestContext.builder()
                    .clientIp("10.0.0.1")
                    .userId("user-123")
                    .apiKey("api-key-456")
                    .build();

            // when / then
            assertEquals("10.0.0.1", ipResolver.resolve(context).value());
            assertEquals("user-123", userResolver.resolve(context).value());
            assertEquals("api-key-456", apiKeyResolver.resolve(context).value());
        }
    }

    // ==================== IP-based Resolver Tests ====================

    @Nested
    @DisplayName("IP-based Resolver Tests")
    class IpBasedResolverTests {

        private final KeyResolver ipResolver = context -> RateLimitKey.of(context.getClientIp());

        @Test
        @DisplayName("should resolve IPv4 address")
        void resolve_shouldResolveIpv4Address() {
            // given
            RequestContext context = RequestContext.builder()
                    .clientIp("192.168.1.100")
                    .build();

            // when
            RateLimitKey key = ipResolver.resolve(context);

            // then
            assertEquals("192.168.1.100", key.value());
        }

        @Test
        @DisplayName("should resolve IPv6 address")
        void resolve_shouldResolveIpv6Address() {
            // given
            RequestContext context = RequestContext.builder()
                    .clientIp("2001:0db8:85a3:0000:0000:8a2e:0370:7334")
                    .build();

            // when
            RateLimitKey key = ipResolver.resolve(context);

            // then
            assertEquals("2001:0db8:85a3:0000:0000:8a2e:0370:7334", key.value());
        }

        @Test
        @DisplayName("should handle null client IP")
        void resolve_shouldHandleNullClientIp() {
            // given
            RequestContext context = RequestContext.builder()
                    .endpoint("/api/test")
                    .build();

            // when
            RateLimitKey key = ipResolver.resolve(context);

            // then
            assertNull(key.value());
        }
    }

    // ==================== User-based Resolver Tests ====================

    @Nested
    @DisplayName("User-based Resolver Tests")
    class UserBasedResolverTests {

        private final KeyResolver userResolver = context -> RateLimitKey.of(context.getUserId());

        @Test
        @DisplayName("should resolve user ID")
        void resolve_shouldResolveUserId() {
            // given
            RequestContext context = RequestContext.builder()
                    .userId("user-abc-123")
                    .build();

            // when
            RateLimitKey key = userResolver.resolve(context);

            // then
            assertEquals("user-abc-123", key.value());
        }

        @Test
        @DisplayName("should handle null user ID")
        void resolve_shouldHandleNullUserId() {
            // given
            RequestContext context = RequestContext.builder()
                    .clientIp("10.0.0.1")
                    .build();

            // when
            RateLimitKey key = userResolver.resolve(context);

            // then
            assertNull(key.value());
        }
    }

    // ==================== API Key Resolver Tests ====================

    @Nested
    @DisplayName("API Key Resolver Tests")
    class ApiKeyResolverTests {

        private final KeyResolver apiKeyResolver = context -> RateLimitKey.of(context.getApiKey());

        @Test
        @DisplayName("should resolve API key")
        void resolve_shouldResolveApiKey() {
            // given
            RequestContext context = RequestContext.builder()
                    .apiKey("sk-live-abc123xyz")
                    .build();

            // when
            RateLimitKey key = apiKeyResolver.resolve(context);

            // then
            assertEquals("sk-live-abc123xyz", key.value());
        }
    }

    // ==================== Composite Key Resolver Tests ====================

    @Nested
    @DisplayName("Composite Key Resolver Tests")
    class CompositeKeyResolverTests {

        @Test
        @DisplayName("should create composite key from multiple fields")
        void resolve_shouldCreateCompositeKey() {
            // given - composite key: user + endpoint
            KeyResolver compositeResolver = context ->
                    RateLimitKey.of(context.getUserId() + ":" + context.getEndpoint());

            RequestContext context = RequestContext.builder()
                    .userId("user-100")
                    .endpoint("/api/orders")
                    .build();

            // when
            RateLimitKey key = compositeResolver.resolve(context);

            // then
            assertEquals("user-100:/api/orders", key.value());
        }

        @Test
        @DisplayName("should create key with multiple segments")
        void resolve_shouldCreateKeyWithMultipleSegments() {
            // given - composite key: ip + user + method
            KeyResolver multiResolver = context ->
                    RateLimitKey.of(String.format("%s:%s:%s",
                            context.getClientIp(),
                            context.getUserId(),
                            context.getMethod()));

            RequestContext context = RequestContext.builder()
                    .clientIp("10.0.0.1")
                    .userId("admin")
                    .method("POST")
                    .build();

            // when
            RateLimitKey key = multiResolver.resolve(context);

            // then
            assertEquals("10.0.0.1:admin:POST", key.value());
        }
    }

    // ==================== Global Key Resolver Tests ====================

    @Nested
    @DisplayName("Global Key Resolver Tests")
    class GlobalKeyResolverTests {

        @Test
        @DisplayName("should return constant key for global scope")
        void resolve_shouldReturnConstantKeyForGlobalScope() {
            // given - global scope uses a constant key
            KeyResolver globalResolver = context -> RateLimitKey.of("GLOBAL");

            RequestContext context1 = RequestContext.builder()
                    .clientIp("192.168.1.1")
                    .userId("user-1")
                    .build();

            RequestContext context2 = RequestContext.builder()
                    .clientIp("10.0.0.1")
                    .userId("user-2")
                    .build();

            // when
            RateLimitKey key1 = globalResolver.resolve(context1);
            RateLimitKey key2 = globalResolver.resolve(context2);

            // then
            assertEquals(key1, key2);
            assertEquals("GLOBAL", key1.value());
        }
    }

    // ==================== Custom Attribute Resolver Tests ====================

    @Nested
    @DisplayName("Custom Attribute Resolver Tests")
    class CustomAttributeResolverTests {

        @Test
        @DisplayName("should resolve from custom attribute")
        void resolve_shouldResolveFromCustomAttribute() {
            // given
            KeyResolver tenantResolver = context -> {
                Object tenantId = context.getAttributes().get("tenantId");
                return RateLimitKey.of(tenantId != null ? tenantId.toString() : null);
            };

            RequestContext context = RequestContext.builder()
                    .attribute("tenantId", "tenant-xyz")
                    .build();

            // when
            RateLimitKey key = tenantResolver.resolve(context);

            // then
            assertEquals("tenant-xyz", key.value());
        }

        @Test
        @DisplayName("should handle missing custom attribute")
        void resolve_shouldHandleMissingCustomAttribute() {
            // given
            KeyResolver tenantResolver = context -> {
                Object tenantId = context.getAttributes().get("tenantId");
                return RateLimitKey.of(tenantId != null ? tenantId.toString() : "default");
            };

            RequestContext context = RequestContext.builder()
                    .clientIp("10.0.0.1")
                    .build();

            // when
            RateLimitKey key = tenantResolver.resolve(context);

            // then
            assertEquals("default", key.value());
        }
    }

    // ==================== Fallback Strategy Tests ====================

    @Nested
    @DisplayName("Fallback Strategy Tests")
    class FallbackStrategyTests {

        @Test
        @DisplayName("should use fallback when primary key is null")
        void resolve_shouldUseFallbackWhenPrimaryKeyIsNull() {
            // given - prefer userId, fallback to clientIp
            KeyResolver fallbackResolver = context -> {
                if (context.getUserId() != null) {
                    return RateLimitKey.of("user:" + context.getUserId());
                }
                return RateLimitKey.of("ip:" + context.getClientIp());
            };

            RequestContext withUser = RequestContext.builder()
                    .userId("user-123")
                    .clientIp("10.0.0.1")
                    .build();

            RequestContext withoutUser = RequestContext.builder()
                    .clientIp("10.0.0.2")
                    .build();

            // when / then
            assertEquals("user:user-123", fallbackResolver.resolve(withUser).value());
            assertEquals("ip:10.0.0.2", fallbackResolver.resolve(withoutUser).value());
        }
    }
}
