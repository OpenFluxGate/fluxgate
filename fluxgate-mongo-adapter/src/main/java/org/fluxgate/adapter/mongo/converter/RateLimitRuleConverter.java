package org.fluxgate.adapter.mongo.converter;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.fluxgate.adapter.mongo.model.RateLimitBandDocument;
import org.fluxgate.adapter.mongo.model.RateLimitRuleDocument;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;

/** Converts between core domain objects and MongoDB documents. */
public final class RateLimitRuleConverter {

  private RateLimitRuleConverter() {}

  /** Convert a MongoDB document to a core domain object. */
  public static RateLimitRule toDomain(RateLimitRuleDocument doc) {
    if (doc == null) {
      return null;
    }

    RateLimitRule.Builder builder =
        RateLimitRule.builder(doc.getId())
            .name(doc.getName())
            .enabled(doc.isEnabled())
            .scope(doc.getScope())
            .keyStrategyId(doc.getKeyStrategyId())
            .onLimitExceedPolicy(doc.getOnLimitExceedPolicy())
            .ruleSetId(doc.getRuleSetId());

    // Convert bands
    if (doc.getBands() != null) {
      for (RateLimitBandDocument bandDoc : doc.getBands()) {
        builder.addBand(toDomain(bandDoc));
      }
    }

    // Convert attributes (pass through as-is)
    if (doc.getAttributes() != null && !doc.getAttributes().isEmpty()) {
      builder.attributes(doc.getAttributes());
    }

    return builder.build();
  }

  /** Convert a core domain object to a MongoDB document. */
  public static RateLimitRuleDocument toDocument(RateLimitRule rule) {
    if (rule == null) {
      return null;
    }

    List<RateLimitBandDocument> bandDocs =
        rule.getBands().stream()
            .map(RateLimitRuleConverter::toDocument)
            .collect(Collectors.toList());

    return new RateLimitRuleDocument(
        rule.getId(),
        rule.getName(),
        rule.isEnabled(),
        rule.getScope(),
        rule.getKeyStrategyId(),
        rule.getOnLimitExceedPolicy(),
        bandDocs,
        rule.getRuleSetIdOrNull() != null ? rule.getRuleSetIdOrNull() : "default",
        rule.getAttributes());
  }

  /** Convert a band document to a core band. */
  public static RateLimitBand toDomain(RateLimitBandDocument doc) {
    return RateLimitBand.builder(Duration.ofSeconds(doc.getWindowSeconds()), doc.getCapacity())
        .label(doc.getLabel())
        .build();
  }

  /** Convert a core band to a band document. */
  public static RateLimitBandDocument toDocument(RateLimitBand band) {
    return new RateLimitBandDocument(
        band.getWindow().toSeconds(),
        band.getCapacity(),
        band.getLabel() != null ? band.getLabel() : "default");
  }
}
