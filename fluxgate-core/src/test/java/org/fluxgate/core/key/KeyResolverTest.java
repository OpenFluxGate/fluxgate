package org.fluxgate.core.key;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.exception.MissingRateLimitKeyException;
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
      assertEquals("ip:192.168.1.1", key.value());
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
      assertEquals("ip:192.168.1.100", key.value());
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
      assertEquals("ip:2001:0db8:85a3:0000:0000:8a2e:0370:7334", key.value());
    }

    @Test
    @DisplayName("should use 'ip:unknown' when client IP is null")
    void resolve_shouldUseUnknownWhenClientIpIsNull() {
      // given
      RequestContext context = RequestContext.builder().endpoint("/api/test").build();
      RateLimitRule rule = createRule("ip-rule", LimitScope.PER_IP);

      // when
      RateLimitKey key = resolver.resolve(context, rule);

      // then
      assertEquals("ip:unknown", key.value());
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
      assertEquals("user:user-abc-123", key.value());
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
      assertEquals("ip:10.0.0.1", key.value());
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
      assertEquals("key:sk-live-abc123xyz", key.value());
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
      assertEquals("ip:10.0.0.1", key.value());
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
      assertEquals("custom:tenant-xyz", key.value());
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
      assertEquals("ip:10.0.0.1", key.value());
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
      assertEquals("ip:10.0.0.1", key.value());
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
      assertEquals("ip:192.168.1.1", ipKey.value());
      assertEquals("user:user-123", userKey.value());
      assertEquals("key:api-key-abc", apiKeyKey.value());
      assertEquals("global", globalKey.value());

      // All keys should be different
      assertNotEquals(ipKey, userKey);
      assertNotEquals(userKey, apiKeyKey);
      assertNotEquals(apiKeyKey, globalKey);
    }
  }

  // ==================== Scope Prefix Tests ====================

  @Nested
  @DisplayName("Scope Prefix Tests")
  class ScopePrefixTests {

    @Test
    @DisplayName("a userId that looks like an IP should not collide with the IP bucket")
    void resolve_scopePrefixShouldPreventNamespaceCollision() {
      // given - the userId is literally an IP address
      RequestContext userContext =
          RequestContext.builder().clientIp("203.0.113.9").userId("10.0.0.5").build();
      RequestContext ipContext = RequestContext.builder().clientIp("10.0.0.5").build();

      // when
      RateLimitKey userKey =
          resolver.resolve(userContext, createRule("user-rule", LimitScope.PER_USER));
      RateLimitKey ipKey = resolver.resolve(ipContext, createRule("ip-rule", LimitScope.PER_IP));

      // then
      assertEquals("user:10.0.0.5", userKey.value());
      assertEquals("ip:10.0.0.5", ipKey.value());
      assertNotEquals(userKey, ipKey);
    }

    @Test
    @DisplayName("fallback keys should carry the prefix of their actual source")
    void resolve_fallbackShouldCarryIpPrefix() {
      // given - no userId, so the key falls back to the client IP
      RequestContext context = RequestContext.builder().clientIp("10.0.0.5").build();

      // when
      RateLimitKey key = resolver.resolve(context, createRule("user-rule", LimitScope.PER_USER));

      // then - "ip:" and not "user:", otherwise an anonymous caller could squat a user bucket
      assertEquals("ip:10.0.0.5", key.value());
    }

    @Test
    @DisplayName("GLOBAL scope should need no prefix")
    void resolve_globalScopeShouldNeedNoPrefix() {
      // given
      RequestContext context = RequestContext.builder().clientIp("10.0.0.5").build();

      // when / then
      assertEquals(
          "global",
          resolver.resolve(context, createRule("global-rule", LimitScope.GLOBAL)).value());
    }
  }

  // ==================== Composite Key Tests ====================

  @Nested
  @DisplayName("Composite Key Tests")
  class CompositeKeyTests {

    @Test
    @DisplayName("composite custom values should keep their separators under the custom prefix")
    void resolve_compositeCustomValueShouldKeepSeparators() {
      // given - the customizer composed a prefixed IP + user key
      RequestContext context =
          RequestContext.builder()
              .clientIp("10.0.0.1")
              .attribute("ipUser", "ip:10.0.0.1:user:user-123")
              .build();
      RateLimitRule rule = createCustomRule("composite-rule", "ipUser");

      // when
      RateLimitKey key = resolver.resolve(context, rule);

      // then
      assertEquals("custom:ip:10.0.0.1:user:user-123", key.value());
    }

    @Test
    @DisplayName("composite custom values for different components should produce different keys")
    void resolve_compositeCustomValuesShouldBeDistinct() {
      // given
      RateLimitRule rule = createCustomRule("composite-rule", "ipUser");
      RequestContext first =
          RequestContext.builder().attribute("ipUser", "ip:10.0.0.1:user:user-1").build();
      RequestContext second =
          RequestContext.builder().attribute("ipUser", "ip:10.0.0.1:user:user-2").build();

      // when / then
      assertNotEquals(resolver.resolve(first, rule), resolver.resolve(second, rule));
    }
  }

  // ==================== Sanitisation Tests ====================

  @Nested
  @DisplayName("Sanitisation Tests")
  class SanitisationTests {

    @Test
    @DisplayName("disallowed characters in a key value should be replaced")
    void resolve_shouldSanitiseDisallowedCharacters() {
      // given - a forwarded header value carrying Redis glob metacharacters and whitespace
      RequestContext context = RequestContext.builder().clientIp("10.0.0.1 */?[]").build();

      // when
      RateLimitKey key = resolver.resolve(context, createRule("ip-rule", LimitScope.PER_IP));

      // then - the replacement is marked and carries a digest, so it cannot collide
      assertTrue(key.value().matches("ip:h:10\\.0\\.0\\.1______:[0-9a-f]{16}"), key.value());
    }

    @Test
    @DisplayName("values differing only in replaced characters should resolve to distinct keys")
    void resolve_replacedCharactersShouldNotCollide() {
      RateLimitRule rule = createRule("user-rule", LimitScope.PER_USER);

      RateLimitKey plus = resolver.resolve(RequestContext.builder().userId("a+1").build(), rule);
      RateLimitKey underscore =
          resolver.resolve(RequestContext.builder().userId("a_1").build(), rule);

      assertEquals("user:a_1", underscore.value());
      assertNotEquals(plus, underscore);
    }

    @Test
    @DisplayName("the length limit should apply to the value only, never hashing away the prefix")
    void resolve_lengthLimitShouldApplyToTheValueOnly() {
      // 252 characters: within the value limit, but over it once "user:" is prepended
      String userId = repeat("u", 252);
      RequestContext context = RequestContext.builder().userId(userId).build();

      RateLimitKey key = resolver.resolve(context, createRule("user-rule", LimitScope.PER_USER));

      assertEquals("user:" + userId, key.value());
    }

    @Test
    @DisplayName("over-long key values should be hashed but keep their scope prefix")
    void resolve_shouldHashOverLongKeyValues() {
      // given
      String longUserId = repeat("u", 300);
      RequestContext context = RequestContext.builder().userId(longUserId).build();

      // when
      RateLimitKey key = resolver.resolve(context, createRule("user-rule", LimitScope.PER_USER));

      // then
      assertTrue(key.value().matches("user:h:[0-9a-f]{64}"), key.value());
    }

    private String repeat(String value, int times) {
      StringBuilder sb = new StringBuilder(value.length() * times);
      for (int i = 0; i < times; i++) {
        sb.append(value);
      }
      return sb.toString();
    }
  }

  // ==================== MissingKeyBehavior Tests ====================

  @Nested
  @DisplayName("MissingKeyBehavior Tests")
  class MissingKeyBehaviorTests {

    private final KeyResolver rejectingResolver =
        new LimitScopeKeyResolver(MissingKeyBehavior.REJECT);

    @Test
    @DisplayName("no-arg constructor should default to FALLBACK_TO_IP")
    void constructor_shouldDefaultToFallbackToIp() {
      // given / when / then
      assertEquals(
          MissingKeyBehavior.FALLBACK_TO_IP, new LimitScopeKeyResolver().getMissingKeyBehavior());
    }

    @Test
    @DisplayName("REJECT should throw when userId is missing")
    void resolve_shouldRejectMissingUserId() {
      // given
      RequestContext context = RequestContext.builder().clientIp("10.0.0.1").build();
      RateLimitRule rule = createRule("user-rule", LimitScope.PER_USER);

      // when / then
      MissingRateLimitKeyException exception =
          assertThrows(
              MissingRateLimitKeyException.class, () -> rejectingResolver.resolve(context, rule));
      assertEquals("user-rule", exception.getRuleId());
      assertEquals(LimitScope.PER_USER, exception.getScope());
    }

    @Test
    @DisplayName("REJECT should throw when apiKey is missing")
    void resolve_shouldRejectMissingApiKey() {
      // given
      RequestContext context = RequestContext.builder().clientIp("10.0.0.1").build();
      RateLimitRule rule = createRule("api-key-rule", LimitScope.PER_API_KEY);

      // when / then
      MissingRateLimitKeyException exception =
          assertThrows(
              MissingRateLimitKeyException.class, () -> rejectingResolver.resolve(context, rule));
      assertEquals(LimitScope.PER_API_KEY, exception.getScope());
    }

    @Test
    @DisplayName("REJECT should throw when the custom attribute is missing")
    void resolve_shouldRejectMissingCustomAttribute() {
      // given
      RequestContext context = RequestContext.builder().clientIp("10.0.0.1").build();
      RateLimitRule rule = createCustomRule("custom-rule", "tenantId");

      // when / then
      assertThrows(
          MissingRateLimitKeyException.class, () -> rejectingResolver.resolve(context, rule));
    }

    @Test
    @DisplayName("REJECT should throw when clientIp is missing")
    void resolve_shouldRejectMissingClientIp() {
      // given
      RequestContext context = RequestContext.builder().endpoint("/api/test").build();
      RateLimitRule rule = createRule("ip-rule", LimitScope.PER_IP);

      // when / then
      assertThrows(
          MissingRateLimitKeyException.class, () -> rejectingResolver.resolve(context, rule));
    }

    @Test
    @DisplayName("REJECT should resolve normally when the value is present")
    void resolve_shouldResolveWhenValuePresent() {
      // given
      RequestContext context = RequestContext.builder().userId("user-123").build();
      RateLimitRule rule = createRule("user-rule", LimitScope.PER_USER);

      // when / then
      assertEquals("user:user-123", rejectingResolver.resolve(context, rule).value());
    }
  }
}
