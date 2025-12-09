package org.fluxgate.adapter.mongo.converter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.fluxgate.adapter.mongo.model.RateLimitBandDocument;
import org.fluxgate.adapter.mongo.model.RateLimitRuleDocument;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RateLimitRuleMongoConverterTest {

  @Test
  @DisplayName("Should convert Domain RateLimitRule to DTO RateLimitRuleDocument")
  void toDto_shouldConvertDomainToDto() {
    // given
    RateLimitRule domainRule =
        RateLimitRule.builder("test-rule")
            .name("Test Rule")
            .enabled(true)
            .scope(LimitScope.PER_API_KEY)
            .keyStrategyId("apiKey")
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 100).label("per-second").build())
            .ruleSetId("test-ruleset")
            .build();

    // when
    RateLimitRuleDocument dto = RateLimitRuleMongoConverter.toDto(domainRule);

    // then
    assertEquals("test-rule", dto.getId());
    assertEquals("Test Rule", dto.getName());
    assertTrue(dto.isEnabled());
    assertEquals(LimitScope.PER_API_KEY, dto.getScope());
    assertEquals("apiKey", dto.getKeyStrategyId());
    assertEquals(OnLimitExceedPolicy.REJECT_REQUEST, dto.getOnLimitExceedPolicy());
    assertEquals("test-ruleset", dto.getRuleSetId());
    assertEquals(1, dto.getBands().size());
    assertEquals(1L, dto.getBands().get(0).getWindowSeconds());
    assertEquals(100L, dto.getBands().get(0).getCapacity());
    assertEquals("per-second", dto.getBands().get(0).getLabel());
  }

  @Test
  @DisplayName("Should convert DTO RateLimitRuleDocument to Domain RateLimitRule")
  void toDomain_shouldConvertDtoToDomain() {
    // given
    RateLimitBandDocument bandDto = new RateLimitBandDocument(60L, 1000L, "per-minute");
    RateLimitRuleDocument dto =
        new RateLimitRuleDocument(
            "test-rule",
            "Test Rule",
            true,
            LimitScope.PER_IP,
            "ip",
            OnLimitExceedPolicy.REJECT_REQUEST,
            List.of(bandDto),
            "test-ruleset");

    // when
    RateLimitRule domainRule = RateLimitRuleMongoConverter.toDomain(dto);

    // then
    assertEquals("test-rule", domainRule.getId());
    assertEquals("Test Rule", domainRule.getName());
    assertTrue(domainRule.isEnabled());
    assertEquals(LimitScope.PER_IP, domainRule.getScope());
    assertEquals("ip", domainRule.getKeyStrategyId());
    assertEquals(OnLimitExceedPolicy.REJECT_REQUEST, domainRule.getOnLimitExceedPolicy());
    assertEquals("test-ruleset", domainRule.getRuleSetIdOrNull());
    assertEquals(1, domainRule.getBands().size());
    assertEquals(Duration.ofSeconds(60), domainRule.getBands().get(0).getWindow());
    assertEquals(1000L, domainRule.getBands().get(0).getCapacity());
    assertEquals("per-minute", domainRule.getBands().get(0).getLabel());
  }

  @Test
  @DisplayName("Should convert RateLimitBand to RateLimitBandDocument")
  void toDto_shouldConvertBandToDto() {
    // given
    RateLimitBand band =
        RateLimitBand.builder(Duration.ofMinutes(5), 500).label("5-minute-window").build();

    // when
    RateLimitBandDocument dto = RateLimitRuleMongoConverter.toDto(band);

    // then
    assertEquals(300L, dto.getWindowSeconds()); // 5 minutes = 300 seconds
    assertEquals(500L, dto.getCapacity());
    assertEquals("5-minute-window", dto.getLabel());
  }

  @Test
  @DisplayName("Should convert RateLimitBandDocument to RateLimitBand")
  void toDomain_shouldConvertBandDtoToDomain() {
    // given
    RateLimitBandDocument dto = new RateLimitBandDocument(3600L, 10000L, "hourly");

    // when
    RateLimitBand band = RateLimitRuleMongoConverter.toDomain(dto);

    // then
    assertEquals(Duration.ofHours(1), band.getWindow());
    assertEquals(10000L, band.getCapacity());
    assertEquals("hourly", band.getLabel());
  }

  @Test
  @DisplayName("Should convert DTO to BSON Document")
  void toBson_shouldConvertDtoToBson() {
    // given
    RateLimitBandDocument bandDto = new RateLimitBandDocument(1L, 100L, "per-second");
    RateLimitRuleDocument dto =
        new RateLimitRuleDocument(
            "bson-rule",
            "BSON Test Rule",
            false,
            LimitScope.GLOBAL,
            "global",
            OnLimitExceedPolicy.REJECT_REQUEST,
            List.of(bandDto),
            "bson-ruleset");

    // when
    Document bson = RateLimitRuleMongoConverter.toBson(dto);

    // then
    assertEquals("bson-rule", bson.getString("id"));
    assertEquals("BSON Test Rule", bson.getString("name"));
    assertFalse(bson.getBoolean("enabled"));
    assertEquals("GLOBAL", bson.getString("scope"));
    assertEquals("global", bson.getString("keyStrategyId"));
    assertEquals("REJECT_REQUEST", bson.getString("onLimitExceedPolicy"));
    assertEquals("bson-ruleset", bson.getString("ruleSetId"));

    @SuppressWarnings("unchecked")
    List<Document> bands = (List<Document>) bson.get("bands");
    assertEquals(1, bands.size());
    assertEquals(1L, bands.get(0).getLong("windowSeconds"));
    assertEquals(100L, bands.get(0).getLong("capacity"));
    assertEquals("per-second", bands.get(0).getString("label"));
  }

  @Test
  @DisplayName("Should convert BSON Document to DTO")
  void fromBson_shouldConvertBsonToDto() {
    // given
    Document bandBson =
        new Document()
            .append("windowSeconds", 60L)
            .append("capacity", 500L)
            .append("label", "test-band");

    Document bson =
        new Document()
            .append("id", "from-bson-rule")
            .append("name", "From BSON Rule")
            .append("enabled", true)
            .append("scope", "PER_USER")
            .append("keyStrategyId", "userId")
            .append("onLimitExceedPolicy", "REJECT_REQUEST")
            .append("ruleSetId", "from-bson-ruleset")
            .append("bands", List.of(bandBson));

    // when
    RateLimitRuleDocument dto = RateLimitRuleMongoConverter.fromBson(bson);

    // then
    assertEquals("from-bson-rule", dto.getId());
    assertEquals("From BSON Rule", dto.getName());
    assertTrue(dto.isEnabled());
    assertEquals(LimitScope.PER_USER, dto.getScope());
    assertEquals("userId", dto.getKeyStrategyId());
    assertEquals(OnLimitExceedPolicy.REJECT_REQUEST, dto.getOnLimitExceedPolicy());
    assertEquals("from-bson-ruleset", dto.getRuleSetId());
    assertEquals(1, dto.getBands().size());
    assertEquals(60L, dto.getBands().get(0).getWindowSeconds());
    assertEquals(500L, dto.getBands().get(0).getCapacity());
    assertEquals("test-band", dto.getBands().get(0).getLabel());
  }

  @Test
  @DisplayName("Should handle multiple bands in conversion")
  void shouldHandleMultipleBandsInConversion() {
    // given
    RateLimitRule domainRule =
        RateLimitRule.builder("multi-band-rule")
            .name("Multi Band Rule")
            .enabled(true)
            .scope(LimitScope.PER_API_KEY)
            .keyStrategyId("apiKey")
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10).label("per-second").build())
            .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).label("per-minute").build())
            .addBand(RateLimitBand.builder(Duration.ofHours(1), 1000).label("per-hour").build())
            .ruleSetId("multi-band-ruleset")
            .build();

    // when - Domain -> DTO -> BSON -> DTO -> Domain
    RateLimitRuleDocument dto1 = RateLimitRuleMongoConverter.toDto(domainRule);
    Document bson = RateLimitRuleMongoConverter.toBson(dto1);
    RateLimitRuleDocument dto2 = RateLimitRuleMongoConverter.fromBson(bson);
    RateLimitRule convertedRule = RateLimitRuleMongoConverter.toDomain(dto2);

    // then
    assertEquals(3, convertedRule.getBands().size());
    assertEquals(Duration.ofSeconds(1), convertedRule.getBands().get(0).getWindow());
    assertEquals(10L, convertedRule.getBands().get(0).getCapacity());
    assertEquals(Duration.ofMinutes(1), convertedRule.getBands().get(1).getWindow());
    assertEquals(100L, convertedRule.getBands().get(1).getCapacity());
    assertEquals(Duration.ofHours(1), convertedRule.getBands().get(2).getWindow());
    assertEquals(1000L, convertedRule.getBands().get(2).getCapacity());
  }

  @Test
  @DisplayName("Should handle round-trip conversion: Domain -> DTO -> BSON -> DTO -> Domain")
  void shouldHandleRoundTripConversion() {
    // given
    RateLimitRule original =
        RateLimitRule.builder("roundtrip-rule")
            .name("Round Trip Test")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .keyStrategyId("ip")
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 50).label("test").build())
            .ruleSetId("roundtrip-ruleset")
            .build();

    // when - Complete round trip
    RateLimitRuleDocument dto1 = RateLimitRuleMongoConverter.toDto(original);
    Document bson = RateLimitRuleMongoConverter.toBson(dto1);
    RateLimitRuleDocument dto2 = RateLimitRuleMongoConverter.fromBson(bson);
    RateLimitRule result = RateLimitRuleMongoConverter.toDomain(dto2);

    // then - Should match original
    assertEquals(original.getId(), result.getId());
    assertEquals(original.getName(), result.getName());
    assertEquals(original.isEnabled(), result.isEnabled());
    assertEquals(original.getScope(), result.getScope());
    assertEquals(original.getKeyStrategyId(), result.getKeyStrategyId());
    assertEquals(original.getOnLimitExceedPolicy(), result.getOnLimitExceedPolicy());
    assertEquals(original.getRuleSetIdOrNull(), result.getRuleSetIdOrNull());
    assertEquals(original.getBands().size(), result.getBands().size());
    assertEquals(original.getBands().get(0).getCapacity(), result.getBands().get(0).getCapacity());
    assertEquals(original.getBands().get(0).getWindow(), result.getBands().get(0).getWindow());
  }

  @Test
  @DisplayName("Should handle disabled rule conversion")
  void shouldHandleDisabledRuleConversion() {
    // given
    RateLimitRule disabledRule =
        RateLimitRule.builder("disabled-rule")
            .name("Disabled Rule")
            .enabled(false) // disabled
            .scope(LimitScope.GLOBAL)
            .keyStrategyId("global")
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 100).label("test").build())
            .ruleSetId("disabled-ruleset")
            .build();

    // when
    RateLimitRuleDocument dto = RateLimitRuleMongoConverter.toDto(disabledRule);
    Document bson = RateLimitRuleMongoConverter.toBson(dto);
    RateLimitRuleDocument convertedDto = RateLimitRuleMongoConverter.fromBson(bson);
    RateLimitRule convertedRule = RateLimitRuleMongoConverter.toDomain(convertedDto);

    // then
    assertFalse(convertedRule.isEnabled());
  }

  @Test
  @DisplayName("Should handle all LimitScope values in conversion")
  void shouldHandleAllLimitScopeValues() {
    LimitScope[] scopes = {
      LimitScope.GLOBAL, LimitScope.PER_IP, LimitScope.PER_USER, LimitScope.PER_API_KEY
    };

    for (LimitScope scope : scopes) {
      // given
      RateLimitRule rule =
          RateLimitRule.builder("scope-test-" + scope)
              .name("Scope Test")
              .enabled(true)
              .scope(scope)
              .keyStrategyId("test")
              .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
              .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 100).label("test").build())
              .ruleSetId("scope-test-ruleset")
              .build();

      // when
      RateLimitRuleDocument dto = RateLimitRuleMongoConverter.toDto(rule);
      Document bson = RateLimitRuleMongoConverter.toBson(dto);
      RateLimitRuleDocument convertedDto = RateLimitRuleMongoConverter.fromBson(bson);
      RateLimitRule convertedRule = RateLimitRuleMongoConverter.toDomain(convertedDto);

      // then
      assertEquals(scope, convertedRule.getScope(), "Scope " + scope + " should be preserved");
    }
  }
}
