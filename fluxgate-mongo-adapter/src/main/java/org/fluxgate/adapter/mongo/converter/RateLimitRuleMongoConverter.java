package org.fluxgate.adapter.mongo.converter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        rule.getRuleSetIdOrNull(),
        rule.getAttributes());
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

    // Convert attributes
    if (doc.getAttributes() != null && !doc.getAttributes().isEmpty()) {
      builder.attributes(doc.getAttributes());
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

  /**
   * Converts a RateLimitRuleDocument to a BSON Document for MongoDB storage.
   *
   * <p>The attributes field is stored as a nested document, allowing MongoDB queries like:
   *
   * <pre>{@code
   * // Find all rules with tier="premium"
   * db.rate_limit_rules.find({"attributes.tier": "premium"})
   *
   * // Find all rules owned by the billing team
   * db.rate_limit_rules.find({"attributes.team": "billing"})
   * }</pre>
   *
   * @param doc the document to convert
   * @return the BSON document
   */
  public static Document toBson(RateLimitRuleDocument doc) {
    List<Document> bandDocs = new ArrayList<>();
    for (RateLimitBandDocument band : doc.getBands()) {
      bandDocs.add(toBson(band));
    }

    Document bson =
        new Document()
            .append("id", doc.getId())
            .append("name", doc.getName())
            .append("enabled", doc.isEnabled())
            .append("scope", doc.getScope().name())
            .append("keyStrategyId", doc.getKeyStrategyId())
            .append("onLimitExceedPolicy", doc.getOnLimitExceedPolicy().name())
            .append("ruleSetId", doc.getRuleSetId())
            .append("bands", bandDocs);

    // Only include attributes if non-empty (avoids storing empty objects in MongoDB)
    if (doc.getAttributes() != null && !doc.getAttributes().isEmpty()) {
      bson.append("attributes", new Document(doc.getAttributes()));
    }

    return bson;
  }

  /**
   * Converts a BSON Document from MongoDB to a RateLimitRuleDocument.
   *
   * <p>Handles missing or null attributes gracefully by returning an empty map.
   *
   * @param doc the BSON document from MongoDB
   * @return the converted RateLimitRuleDocument
   */
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

    // Parse attributes from nested document (may be null if not present)
    Map<String, Object> attributes = parseAttributes(doc.get("attributes", Document.class));

    return new RateLimitRuleDocument(
        id, name, enabled, scope, keyStrategyId, policy, bands, ruleSetId, attributes);
  }

  /**
   * Parses attributes from a BSON Document to a Map.
   *
   * <p>MongoDB stores nested documents as Document objects. This method converts them to a plain
   * Map for easier use in the application layer.
   *
   * @param attrDoc the attributes document (may be null)
   * @return a Map of attributes (empty if input is null)
   */
  private static Map<String, Object> parseAttributes(Document attrDoc) {
    if (attrDoc == null || attrDoc.isEmpty()) {
      return Collections.emptyMap();
    }
    // Create a new HashMap to decouple from BSON Document
    return new HashMap<>(attrDoc);
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
