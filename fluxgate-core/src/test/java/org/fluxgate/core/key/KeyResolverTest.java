package org.fluxgate.core.key;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KeyResolver} interface and {@link LimitScopeKeyResolver} implementation.
 *
 * <p>KeyResolver resolves a RateLimitKey from a RequestContext and RateLimitRule. The key is
 * determined by the rule's LimitScope.
 */
@DisplayName("KeyResolver Tests")
class KeyResolverTest {

  private final KeyResolver resolver = new LimitScopeKeyResolver();

  // Helper method to create a rule with specified scope
  private RateLimitRule createRule(String id, LimitScope scope) {
    return RateLimitRule.builder(id)
        .name("Test Rule")
        .enabled(true)
        .scope(scope)
        .ruleSetId("test-rules")
        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).label("per-minute").build())
        .build();
  }

  private RateLimitRule createCustomRule(String id, String keyStrategyId) {
    return RateLimitRule.builder(id)
        .name("Custom Rule")
        .enabled(true)
        .scope(LimitScope.CUSTOM)
        .keyStrategyId(keyStrategyId)
        .ruleSetId("test-rules")
        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).label("per-minute").build())
        .build();
  }

  // ==================== Basic Contract Tests ====================

  @Nested
  @DisplayName("Basic Contract Tests")
  class BasicContractTests {

    @Test
    @DisplayName("should resolve key from context and rule")
    void resolve_shouldResolveKeyFromContextAndRule() {
      // given
      RequestContext context = RequestContext.builder().clientIp("192.168.1.1").build();
      RateLimitRule rule = createRule("test-rule", LimitScope.PER_IP);

      // when
      RateLimitKey key = resolver.resolve(context, rule);

      // then
      assertNotNull(key);
      assertEquals("192.168.1.1", key.value());
    }
  }

  // ==================== PER_IP Scope Tests ====================

  @Nested
  @DisplayName("PER_IP Scope Tests")
  class PerIpScopeTests {

    @Test
    @DisplayName("should resolve IPv4 address")
    void resolve_shouldResolveIpv4Address() {
      // given
      RequestContext context = RequestContext.builder().clientIp("192.168.1.100").build();
      RateLimitRule rule = createRule("ip-rule", LimitScope.PER_IP);

      // when
      RateLimitKey key = resolver.resolve(context, rule);

      // then
      assertEquals("192.168.1.100", key.value());
    }

    @Test
    @DisplayName("should resolve IPv6 address")
    void resolve_shouldResolveIpv6Address() {
      // given
      RequestContext context =
          RequestContext.builder().clientIp("2001:0db8:85a3:0000:0000:8a2e:0370:7334").build();
      RateLimitRule rule = createRule("ip-rule", LimitScope.PER_IP);

      // when
      RateLimitKey key = resolver.resolve(context, rule);

      // then
      assertEquals("2001:0db8:85a3:0000:0000:8a2e:0370:7334", key.value());
    }

    @Test
    @DisplayName("should use 'unknown' when client IP is null")
    void resolve_shouldUseUnknownWhenClientIpIsNull() {
      // given
      RequestContext context = RequestContext.builder().endpoint("/api/test").build();
      RateLimitRule rule = createRule("ip-rule", LimitScope.PER_IP);

      // when
      RateLimitKey key = resolver.resolve(context, rule);

      // then
      assertEquals("unknown", key.value());
    }
  }

  // ==================== PER_USER Scope Tests ====================

  @Nested
  @DisplayName("PER_USER Scope Tests")
  class PerUserScopeTests {

    @Test
    @DisplayName("should resolve user ID")
    void resolve_shouldResolveUserId() {
      // given
      RequestContext context =
          RequestContext.builder().userId("user-abc-123").clientIp("10.0.0.1").build();
      RateLimitRule rule = createRule("user-rule", LimitScope.PER_USER);

      // when
      RateLimitKey key = resolver.resolve(context, rule);

      // then
      assertEquals("user-abc-123", key.value());
    }

    @Test
    @DisplayName("should fallback to clientIp when userId is null")
    void resolve_shouldFallbackWhenUserIdIsNull() {
      // given
      RequestContext context = RequestContext.builder().clientIp("10.0.0.1").build();
      RateLimitRule rule = createRule("user-rule", LimitScope.PER_USER);

      // when
      RateLimitKey key = resolver.resolve(context, rule);

      // then
      assertEquals("10.0.0.1", key.value());
    }
  }

  // ==================== PER_API_KEY Scope Tests ====================

  @Nested
  @DisplayName("PER_API_KEY Scope Tests")
  class PerApiKeyScopeTests {

    @Test
    @DisplayName("should resolve API key")
    void resolve_shouldResolveApiKey() {
      // given
      RequestContext context =
          RequestContext.builder().apiKey("sk-live-abc123xyz").clientIp("10.0.0.1").build();
      RateLimitRule rule = createRule("api-key-rule", LimitScope.PER_API_KEY);

      // when
      RateLimitKey key = resolver.resolve(context, rule);

      // then
      assertEquals("sk-live-abc123xyz", key.value());
    }

    @Test
    @DisplayName("should fallback to clientIp when apiKey is null")
    void resolve_shouldFallbackWhenApiKeyIsNull() {
      // given
      RequestContext context = RequestContext.builder().clientIp("10.0.0.1").build();
      RateLimitRule rule = createRule("api-key-rule", LimitScope.PER_API_KEY);

      // when
      RateLimitKey key = resolver.resolve(context, rule);

      // then
      assertEquals("10.0.0.1", key.value());
    }
  }

  // ==================== GLOBAL Scope Tests ====================

  @Nested
  @DisplayName("GLOBAL Scope Tests")
  class GlobalScopeTests {

    @Test
    @DisplayName("should return 'global' key for all requests")
    void resolve_shouldReturnGlobalKey() {
      // given
      RateLimitRule rule = createRule("global-rule", LimitScope.GLOBAL);

      RequestContext context1 =
          RequestContext.builder().clientIp("192.168.1.1").userId("user-1").build();

      RequestContext context2 =
          RequestContext.builder().clientIp("10.0.0.1").userId("user-2").build();

      // when
      RateLimitKey key1 = resolver.resolve(context1, rule);
      RateLimitKey key2 = resolver.resolve(context2, rule);

      // then
      assertEquals(key1, key2);
      assertEquals("global", key1.value());
    }
  }

  // ==================== CUSTOM Scope Tests ====================

  @Nested
  @DisplayName("CUSTOM Scope Tests")
  class CustomScopeTests {

    @Test
    @DisplayName("should resolve from custom attribute using keyStrategyId")
    void resolve_shouldResolveFromCustomAttribute() {
      // given
      RequestContext context =
          RequestContext.builder().clientIp("10.0.0.1").attribute("tenantId", "tenant-xyz").build();
      RateLimitRule rule = createCustomRule("custom-rule", "tenantId");

      // when
      RateLimitKey key = resolver.resolve(context, rule);

      // then
      assertEquals("tenant-xyz", key.value());
    }

    @Test
    @DisplayName("should fallback to clientIp when custom attribute is missing")
    void resolve_shouldFallbackWhenCustomAttributeMissing() {
      // given
      RequestContext context = RequestContext.builder().clientIp("10.0.0.1").build();
      RateLimitRule rule = createCustomRule("custom-rule", "tenantId");

      // when
      RateLimitKey key = resolver.resolve(context, rule);

      // then
      assertEquals("10.0.0.1", key.value());
    }

    @Test
    @DisplayName("should fallback to clientIp when keyStrategyId is null")
    void resolve_shouldFallbackWhenKeyStrategyIdIsNull() {
      // given
      RequestContext context =
          RequestContext.builder().clientIp("10.0.0.1").attribute("tenantId", "tenant-xyz").build();
      RateLimitRule rule =
          RateLimitRule.builder("custom-rule")
              .name("Custom Rule")
              .enabled(true)
              .scope(LimitScope.CUSTOM)
              // keyStrategyId not set
              .ruleSetId("test-rules")
              .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).label("per-minute").build())
              .build();

      // when
      RateLimitKey key = resolver.resolve(context, rule);

      // then
      assertEquals("10.0.0.1", key.value());
    }
  }

  // ==================== Multiple Scopes Tests ====================

  @Nested
  @DisplayName("Multiple Scopes Tests")
  class MultipleScopesTests {

    @Test
    @DisplayName("different scopes should produce different keys for same context")
    void resolve_differentScopesShouldProduceDifferentKeys() {
      // given
      RequestContext context =
          RequestContext.builder()
              .clientIp("192.168.1.1")
              .userId("user-123")
              .apiKey("api-key-abc")
              .build();

      RateLimitRule ipRule = createRule("ip-rule", LimitScope.PER_IP);
      RateLimitRule userRule = createRule("user-rule", LimitScope.PER_USER);
      RateLimitRule apiKeyRule = createRule("api-key-rule", LimitScope.PER_API_KEY);
      RateLimitRule globalRule = createRule("global-rule", LimitScope.GLOBAL);

      // when
      RateLimitKey ipKey = resolver.resolve(context, ipRule);
      RateLimitKey userKey = resolver.resolve(context, userRule);
      RateLimitKey apiKeyKey = resolver.resolve(context, apiKeyRule);
      RateLimitKey globalKey = resolver.resolve(context, globalRule);

      // then
      assertEquals("192.168.1.1", ipKey.value());
      assertEquals("user-123", userKey.value());
      assertEquals("api-key-abc", apiKeyKey.value());
      assertEquals("global", globalKey.value());

      // All keys should be different
      assertNotEquals(ipKey, userKey);
      assertNotEquals(userKey, apiKeyKey);
      assertNotEquals(apiKeyKey, globalKey);
    }
  }
}
