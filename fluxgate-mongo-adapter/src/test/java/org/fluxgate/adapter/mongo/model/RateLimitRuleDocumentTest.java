package org.fluxgate.adapter.mongo.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RateLimitRuleDocument}.
 *
 * <p>RateLimitRuleDocument represents a rate limit rule document for MongoDB storage.
 */
@DisplayName("RateLimitRuleDocument Tests")
class RateLimitRuleDocumentTest {

  // Helper method to create a valid band document
  private RateLimitBandDocument createBandDocument(long windowSeconds, long capacity) {
    return new RateLimitBandDocument(windowSeconds, capacity, "default");
  }

  // ==================== Constructor Tests ====================

  @Nested
  @DisplayName("Constructor Tests")
  class ConstructorTests {

    @Test
    @DisplayName("should create document with all fields (without attributes)")
    void constructor_shouldCreateDocumentWithAllFields() {
      // given
      String id = "rule-1";
      String name = "Test Rule";
      boolean enabled = true;
      LimitScope scope = LimitScope.PER_IP;
      String keyStrategyId = "ip";
      OnLimitExceedPolicy onLimitExceedPolicy = OnLimitExceedPolicy.REJECT_REQUEST;
      List<RateLimitBandDocument> bands = List.of(createBandDocument(60, 100));
      String ruleSetId = "api-limits";

      // when
      RateLimitRuleDocument document =
          new RateLimitRuleDocument(
              id, name, enabled, scope, keyStrategyId, onLimitExceedPolicy, bands, ruleSetId);

      // then
      assertEquals(id, document.getId());
      assertEquals(name, document.getName());
      assertTrue(document.isEnabled());
      assertEquals(scope, document.getScope());
      assertEquals(keyStrategyId, document.getKeyStrategyId());
      assertEquals(onLimitExceedPolicy, document.getOnLimitExceedPolicy());
      assertEquals(1, document.getBands().size());
      assertEquals(ruleSetId, document.getRuleSetId());
      assertTrue(document.getAttributes().isEmpty());
    }

    @Test
    @DisplayName("should create document with multiple bands")
    void constructor_shouldCreateDocumentWithMultipleBands() {
      // given
      List<RateLimitBandDocument> bands =
          List.of(
              createBandDocument(1, 10),
              createBandDocument(60, 100),
              createBandDocument(3600, 1000));

      // when
      RateLimitRuleDocument document =
          new RateLimitRuleDocument(
              "rule-1",
              "Multi-Band Rule",
              true,
              LimitScope.PER_IP,
              "ip",
              OnLimitExceedPolicy.REJECT_REQUEST,
              bands,
              "default");

      // then
      assertEquals(3, document.getBands().size());
    }

    @Test
    @DisplayName("should create document with attributes")
    void constructor_shouldCreateDocumentWithAttributes() {
      // given
      Map<String, Object> attributes = Map.of("tier", "premium", "team", "billing");

      // when
      RateLimitRuleDocument document =
          new RateLimitRuleDocument(
              "rule-1",
              "Premium Rule",
              true,
              LimitScope.PER_IP,
              "ip",
              OnLimitExceedPolicy.REJECT_REQUEST,
              List.of(createBandDocument(60, 100)),
              "default",
              attributes);

      // then
      assertEquals("premium", document.getAttributes().get("tier"));
      assertEquals("billing", document.getAttributes().get("team"));
    }

    @Test
    @DisplayName("should throw NullPointerException when id is null")
    void constructor_shouldThrowWhenIdIsNull() {
      // given / when / then
      assertThrows(
          NullPointerException.class,
          () ->
              new RateLimitRuleDocument(
                  null,
                  "name",
                  true,
                  LimitScope.PER_IP,
                  "ip",
                  OnLimitExceedPolicy.REJECT_REQUEST,
                  List.of(createBandDocument(60, 100)),
                  "ruleset"));
    }

