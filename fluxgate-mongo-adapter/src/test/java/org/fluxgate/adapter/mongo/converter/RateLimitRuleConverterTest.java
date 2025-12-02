package org.fluxgate.adapter.mongo.converter;

import org.fluxgate.adapter.mongo.model.RateLimitBandDocument;
import org.fluxgate.adapter.mongo.model.RateLimitRuleDocument;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RateLimitRuleConverter}.
 * <p>
 * Tests conversion between core domain objects and MongoDB documents.
 */
@DisplayName("RateLimitRuleConverter Tests")
class RateLimitRuleConverterTest {

    // ==================== Domain to Document Tests ====================

    @Nested
    @DisplayName("Domain to Document Conversion Tests")
    class DomainToDocumentTests {

        @Test
        @DisplayName("toDocument should convert RateLimitRule to RateLimitRuleDocument")
        void toDocument_shouldConvertRuleToRuleDocument() {
            // given
            RateLimitRule rule = RateLimitRule.builder("rule-1")
                    .name("Test Rule")
                    .enabled(true)
                    .scope(LimitScope.PER_IP)
                    .keyStrategyId("ip")
                    .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
                    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).label("per-minute").build())
                    .ruleSetId("api-limits")
                    .build();

            // when
            RateLimitRuleDocument document = RateLimitRuleConverter.toDocument(rule);

