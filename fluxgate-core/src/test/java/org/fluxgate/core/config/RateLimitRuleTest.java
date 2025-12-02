package org.fluxgate.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RateLimitRule}.
 * <p>
 * RateLimitRule is the core configuration object describing a rate limit rule.
 * It is intentionally storage-agnostic and engine-agnostic.
 */
@DisplayName("RateLimitRule Tests")
class RateLimitRuleTest {

    // Helper method to create a valid band
    private RateLimitBand createBand(Duration window, long capacity) {
        return RateLimitBand.builder(window, capacity).build();
    }

    // ==================== Basic Happy Path Tests ====================

    @Nested
    @DisplayName("Basic Happy Path Tests")
    class BasicHappyPathTests {

        @Test
        @DisplayName("should build rule with all custom fields")
        void build_shouldCreateRuleWithAllCustomFields() {
            // given
            String id = "rule-1";
            String name = "Custom Rule Name";
            boolean enabled = false;
            LimitScope scope = LimitScope.PER_IP;
            String keyStrategyId = "ip";
            OnLimitExceedPolicy policy = OnLimitExceedPolicy.WAIT_FOR_REFILL;
            String ruleSetId = "api-limits";
            RateLimitBand band1 = createBand(Duration.ofMinutes(1), 100);
            RateLimitBand band2 = createBand(Duration.ofHours(1), 1000);

            // when
            RateLimitRule rule = RateLimitRule.builder(id)
                    .name(name)
                    .enabled(enabled)
                    .scope(scope)
                    .keyStrategyId(keyStrategyId)
                    .onLimitExceedPolicy(policy)
                    .ruleSetId(ruleSetId)
                    .addBand(band1)
                    .addBand(band2)
                    .build();

            // then
            assertEquals(id, rule.getId());
            assertEquals(name, rule.getName());
            assertFalse(rule.isEnabled());
            assertEquals(scope, rule.getScope());
            assertEquals(keyStrategyId, rule.getKeyStrategyId());
            assertEquals(policy, rule.getOnLimitExceedPolicy());
            assertEquals(ruleSetId, rule.getRuleSetIdOrNull());
            assertEquals(2, rule.getBands().size());
            assertEquals(band1.getCapacity(), rule.getBands().get(0).getCapacity());
            assertEquals(band2.getCapacity(), rule.getBands().get(1).getCapacity());
        }

        @Test
        @DisplayName("should preserve band order")
        void build_shouldPreserveBandOrder() {
            // given
            RateLimitBand band1 = createBand(Duration.ofSeconds(1), 10);
            RateLimitBand band2 = createBand(Duration.ofMinutes(1), 100);
            RateLimitBand band3 = createBand(Duration.ofHours(1), 1000);

            // when
            RateLimitRule rule = RateLimitRule.builder("rule-1")
                    .addBand(band1)
                    .addBand(band2)
                    .addBand(band3)
                    .build();

            // then
            List<RateLimitBand> bands = rule.getBands();
            assertEquals(3, bands.size());
            assertEquals(10, bands.get(0).getCapacity());
            assertEquals(100, bands.get(1).getCapacity());
            assertEquals(1000, bands.get(2).getCapacity());
        }

        @Test
        @DisplayName("toString should contain key fields")
        void toString_shouldContainKeyFields() {
            // given
            RateLimitRule rule = RateLimitRule.builder("rule-1")
                    .name("Test Rule")
                    .scope(LimitScope.PER_IP)
                    .keyStrategyId("ip")
                    .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
                    .addBand(createBand(Duration.ofMinutes(1), 100))
                    .build();

            // when
            String result = rule.toString();

            // then
            assertTrue(result.contains("id='rule-1'"));
            assertTrue(result.contains("name='Test Rule'"));
            assertTrue(result.contains("scope=PER_IP"));
            assertTrue(result.contains("keyStrategyId='ip'"));
            assertTrue(result.contains("onLimitExceedPolicy=REJECT_REQUEST"));
            assertTrue(result.contains("enabled=true"));
        }
    }

    // ==================== Default Behavior Tests ====================

    @Nested
    @DisplayName("Default Behavior Tests")
    class DefaultBehaviorTests {

        @Test
        @DisplayName("should use id as default name when name not provided")
        void build_shouldUseIdAsDefaultNameWhenNameNotProvided() {
            // given
            String id = "my-rule-id";

            // when
            RateLimitRule rule = RateLimitRule.builder(id)
                    .addBand(createBand(Duration.ofMinutes(1), 100))
                    .build();

            // then
            assertEquals(id, rule.getName());
        }

