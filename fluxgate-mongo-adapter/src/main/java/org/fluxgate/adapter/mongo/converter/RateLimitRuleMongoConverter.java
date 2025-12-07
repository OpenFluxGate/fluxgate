package org.fluxgate.adapter.mongo.converter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.fluxgate.adapter.mongo.model.RateLimitBandDocument;
import org.fluxgate.adapter.mongo.model.RateLimitRuleDocument;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;

public final class RateLimitRuleMongoConverter {

  private RateLimitRuleMongoConverter() {}

  /* ========= Domain <-> DTO ========= */

  public static RateLimitRuleDocument toDto(RateLimitRule rule) {
    List<RateLimitBandDocument> bandDocs = new ArrayList<>();
    for (RateLimitBand band : rule.getBands()) {
      bandDocs.add(toDto(band));
    }

    return new RateLimitRuleDocument(
        rule.getId(),
        rule.getName(),
        rule.isEnabled(),
        rule.getScope(),
        rule.getKeyStrategyId(),
        rule.getOnLimitExceedPolicy(),
        bandDocs,
        rule.getRuleSetIdOrNull());
  }

  public static RateLimitRule toDomain(RateLimitRuleDocument doc) {
    RateLimitRule.Builder builder =
        RateLimitRule.builder(doc.getId())
            .name(doc.getName())
            .enabled(doc.isEnabled())
            .scope(doc.getScope())
            .keyStrategyId(doc.getKeyStrategyId())
            .onLimitExceedPolicy(doc.getOnLimitExceedPolicy())
            .ruleSetId(doc.getRuleSetId());

    for (RateLimitBandDocument bandDoc : doc.getBands()) {
      builder.addBand(toDomain(bandDoc));
    }
    return builder.build();
  }

  public static RateLimitBandDocument toDto(RateLimitBand band) {
    // Assumes current Band design with windowSeconds + capacity + label
    long windowSeconds = band.getWindow().getSeconds();
    long capacity = band.getCapacity();
    String label = band.getLabel();

    return new RateLimitBandDocument(windowSeconds, capacity, label);
  }

  public static RateLimitBand toDomain(RateLimitBandDocument doc) {
    return RateLimitBand.builder(Duration.ofSeconds(doc.getWindowSeconds()), doc.getCapacity())
        .label(doc.getLabel())
        .build();
  }

  /* ========= DTO ↔ Bson(Document) ========= */

  public static Document toBson(RateLimitRuleDocument doc) {
    List<Document> bandDocs = new ArrayList<>();
    for (RateLimitBandDocument band : doc.getBands()) {
      bandDocs.add(toBson(band));
    }

    return new Document()
        .append("id", doc.getId())
        .append("name", doc.getName())
        .append("enabled", doc.isEnabled())
        .append("scope", doc.getScope().name())
        .append("keyStrategyId", doc.getKeyStrategyId())
        .append("onLimitExceedPolicy", doc.getOnLimitExceedPolicy().name())
        .append("ruleSetId", doc.getRuleSetId())
        .append("bands", bandDocs);
  }

  public static RateLimitRuleDocument fromBson(Document doc) {
    String id = doc.getString("id");
    String name = doc.getString("name");
    boolean enabled = doc.getBoolean("enabled", true);
    LimitScope scope = LimitScope.valueOf(doc.getString("scope"));
    String keyStrategyId = doc.getString("keyStrategyId");
    OnLimitExceedPolicy policy = OnLimitExceedPolicy.valueOf(doc.getString("onLimitExceedPolicy"));
    String ruleSetId = doc.getString("ruleSetId");

    @SuppressWarnings("unchecked")
    List<Document> bandDocs = (List<Document>) doc.get("bands");
    List<RateLimitBandDocument> bands = new ArrayList<>();
    if (bandDocs != null) {
      for (Document bd : bandDocs) {
        bands.add(fromBsonBand(bd));
      }
    }

    return new RateLimitRuleDocument(
        id, name, enabled, scope, keyStrategyId, policy, bands, ruleSetId);
  }

  private static Document toBson(RateLimitBandDocument doc) {
    return new Document()
        .append("windowSeconds", doc.getWindowSeconds())
        .append("capacity", doc.getCapacity())
        .append("label", doc.getLabel());
  }

  private static RateLimitBandDocument fromBsonBand(Document d) {
    long windowSeconds = d.getLong("windowSeconds");
    long capacity = d.getLong("capacity");
    String label = d.getString("label");

    return new RateLimitBandDocument(windowSeconds, capacity, label);
  }
}
