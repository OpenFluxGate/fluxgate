package org.fluxgate.adapter.mongo.converter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.fluxgate.adapter.mongo.model.RateLimitBandDocument;
import org.fluxgate.adapter.mongo.model.RateLimitRuleDocument;
import org.fluxgate.core.config.AccessControl;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.QuotaPeriod;
import org.fluxgate.core.config.RateLimitAlgorithm;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.config.RuleMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link RateLimitRuleConverter}.
 *
 * <p>Tests conversion between core domain objects and MongoDB documents.
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
      RateLimitRule rule =
          RateLimitRule.builder("rule-1")
              .name("Test Rule")
              .enabled(true)
              .scope(LimitScope.PER_IP)
              .keyStrategyId("ip")
              .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
              .addBand(
                  RateLimitBand.builder(Duration.ofMinutes(1), 100).label("per-minute").build())
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
      RateLimitRule rule =
          RateLimitRule.builder("rule-1")
              .name("Multi-Band Rule")
              .scope(LimitScope.PER_IP)
              .keyStrategyId("ip")
              .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
              .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10).label("per-second").build())
              .addBand(
                  RateLimitBand.builder(Duration.ofMinutes(1), 100).label("per-minute").build())
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
      RateLimitRule rule =
          RateLimitRule.builder("rule-1")
              .name("No RuleSet Rule")
              .scope(LimitScope.PER_IP)
              .keyStrategyId("ip")
              .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
              .addBand(
                  RateLimitBand.builder(Duration.ofMinutes(1), 100).label("per-minute").build())
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
      RateLimitBand band =
          RateLimitBand.builder(Duration.ofMinutes(5), 500).label("five-minutes").build();

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

    @Test
    @DisplayName(
        "toDocument (band) normalises the default ZoneOffset.UTC zone to \"UTC\", not \"Z\"")
    void toDocument_normalisesUtcZone() {
      RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100).build();

      assertEquals("UTC", RateLimitRuleConverter.toDocument(band).getZoneId());
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
      RateLimitRuleDocument document =
          new RateLimitRuleDocument(
              "rule-1",
              "Test Rule",
              true,
              LimitScope.PER_IP,
              "ip",
              OnLimitExceedPolicy.REJECT_REQUEST,
              List.of(new RateLimitBandDocument(60, 100, "per-minute")),
              "api-limits");

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
        RateLimitRuleDocument document =
            new RateLimitRuleDocument(
                "rule-1",
                "name",
                true,
                scope,
                "key",
                OnLimitExceedPolicy.REJECT_REQUEST,
                List.of(new RateLimitBandDocument(60, 100, "label")),
                "default");
        RateLimitRule rule = RateLimitRuleConverter.toDomain(document);
        assertEquals(scope, rule.getScope());
      }
    }

    @Test
    @DisplayName("toDomain should convert all policy types")
    void toDomain_shouldConvertAllPolicyTypes() {
      // given / when / then
      for (OnLimitExceedPolicy policy : OnLimitExceedPolicy.values()) {
        RateLimitRuleDocument document =
            new RateLimitRuleDocument(
                "rule-1",
                "name",
                true,
                LimitScope.PER_IP,
                "ip",
                policy,
                List.of(new RateLimitBandDocument(60, 100, "label")),
                "default");
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
      RateLimitRule original =
          RateLimitRule.builder("rule-1")
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

  // ==================== 0.4.0 Field Round-Trip Tests ====================

  @Nested
  @DisplayName("0.4.0 Field Round-Trip Tests")
  class FieldRoundTripTests {

    @Test
    @DisplayName("matcher, priority and band algorithms survive a BSON round-trip")
    void matcherAndAlgorithms_surviveBsonRoundTrip() {
      RuleMatcher matcher =
          RuleMatcher.builder()
              .methods(Set.of("GET", "POST"))
              .addPathPattern("/api/**")
              .addExcludePathPattern("/api/health")
              .headerEquals("X-Tier", "free")
              .headerPresent("X-Api-Key")
              .build();
      RateLimitRule rule =
          RateLimitRule.builder("rule-rt")
              .name("Round trip")
              .scope(LimitScope.PER_API_KEY)
              .keyStrategyId("apiKey")
              .ruleSetId("rs-rt")
              .priority(7)
              .matcher(matcher)
              .addBand(
                  RateLimitBand.builder(Duration.ofMinutes(1), 10)
                      .label("sw")
                      .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
                      .slidingWindowBuckets(6)
                      .build())
              .addBand(
                  RateLimitBand.builder(Duration.ofDays(1), 1000)
                      .label("daily")
                      .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                      .quotaPeriod(QuotaPeriod.DAILY)
                      .zoneId(ZoneId.of("Asia/Seoul"))
                      .build())
              .build();

      Document bson = RateLimitRuleMongoConverter.toBson(RateLimitRuleConverter.toDocument(rule));
      RateLimitRule restored =
          RateLimitRuleConverter.toDomain(RateLimitRuleMongoConverter.fromBson(bson));

      assertEquals(7, restored.getPriority());
      assertEquals(Set.of("GET", "POST"), restored.getMatcher().getMethods());
      assertEquals(List.of("/api/**"), restored.getMatcher().getPathPatterns());
      assertEquals(List.of("/api/health"), restored.getMatcher().getExcludePathPatterns());
      assertEquals(matcher.getHeaderEquals(), restored.getMatcher().getHeaderEquals());
      assertEquals(matcher.getHeaderPresent(), restored.getMatcher().getHeaderPresent());
      assertEquals(matcher, restored.getMatcher());
      assertEquals(rule.getBands(), restored.getBands());
    }

    @Test
    @DisplayName("an unknown algorithm is rejected, never silently enforced as TOKEN_BUCKET")
    void unknownAlgorithm_isRejected() {
      RateLimitBandDocument doc =
          new RateLimitBandDocument(60, 5, "x", "NO_SUCH_ALGORITHM", null, "UTC", 0);

      InvalidRuleDocumentException e =
          assertThrows(
              InvalidRuleDocumentException.class, () -> RateLimitRuleConverter.toDomain(doc));
      assertTrue(e.getMessage().contains("NO_SUCH_ALGORITHM"));
    }

    @Test
    @DisplayName("an invalid zone id is rejected, never silently replaced by UTC")
    void invalidZone_isRejected() {
      RateLimitBandDocument doc =
          new RateLimitBandDocument(60, 5, "x", "TOKEN_BUCKET", null, "Not/AZone", 0);

      assertThrows(InvalidRuleDocumentException.class, () -> RateLimitRuleConverter.toDomain(doc));
    }

    @Test
    @DisplayName("an unknown quota period is rejected like an unknown algorithm")
    void unknownQuotaPeriod_isRejected() {
      RateLimitBandDocument doc =
          new RateLimitBandDocument(60, 5, "x", "FIXED_WINDOW", "FORTNIGHT", "UTC", 0);

      assertThrows(InvalidRuleDocumentException.class, () -> RateLimitRuleConverter.toDomain(doc));
    }

    @Test
    @DisplayName("absent algorithm and zone id still default to the 0.3.x TOKEN_BUCKET and UTC")
    void absentAlgorithmAndZone_defaultTo03x() {
      RateLimitBand band = RateLimitRuleConverter.toDomain(new RateLimitBandDocument(60, 5, "x"));

      assertEquals(RateLimitAlgorithm.TOKEN_BUCKET, band.getAlgorithm());
      assertEquals(java.time.ZoneOffset.UTC, band.getZoneId().normalized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Z", "UTC", "Etc/UTC", "+00:00"})
    @DisplayName("every UTC spelling reads back as the ZoneOffset.UTC default")
    void utcSpellings_readAsZoneOffsetUtc(String zone) {
      RateLimitBand band =
          RateLimitRuleConverter.toDomain(
              new RateLimitBandDocument(60, 5, "x", "TOKEN_BUCKET", null, zone, 0));

      assertSame(ZoneOffset.UTC, band.getZoneId());
    }

    @Test
    @DisplayName("a default band survives toDocument -> toDomain unchanged")
    void defaultBand_roundTripsThroughDocument() {
      RateLimitBand band =
          RateLimitBand.builder(Duration.ofMinutes(1), 100).label("per-minute").build();

      RateLimitBand restored =
          RateLimitRuleConverter.toDomain(RateLimitRuleConverter.toDocument(band));

      assertEquals(band, restored);
      assertSame(ZoneOffset.UTC, restored.getZoneId());
    }

    @Test
    @DisplayName("toAccessControl builds every list that is present")
    void toAccessControl_buildsAllLists() {
      RateLimitRuleDocument doc = createDocument();
      RateLimitRuleConverter.applyAccessControlStrings(
          doc,
          List.of("192.168.0.0/16"),
          List.of("10.0.0.0/8"),
          Set.of("user:admin"),
          Set.of("user:blocked"));

      AccessControl ac = RateLimitRuleConverter.toAccessControl(doc);

      assertTrue(ac.getAllowedIps().contains("192.168.1.1"));
      assertTrue(ac.getDeniedIps().contains("10.1.2.3"));
      assertEquals(Set.of("user:admin"), ac.getAllowedKeys());
      assertEquals(Set.of("user:blocked"), ac.getDeniedKeys());
    }

    @Test
    @DisplayName("toAccessControl with only a denied key list leaves the other lists empty")
    void toAccessControl_partialLists() {
      RateLimitRuleDocument doc = createDocument();
      RateLimitRuleConverter.applyAccessControlStrings(doc, null, null, null, Set.of("key:bad"));

      AccessControl ac = RateLimitRuleConverter.toAccessControl(doc);

      assertFalse(ac.isEmpty());
      assertTrue(ac.getAllowedIps().isEmpty());
      assertTrue(ac.getDeniedIps().isEmpty());
      assertTrue(ac.getAllowedKeys().isEmpty());
      assertEquals(Set.of("key:bad"), ac.getDeniedKeys());
    }

    @Test
    @DisplayName("toAccessControl of a document without lists is EMPTY")
    void toAccessControl_noLists_isEmpty() {
      assertSame(AccessControl.EMPTY, RateLimitRuleConverter.toAccessControl(createDocument()));
    }

    private RateLimitRuleDocument createDocument() {
      return new RateLimitRuleDocument(
          "rule-acl",
          "ACL",
          true,
          LimitScope.PER_IP,
          "ip",
          OnLimitExceedPolicy.REJECT_REQUEST,
          List.of(new RateLimitBandDocument(60, 10, "per-minute")),
          "rs-acl");
    }
  }
}