        @Test
        @DisplayName("should default enabled to true")
        void build_shouldDefaultEnabledToTrue() {
            // given / when
            RateLimitRule rule = RateLimitRule.builder("rule-1")
                    .addBand(createBand(Duration.ofMinutes(1), 100))
                    .build();

            // then
            assertTrue(rule.isEnabled());
        }

        @Test
        @DisplayName("should default scope to PER_API_KEY")
        void build_shouldDefaultScopeToPerApiKey() {
            // given / when
            RateLimitRule rule = RateLimitRule.builder("rule-1")
                    .addBand(createBand(Duration.ofMinutes(1), 100))
                    .build();

            // then
            assertEquals(LimitScope.PER_API_KEY, rule.getScope());
        }

        @Test
        @DisplayName("should default keyStrategyId to apiKey")
        void build_shouldDefaultKeyStrategyIdToApiKey() {
            // given / when
            RateLimitRule rule = RateLimitRule.builder("rule-1")
                    .addBand(createBand(Duration.ofMinutes(1), 100))
                    .build();

            // then
            assertEquals("apiKey", rule.getKeyStrategyId());
        }

        @Test
        @DisplayName("should default onLimitExceedPolicy to REJECT_REQUEST")
        void build_shouldDefaultOnLimitExceedPolicyToRejectRequest() {
            // given / when
            RateLimitRule rule = RateLimitRule.builder("rule-1")
                    .addBand(createBand(Duration.ofMinutes(1), 100))
                    .build();

            // then
            assertEquals(OnLimitExceedPolicy.REJECT_REQUEST, rule.getOnLimitExceedPolicy());
        }

        @Test
        @DisplayName("ruleSetId should be null when not set")
        void build_shouldAllowNullRuleSetId() {
            // given / when
            RateLimitRule rule = RateLimitRule.builder("rule-1")
                    .addBand(createBand(Duration.ofMinutes(1), 100))
                    .build();

            // then
            assertNull(rule.getRuleSetIdOrNull());
        }
    }

    // ==================== Bands Requirement Tests ====================

    @Nested
    @DisplayName("Bands Requirement Tests")
    class BandsRequirementTests {

        @Test
        @DisplayName("should throw IllegalArgumentException when no bands configured")
        void build_shouldThrowWhenNoBandsConfigured() {
            // given / when / then
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> RateLimitRule.builder("rule-1").build()
            );
            assertTrue(exception.getMessage().contains("At least one RateLimitBand must be configured"));
        }

        @Test
        @DisplayName("should allow single band")
        void build_shouldAllowSingleBand() {
            // given / when
            RateLimitRule rule = RateLimitRule.builder("rule-1")
                    .addBand(createBand(Duration.ofMinutes(1), 100))
                    .build();

            // then
            assertEquals(1, rule.getBands().size());
        }