            // then
            assertEquals("rule-1", document.getId());
            assertEquals("Test Rule", document.getName());
            assertTrue(document.isEnabled());
            assertEquals(LimitScope.PER_IP, document.getScope());
            assertEquals("ip", document.getKeyStrategyId());
            assertEquals(OnLimitExceedPolicy.REJECT_REQUEST, document.getOnLimitExceedPolicy());
            assertEquals("api-limits", document.getRuleSetId());
            assertEquals(1, document.getBands().size());
        }

        @Test
        @DisplayName("toDocument should convert multiple bands")
        void toDocument_shouldConvertMultipleBands() {
            // given
            RateLimitRule rule = RateLimitRule.builder("rule-1")
                    .name("Multi-Band Rule")
                    .scope(LimitScope.PER_IP)
                    .keyStrategyId("ip")
                    .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
                    .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10).label("per-second").build())
                    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).label("per-minute").build())
                    .addBand(RateLimitBand.builder(Duration.ofHours(1), 1000).label("per-hour").build())
                    .ruleSetId("multi-band")
                    .build();

            // when
            RateLimitRuleDocument document = RateLimitRuleConverter.toDocument(rule);

            // then
            assertEquals(3, document.getBands().size());
            assertEquals(1, document.getBands().get(0).getWindowSeconds());
            assertEquals(60, document.getBands().get(1).getWindowSeconds());
            assertEquals(3600, document.getBands().get(2).getWindowSeconds());
        }

        @Test
        @DisplayName("toDocument should handle null ruleSetId by using 'default'")
        void toDocument_shouldHandleNullRuleSetIdByUsingDefault() {
            // given
            RateLimitRule rule = RateLimitRule.builder("rule-1")
                    .name("No RuleSet Rule")
                    .scope(LimitScope.PER_IP)
                    .keyStrategyId("ip")
                    .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
                    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).label("per-minute").build())
                    .build();

            // when
            RateLimitRuleDocument document = RateLimitRuleConverter.toDocument(rule);

            // then
            assertEquals("default", document.getRuleSetId());
        }

        @Test
        @DisplayName("toDocument should return null for null rule")
        void toDocument_shouldReturnNullForNullRule() {
            // given / when / then
            assertNull(RateLimitRuleConverter.toDocument((RateLimitRule) null));
        }

        @Test
        @DisplayName("toDocument (band) should convert Duration to seconds")
        void toDocument_shouldConvertDurationToSeconds() {
            // given
            RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(5), 500)
                    .label("five-minutes")
                    .build();

            // when
            RateLimitBandDocument document = RateLimitRuleConverter.toDocument(band);

            // then
            assertEquals(300, document.getWindowSeconds()); // 5 minutes = 300 seconds
            assertEquals(500, document.getCapacity());
            assertEquals("five-minutes", document.getLabel());
        }

        @Test
        @DisplayName("toDocument (band) should use 'default' label when label is null")
        void toDocument_shouldUseDefaultLabelWhenLabelIsNull() {
            // given
            RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100).build();

            // when
            RateLimitBandDocument document = RateLimitRuleConverter.toDocument(band);

            // then
            assertEquals("default", document.getLabel());
        }
    }

    // ==================== Document to Domain Tests ====================

    @Nested
    @DisplayName("Document to Domain Conversion Tests")
    class DocumentToDomainTests {

        @Test
        @DisplayName("toDomain should convert RateLimitRuleDocument to RateLimitRule")
        void toDomain_shouldConvertDocumentToRule() {
            // given
            RateLimitRuleDocument document = new RateLimitRuleDocument(
                    "rule-1",
                    "Test Rule",
                    true,
                    LimitScope.PER_IP,
                    "ip",
                    OnLimitExceedPolicy.REJECT_REQUEST,
                    List.of(new RateLimitBandDocument(60, 100, "per-minute")),
                    "api-limits"
            );

            // when
            RateLimitRule rule = RateLimitRuleConverter.toDomain(document);

            // then
            assertEquals("rule-1", rule.getId());
            assertEquals("Test Rule", rule.getName());
            assertTrue(rule.isEnabled());
            assertEquals(LimitScope.PER_IP, rule.getScope());
            assertEquals("ip", rule.getKeyStrategyId());
            assertEquals(OnLimitExceedPolicy.REJECT_REQUEST, rule.getOnLimitExceedPolicy());
            assertEquals("api-limits", rule.getRuleSetIdOrNull());
            assertEquals(1, rule.getBands().size());
        }

        @Test
        @DisplayName("toDomain should convert all scope types")
        void toDomain_shouldConvertAllScopeTypes() {
            // given / when / then
            for (LimitScope scope : LimitScope.values()) {
                RateLimitRuleDocument document = new RateLimitRuleDocument(
                        "rule-1", "name", true, scope, "key", OnLimitExceedPolicy.REJECT_REQUEST,
                        List.of(new RateLimitBandDocument(60, 100, "label")), "default"
                );
                RateLimitRule rule = RateLimitRuleConverter.toDomain(document);
                assertEquals(scope, rule.getScope());
            }
        }

        @Test
        @DisplayName("toDomain should convert all policy types")
        void toDomain_shouldConvertAllPolicyTypes() {
            // given / when / then
            for (OnLimitExceedPolicy policy : OnLimitExceedPolicy.values()) {
                RateLimitRuleDocument document = new RateLimitRuleDocument(
                        "rule-1", "name", true, LimitScope.PER_IP, "ip", policy,
                        List.of(new RateLimitBandDocument(60, 100, "label")), "default"
                );
                RateLimitRule rule = RateLimitRuleConverter.toDomain(document);
                assertEquals(policy, rule.getOnLimitExceedPolicy());
            }
        }

        @Test
        @DisplayName("toDomain (band) should convert seconds to Duration")
        void toDomain_shouldConvertSecondsToDuration() {
            // given
            RateLimitBandDocument document = new RateLimitBandDocument(300, 500, "five-minutes");

            // when
            RateLimitBand band = RateLimitRuleConverter.toDomain(document);

            // then
            assertEquals(Duration.ofMinutes(5), band.getWindow());
            assertEquals(500, band.getCapacity());
            assertEquals("five-minutes", band.getLabel());
        }

        @Test
        @DisplayName("toDomain should return null for null document")
        void toDomain_shouldReturnNullForNullDocument() {
            // given / when / then
            assertNull(RateLimitRuleConverter.toDomain((RateLimitRuleDocument) null));
        }
    }

    // ==================== Round-trip Conversion Tests ====================

    @Nested
    @DisplayName("Round-trip Conversion Tests")
    class RoundTripTests {

        @Test
        @DisplayName("should preserve all fields in round-trip conversion")
        void roundTrip_shouldPreserveAllFields() {
            // given
            RateLimitRule original = RateLimitRule.builder("rule-1")
                    .name("Round Trip Test")
                    .enabled(false)
                    .scope(LimitScope.PER_USER)
                    .keyStrategyId("userId")
                    .onLimitExceedPolicy(OnLimitExceedPolicy.WAIT_FOR_REFILL)
                    .addBand(RateLimitBand.builder(Duration.ofSeconds(30), 50).label("burst").build())
                    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).label("sustained").build())
                    .ruleSetId("user-limits")
                    .build();

            // when
            RateLimitRuleDocument document = RateLimitRuleConverter.toDocument(original);
            RateLimitRule restored = RateLimitRuleConverter.toDomain(document);

            // then
            assertEquals(original.getId(), restored.getId());
            assertEquals(original.getName(), restored.getName());
            assertEquals(original.isEnabled(), restored.isEnabled());
            assertEquals(original.getScope(), restored.getScope());
            assertEquals(original.getKeyStrategyId(), restored.getKeyStrategyId());
            assertEquals(original.getOnLimitExceedPolicy(), restored.getOnLimitExceedPolicy());
            assertEquals(original.getRuleSetIdOrNull(), restored.getRuleSetIdOrNull());
            assertEquals(original.getBands().size(), restored.getBands().size());

            // Verify bands
            for (int i = 0; i < original.getBands().size(); i++) {
                RateLimitBand originalBand = original.getBands().get(i);
                RateLimitBand restoredBand = restored.getBands().get(i);
                assertEquals(originalBand.getWindow(), restoredBand.getWindow());
                assertEquals(originalBand.getCapacity(), restoredBand.getCapacity());
                assertEquals(originalBand.getLabel(), restoredBand.getLabel());
            }
        }
    }
}
