package org.fluxgate.core.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RateLimitRuleSet}.
 *
 * <p>RateLimitRuleSet groups related rate limit rules together with a key resolution strategy and
 * optional metrics recording.
 */
@DisplayName("RateLimitRuleSet Tests")
class RateLimitRuleSetTest {

  // Helper methods
  private RateLimitRule createRule(String id) {
    return RateLimitRule.builder(id)
        .name("Test Rule " + id)
        .scope(LimitScope.PER_IP)
        .keyStrategyId("ip")
        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).build())
        .build();
  }

  private KeyResolver createKeyResolver() {
    return context -> RateLimitKey.of(context.getClientIp());
  }

  private RateLimitMetricsRecorder createMetricsRecorder() {
    return (context, result) -> {
      // no-op recorder for testing
    };
  }

  // ==================== Builder Tests ====================

  @Nested
  @DisplayName("Builder Tests")
  class BuilderTests {

    @Test
    @DisplayName("should build rule set with required fields")
    void build_shouldCreateRuleSetWithRequiredFields() {
      // given
      String id = "auth-api-default";
      RateLimitRule rule = createRule("rule-1");
      KeyResolver keyResolver = createKeyResolver();

      // when
      RateLimitRuleSet ruleSet =
          RateLimitRuleSet.builder(id).rules(List.of(rule)).keyResolver(keyResolver).build();

      // then
      assertEquals(id, ruleSet.getId());
      assertEquals(1, ruleSet.getRules().size());
      assertEquals(rule, ruleSet.getRules().get(0));
      assertNotNull(ruleSet.getKeyResolver());
      assertNull(ruleSet.getDescription());
      assertNull(ruleSet.getMetricsRecorder());
    }

    @Test
    @DisplayName("should build rule set with all optional fields")
    void build_shouldCreateRuleSetWithAllFields() {
      // given
      String id = "api-limits";
      String description = "Rate limits for API endpoints";
      RateLimitRule rule1 = createRule("rule-1");
      RateLimitRule rule2 = createRule("rule-2");
      KeyResolver keyResolver = createKeyResolver();
      RateLimitMetricsRecorder metricsRecorder = createMetricsRecorder();

      // when
      RateLimitRuleSet ruleSet =
          RateLimitRuleSet.builder(id)
              .description(description)
              .rules(List.of(rule1, rule2))
              .keyResolver(keyResolver)
              .metricsRecorder(metricsRecorder)
              .build();

      // then
      assertEquals(id, ruleSet.getId());
      assertEquals(description, ruleSet.getDescription());
      assertEquals(2, ruleSet.getRules().size());
      assertNotNull(ruleSet.getKeyResolver());
      assertNotNull(ruleSet.getMetricsRecorder());
    }

    @Test
    @DisplayName("should preserve rule order")
    void build_shouldPreserveRuleOrder() {
      // given
      RateLimitRule rule1 = createRule("first");
      RateLimitRule rule2 = createRule("second");
      RateLimitRule rule3 = createRule("third");

      // when
      RateLimitRuleSet ruleSet =
          RateLimitRuleSet.builder("test-set")
              .rules(List.of(rule1, rule2, rule3))
              .keyResolver(createKeyResolver())
              .build();

      // then
      List<RateLimitRule> rules = ruleSet.getRules();
      assertEquals("first", rules.get(0).getId());
      assertEquals("second", rules.get(1).getId());
      assertEquals("third", rules.get(2).getId());
    }
  }

  // ==================== Validation Tests ====================

  @Nested
  @DisplayName("Validation Tests")
  class ValidationTests {

    @Test
    @DisplayName("should throw NullPointerException when id is null")
    void build_shouldThrowWhenIdIsNull() {
      // given / when / then
      NullPointerException exception =
          assertThrows(
              NullPointerException.class,
              () ->
                  RateLimitRuleSet.builder(null)
                      .rules(List.of(createRule("rule-1")))
                      .keyResolver(createKeyResolver())
                      .build());
      assertTrue(exception.getMessage().contains("id must not be null"));
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when rules is empty")
    void build_shouldThrowWhenRulesIsEmpty() {
      // given / when / then
      IllegalArgumentException exception =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  RateLimitRuleSet.builder("test-set")
                      .rules(List.of())
                      .keyResolver(createKeyResolver())
                      .build());
      assertTrue(exception.getMessage().contains("rules must contain at least one"));
    }

    @Test
    @DisplayName("should throw IllegalStateException when keyResolver is null")
    void build_shouldThrowWhenKeyResolverIsNull() {
      // given / when / then
      IllegalStateException exception =
          assertThrows(
              IllegalStateException.class,
              () ->
                  RateLimitRuleSet.builder("test-set")
                      .rules(List.of(createRule("rule-1")))
                      .keyResolver(null)
                      .build());
      assertTrue(exception.getMessage().contains("keyResolver must not be null"));
    }

    @Test
    @DisplayName("should throw IllegalStateException when keyResolver not set")
    void build_shouldThrowWhenKeyResolverNotSet() {
      // given / when / then
      assertThrows(
          IllegalStateException.class,
          () -> RateLimitRuleSet.builder("test-set").rules(List.of(createRule("rule-1"))).build());
    }
  }

  // ==================== Getter Tests ====================

  @Nested
  @DisplayName("Getter Tests")
  class GetterTests {

    @Test
    @DisplayName("getId should return correct id")
    void getId_shouldReturnCorrectId() {
      // given
      String expectedId = "my-rule-set";
      RateLimitRuleSet ruleSet =
          RateLimitRuleSet.builder(expectedId)
              .rules(List.of(createRule("rule-1")))
              .keyResolver(createKeyResolver())
              .build();

      // when / then
      assertEquals(expectedId, ruleSet.getId());
    }

    @Test
    @DisplayName("getDescription should return null when not set")
    void getDescription_shouldReturnNullWhenNotSet() {
      // given
      RateLimitRuleSet ruleSet =
          RateLimitRuleSet.builder("test-set")
              .rules(List.of(createRule("rule-1")))
              .keyResolver(createKeyResolver())
              .build();

      // when / then
      assertNull(ruleSet.getDescription());
    }

    @Test
    @DisplayName("getDescription should return correct description")
    void getDescription_shouldReturnCorrectDescription() {
      // given
      String expectedDescription = "Test description";
      RateLimitRuleSet ruleSet =
          RateLimitRuleSet.builder("test-set")
              .description(expectedDescription)
              .rules(List.of(createRule("rule-1")))
              .keyResolver(createKeyResolver())
              .build();

      // when / then
      assertEquals(expectedDescription, ruleSet.getDescription());
    }

    @Test
    @DisplayName("getRules should return unmodifiable list")
    void getRules_shouldReturnUnmodifiableList() {
      // given
      RateLimitRuleSet ruleSet =
          RateLimitRuleSet.builder("test-set")
              .rules(List.of(createRule("rule-1")))
              .keyResolver(createKeyResolver())
              .build();

      // when
      List<RateLimitRule> rules = ruleSet.getRules();

      // then
      assertThrows(UnsupportedOperationException.class, () -> rules.add(createRule("new-rule")));
    }

    @Test
    @DisplayName("getKeyResolver should return the resolver")
    void getKeyResolver_shouldReturnResolver() {
      // given
      KeyResolver expectedResolver = createKeyResolver();
      RateLimitRuleSet ruleSet =
          RateLimitRuleSet.builder("test-set")
              .rules(List.of(createRule("rule-1")))
              .keyResolver(expectedResolver)
              .build();

      // when / then
      assertNotNull(ruleSet.getKeyResolver());
    }

    @Test
    @DisplayName("getMetricsRecorder should return null when not set")
    void getMetricsRecorder_shouldReturnNullWhenNotSet() {
      // given
      RateLimitRuleSet ruleSet =
          RateLimitRuleSet.builder("test-set")
              .rules(List.of(createRule("rule-1")))
              .keyResolver(createKeyResolver())
              .build();

      // when / then
      assertNull(ruleSet.getMetricsRecorder());
    }

    @Test
    @DisplayName("getMetricsRecorder should return recorder when set")
    void getMetricsRecorder_shouldReturnRecorderWhenSet() {
      // given
      RateLimitMetricsRecorder expectedRecorder = createMetricsRecorder();
      RateLimitRuleSet ruleSet =
          RateLimitRuleSet.builder("test-set")
              .rules(List.of(createRule("rule-1")))
              .keyResolver(createKeyResolver())
              .metricsRecorder(expectedRecorder)
              .build();

      // when / then
      assertNotNull(ruleSet.getMetricsRecorder());
    }
  }

  // ==================== KeyResolver Integration Tests ====================

  @Nested
  @DisplayName("KeyResolver Integration Tests")
  class KeyResolverIntegrationTests {

    @Test
    @DisplayName("keyResolver should resolve key from context")
    void keyResolver_shouldResolveKeyFromContext() {
      // given
      String clientIp = "10.0.0.1";
      KeyResolver resolver = context -> RateLimitKey.of(context.getClientIp());

      RateLimitRuleSet ruleSet =
          RateLimitRuleSet.builder("test-set")
              .rules(List.of(createRule("rule-1")))
              .keyResolver(resolver)
              .build();

      RequestContext context =
          RequestContext.builder().clientIp(clientIp).endpoint("/api/test").method("GET").build();

      // when
      RateLimitKey resolvedKey = ruleSet.getKeyResolver().resolve(context);

      // then
      assertEquals(clientIp, resolvedKey.value());
    }

    @Test
    @DisplayName("keyResolver should handle different key strategies")
    void keyResolver_shouldHandleDifferentStrategies() {
      // given - API key strategy
      KeyResolver apiKeyResolver = context -> RateLimitKey.of(context.getApiKey());

      RateLimitRuleSet apiKeySet =
          RateLimitRuleSet.builder("api-key-set")
              .rules(List.of(createRule("rule-1")))
              .keyResolver(apiKeyResolver)
              .build();

      RequestContext context =
          RequestContext.builder()
              .clientIp("192.168.1.1")
              .apiKey("my-api-key-123")
              .userId("user-456")
              .build();

      // when
      RateLimitKey resolvedKey = apiKeySet.getKeyResolver().resolve(context);

      // then
      assertEquals("my-api-key-123", resolvedKey.value());
    }
  }
}