        @Test
        @DisplayName("should allow multiple bands")
        void build_shouldAllowMultipleBands() {
            // given / when
            RateLimitRule rule = RateLimitRule.builder("rule-1")
                    .addBand(createBand(Duration.ofSeconds(1), 10))
                    .addBand(createBand(Duration.ofMinutes(1), 100))
                    .addBand(createBand(Duration.ofHours(1), 1000))
                    .addBand(createBand(Duration.ofDays(1), 10000))
                    .build();

            // then
            assertEquals(4, rule.getBands().size());
        }
    }

    // ==================== Null Safety Tests ====================

    @Nested
    @DisplayName("Null Safety Tests")
    class NullSafetyTests {

        @Test
        @DisplayName("builder(null) should throw NullPointerException")
        void builder_shouldThrowWhenIdIsNull() {
            // given / when / then
            NullPointerException exception = assertThrows(
                    NullPointerException.class,
                    () -> RateLimitRule.builder(null)
            );
            assertTrue(exception.getMessage().contains("id must not be null"));
        }

        @Test
        @DisplayName("scope(null) should throw NullPointerException")
        void scope_shouldThrowWhenNull() {
            // given / when / then
            NullPointerException exception = assertThrows(
                    NullPointerException.class,
                    () -> RateLimitRule.builder("rule-1")
                            .scope(null)
                            .addBand(createBand(Duration.ofMinutes(1), 100))
                            .build()
            );
            assertTrue(exception.getMessage().contains("scope must not be null"));
        }

        @Test
        @DisplayName("keyStrategyId(null) should throw NullPointerException")
        void keyStrategyId_shouldThrowWhenNull() {
            // given / when / then
            NullPointerException exception = assertThrows(
                    NullPointerException.class,
                    () -> RateLimitRule.builder("rule-1")
                            .keyStrategyId(null)
                            .addBand(createBand(Duration.ofMinutes(1), 100))
                            .build()
            );
            assertTrue(exception.getMessage().contains("keyStrategyId must not be null"));
        }

        @Test
        @DisplayName("onLimitExceedPolicy(null) should throw NullPointerException")
        void onLimitExceedPolicy_shouldThrowWhenNull() {
            // given / when / then
            NullPointerException exception = assertThrows(
                    NullPointerException.class,
                    () -> RateLimitRule.builder("rule-1")
                            .onLimitExceedPolicy(null)
                            .addBand(createBand(Duration.ofMinutes(1), 100))
                            .build()
            );
            assertTrue(exception.getMessage().contains("onLimitExceedPolicy must not be null"));
        }

        @Test
        @DisplayName("addBand(null) should throw NullPointerException")
        void addBand_shouldThrowWhenNull() {
            // given / when / then
            NullPointerException exception = assertThrows(
                    NullPointerException.class,
                    () -> RateLimitRule.builder("rule-1")
                            .addBand(null)
            );
            assertTrue(exception.getMessage().contains("band must not be null"));
        }
    }

    // ==================== Immutability Tests ====================

    @Nested
    @DisplayName("Immutability Tests")
    class ImmutabilityTests {

        @Test
        @DisplayName("getBands should return unmodifiable list")
        void getBands_shouldReturnUnmodifiableList() {
            // given
            RateLimitRule rule = RateLimitRule.builder("rule-1")
                    .addBand(createBand(Duration.ofMinutes(1), 100))
                    .build();

            // when
            List<RateLimitBand> bands = rule.getBands();

            // then
            assertThrows(UnsupportedOperationException.class, () ->
                    bands.add(createBand(Duration.ofHours(1), 1000))
            );
        }

        @Test
        @DisplayName("getBands should not allow removal")
        void getBands_shouldNotAllowRemoval() {
            // given
            RateLimitRule rule = RateLimitRule.builder("rule-1")
                    .addBand(createBand(Duration.ofMinutes(1), 100))
                    .addBand(createBand(Duration.ofHours(1), 1000))
                    .build();

            // when
            List<RateLimitBand> bands = rule.getBands();

            // then
            assertThrows(UnsupportedOperationException.class, () -> bands.remove(0));
        }

        @Test
        @DisplayName("getBands should not allow clear")
        void getBands_shouldNotAllowClear() {
            // given
            RateLimitRule rule = RateLimitRule.builder("rule-1")
                    .addBand(createBand(Duration.ofMinutes(1), 100))
                    .build();

            // when
            List<RateLimitBand> bands = rule.getBands();

            // then
            assertThrows(UnsupportedOperationException.class, bands::clear);
        }
    }

    // ==================== Additional Edge Case Tests ====================

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("should allow explicit name override")
        void build_shouldAllowExplicitNameOverride() {
            // given
            String id = "rule-id";
            String customName = "Custom Name Different From ID";

            // when
            RateLimitRule rule = RateLimitRule.builder(id)
                    .name(customName)
                    .addBand(createBand(Duration.ofMinutes(1), 100))
                    .build();

            // then
            assertEquals(id, rule.getId());
            assertEquals(customName, rule.getName());
            assertNotEquals(rule.getId(), rule.getName());
        }

        @Test
        @DisplayName("should allow toggling enabled state")
        void build_shouldAllowTogglingEnabledState() {
            // given / when - disabled
            RateLimitRule disabledRule = RateLimitRule.builder("rule-1")
                    .enabled(false)
                    .addBand(createBand(Duration.ofMinutes(1), 100))
                    .build();

            // then
            assertFalse(disabledRule.isEnabled());

            // given / when - enabled
            RateLimitRule enabledRule = RateLimitRule.builder("rule-2")
                    .enabled(true)
                    .addBand(createBand(Duration.ofMinutes(1), 100))
                    .build();

            // then
            assertTrue(enabledRule.isEnabled());
        }

        @Test
        @DisplayName("should allow all LimitScope values")
        void build_shouldAllowAllLimitScopeValues() {
            // given / when / then
            for (LimitScope scope : LimitScope.values()) {
                RateLimitRule rule = RateLimitRule.builder("rule-" + scope.name())
                        .scope(scope)
                        .addBand(createBand(Duration.ofMinutes(1), 100))
                        .build();
                assertEquals(scope, rule.getScope());
            }
        }

        @Test
        @DisplayName("should allow all OnLimitExceedPolicy values")
        void build_shouldAllowAllOnLimitExceedPolicyValues() {
            // given / when / then
            for (OnLimitExceedPolicy policy : OnLimitExceedPolicy.values()) {
                RateLimitRule rule = RateLimitRule.builder("rule-" + policy.name())
                        .onLimitExceedPolicy(policy)
                        .addBand(createBand(Duration.ofMinutes(1), 100))
                        .build();
                assertEquals(policy, rule.getOnLimitExceedPolicy());
            }
        }
    }
}