    @Test
    @DisplayName("should throw NullPointerException when name is null")
    void constructor_shouldThrowWhenNameIsNull() {
      // given / when / then
      assertThrows(
          NullPointerException.class,
          () ->
              new RateLimitRuleDocument(
                  "id",
                  null,
                  true,
                  LimitScope.PER_IP,
                  "ip",
                  OnLimitExceedPolicy.REJECT_REQUEST,
                  List.of(createBandDocument(60, 100)),
                  "ruleset"));
    }

    @Test
    @DisplayName("should throw NullPointerException when scope is null")
    void constructor_shouldThrowWhenScopeIsNull() {
      // given / when / then
      assertThrows(
          NullPointerException.class,
          () ->
              new RateLimitRuleDocument(
                  "id",
                  "name",
                  true,
                  null,
                  "ip",
                  OnLimitExceedPolicy.REJECT_REQUEST,
                  List.of(createBandDocument(60, 100)),
                  "ruleset"));
    }

    @Test
    @DisplayName("should throw NullPointerException when ruleSetId is null")
    void constructor_shouldThrowWhenRuleSetIdIsNull() {
      // given / when / then
      assertThrows(
          NullPointerException.class,
          () ->
              new RateLimitRuleDocument(
                  "id",
                  "name",
                  true,
                  LimitScope.PER_IP,
                  "ip",
                  OnLimitExceedPolicy.REJECT_REQUEST,
                  List.of(createBandDocument(60, 100)),
                  null));
    }
  }

  // ==================== Getter Tests ====================

  @Nested
  @DisplayName("Getter Tests")
  class GetterTests {

    @Test
    @DisplayName("getId should return correct value")
    void getId_shouldReturnCorrectValue() {
      // given
      RateLimitRuleDocument document = createDefaultDocument("test-id");

      // when / then
      assertEquals("test-id", document.getId());
    }

    @Test
    @DisplayName("getName should return correct value")
    void getName_shouldReturnCorrectValue() {
      // given
      RateLimitRuleDocument document =
          new RateLimitRuleDocument(
              "id",
              "Custom Name",
              true,
              LimitScope.PER_IP,
              "ip",
              OnLimitExceedPolicy.REJECT_REQUEST,
              List.of(createBandDocument(60, 100)),
              "default");

      // when / then
      assertEquals("Custom Name", document.getName());
    }

    @Test
    @DisplayName("isEnabled should return correct value")
    void isEnabled_shouldReturnCorrectValue() {
      // given
      RateLimitRuleDocument enabledDoc =
          new RateLimitRuleDocument(
              "id",
              "name",
              true,
              LimitScope.PER_IP,
              "ip",
              OnLimitExceedPolicy.REJECT_REQUEST,
              List.of(createBandDocument(60, 100)),
              "default");
      RateLimitRuleDocument disabledDoc =
          new RateLimitRuleDocument(
              "id",
              "name",
              false,
              LimitScope.PER_IP,
              "ip",
              OnLimitExceedPolicy.REJECT_REQUEST,
              List.of(createBandDocument(60, 100)),
              "default");

      // when / then
      assertTrue(enabledDoc.isEnabled());
      assertFalse(disabledDoc.isEnabled());
    }

    @Test
    @DisplayName("getScope should return correct value")
    void getScope_shouldReturnCorrectValue() {
      // given
      RateLimitRuleDocument document =
          new RateLimitRuleDocument(
              "id",
              "name",
              true,
              LimitScope.PER_USER,
              "userId",
              OnLimitExceedPolicy.REJECT_REQUEST,
              List.of(createBandDocument(60, 100)),
              "default");

      // when / then
      assertEquals(LimitScope.PER_USER, document.getScope());
    }

    @Test
    @DisplayName("getBands should return immutable copy")
    void getBands_shouldReturnImmutableCopy() {
      // given
      List<RateLimitBandDocument> bands = List.of(createBandDocument(60, 100));
      RateLimitRuleDocument document =
          new RateLimitRuleDocument(
              "id",
              "name",
              true,
              LimitScope.PER_IP,
              "ip",
              OnLimitExceedPolicy.REJECT_REQUEST,
              bands,
              "default");

      // when
      List<RateLimitBandDocument> returnedBands = document.getBands();

      // then
      assertThrows(
          UnsupportedOperationException.class,
          () -> returnedBands.add(createBandDocument(120, 200)));
    }

    @Test
    @DisplayName("getRuleSetId should return correct value")
    void getRuleSetId_shouldReturnCorrectValue() {
      // given
      RateLimitRuleDocument document =
          new RateLimitRuleDocument(
              "id",
              "name",
              true,
              LimitScope.PER_IP,
              "ip",
              OnLimitExceedPolicy.REJECT_REQUEST,
              List.of(createBandDocument(60, 100)),
              "my-ruleset");

      // when / then
      assertEquals("my-ruleset", document.getRuleSetId());
    }

    @Test
    @DisplayName("getOnLimitExceedPolicy should return correct value")
    void getOnLimitExceedPolicy_shouldReturnCorrectValue() {
      // given
      RateLimitRuleDocument document =
          new RateLimitRuleDocument(
              "id",
              "name",
              true,
              LimitScope.PER_IP,
              "ip",
              OnLimitExceedPolicy.WAIT_FOR_REFILL,
              List.of(createBandDocument(60, 100)),
              "default");

      // when / then
      assertEquals(OnLimitExceedPolicy.WAIT_FOR_REFILL, document.getOnLimitExceedPolicy());
    }

    @Test
    @DisplayName("getAttributes should return empty map when using constructor without attributes")
    void getAttributes_shouldReturnEmptyMapWhenNoAttributes() {
      // given
      RateLimitRuleDocument document = createDefaultDocument("test-id");

      // when / then
      assertNotNull(document.getAttributes());
      assertTrue(document.getAttributes().isEmpty());
    }

    @Test
    @DisplayName("getAttributes should return correct values")
    void getAttributes_shouldReturnCorrectValues() {
      // given
      Map<String, Object> attributes = Map.of("tier", "premium", "limit", 1000);
      RateLimitRuleDocument document =
          new RateLimitRuleDocument(
              "id",
              "name",
              true,
              LimitScope.PER_IP,
              "ip",
              OnLimitExceedPolicy.REJECT_REQUEST,
              List.of(createBandDocument(60, 100)),
              "default",
              attributes);

      // when / then
      assertEquals("premium", document.getAttributes().get("tier"));
      assertEquals(1000, document.getAttributes().get("limit"));
    }
  }

  // ==================== Edge Case Tests ====================

  @Nested
  @DisplayName("Edge Case Tests")
  class EdgeCaseTests {

    @Test
    @DisplayName("should handle all scope types")
    void shouldHandleAllScopeTypes() {
      // given
      LimitScope[] scopes = {
        LimitScope.GLOBAL, LimitScope.PER_API_KEY, LimitScope.PER_USER, LimitScope.PER_IP
      };

      // when / then
      for (LimitScope scope : scopes) {
        RateLimitRuleDocument document =
            new RateLimitRuleDocument(
                "id",
                "name",
                true,
                scope,
                "key",
                OnLimitExceedPolicy.REJECT_REQUEST,
                List.of(createBandDocument(60, 100)),
                "default");
        assertEquals(scope, document.getScope());
      }
    }

    @Test
    @DisplayName("should handle all policy types")
    void shouldHandleAllPolicyTypes() {
      // given
      OnLimitExceedPolicy[] policies = OnLimitExceedPolicy.values();

      // when / then
      for (OnLimitExceedPolicy policy : policies) {
        RateLimitRuleDocument document =
            new RateLimitRuleDocument(
                "id",
                "name",
                true,
                LimitScope.PER_IP,
                "ip",
                policy,
                List.of(createBandDocument(60, 100)),
                "default");
        assertEquals(policy, document.getOnLimitExceedPolicy());
      }
    }
  }

  // Helper method
  private RateLimitRuleDocument createDefaultDocument(String id) {
    return new RateLimitRuleDocument(
        id,
        "Default Rule",
        true,
        LimitScope.PER_IP,
        "ip",
        OnLimitExceedPolicy.REJECT_REQUEST,
        List.of(createBandDocument(60, 100)),
        "default");
  }
}
