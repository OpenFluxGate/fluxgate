package org.fluxgate.adapter.mongo.converter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.fluxgate.adapter.mongo.model.RateLimitBandDocument;
import org.fluxgate.adapter.mongo.model.RateLimitRuleDocument;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;

/**
 * Converts between {@link RateLimitRuleDocument} / {@link RateLimitBandDocument} DTOs and BSON
 * {@link Document} objects for MongoDB storage.
 *
 * <p>All 0.4.0 fields ({@code priority}, matcher fields, access-control lists, band {@code
 * algorithm} / {@code quotaPeriod} / {@code zoneId} / {@code slidingWindowBuckets}) are persisted
 * when present and defaulted gracefully when absent (backward compatibility with 0.3.x documents).
 */
public final class RateLimitRuleMongoConverter {

  private RateLimitRuleMongoConverter() {}

  /* ========= Domain <-> DTO ========= */

  /**
   * Converts a core {@link RateLimitRule} to a {@link RateLimitRuleDocument} DTO.
   *
   * @param rule the rule (may be null → returns null)
   * @return the DTO, or null
   */
  public static RateLimitRuleDocument toDto(RateLimitRule rule) {
    return RateLimitRuleConverter.toDocument(rule);
  }

  /**
   * Converts a {@link RateLimitRuleDocument} DTO to a core {@link RateLimitRule}.
   *
   * @param doc the DTO (may be null → returns null)
   * @return the domain rule, or null
   */
  public static RateLimitRule toDomain(RateLimitRuleDocument doc) {
    return RateLimitRuleConverter.toDomain(doc);
  }

  /**
   * Converts a core {@link RateLimitBand} to a {@link RateLimitBandDocument} DTO.
   *
   * @param band the band (may be null → returns null)
   * @return the DTO, or null
   */
  public static RateLimitBandDocument toDto(RateLimitBand band) {
    if (band == null) {
      return null;
    }
    return RateLimitRuleConverter.toDocument(band);
  }

  /**
   * Converts a {@link RateLimitBandDocument} DTO to a core {@link RateLimitBand}.
   *
   * @param doc the DTO (may be null → returns null)
   * @return the domain band, or null
   */
  public static RateLimitBand toDomain(RateLimitBandDocument doc) {
    if (doc == null) {
      return null;
    }
    return RateLimitRuleConverter.toDomain(doc);
  }

  /* ========= DTO ↔ Bson(Document) ========= */

  /**
   * Converts a {@link RateLimitRuleDocument} DTO to a BSON {@link Document} for MongoDB storage.
   *
   * <p>All 0.4.0 fields are included when non-default so that a document read back on 0.3.x still
   * loads (new fields are simply ignored). The attributes field is stored as a nested document.
   *
   * <p>Access-control lists are stored as top-level arrays on the rule document. All rules in a
   * rule set carry the same lists; the repository uses the first rule it finds.
   *
   * @param doc the DTO to convert (must not be null)
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

    // ===== 0.4.0 matcher fields =====
    bson.append("priority", doc.getPriority());

    List<String> methods = doc.getMethods();
    if (!methods.isEmpty()) {
      bson.append("methods", new ArrayList<>(methods));
    }
    List<String> pathPatterns = doc.getPathPatterns();
    if (!pathPatterns.isEmpty()) {
      bson.append("pathPatterns", new ArrayList<>(pathPatterns));
    }
    List<String> excludePathPatterns = doc.getExcludePathPatterns();
    if (!excludePathPatterns.isEmpty()) {
      bson.append("excludePathPatterns", new ArrayList<>(excludePathPatterns));
    }
    Map<String, String> headerEquals = doc.getHeaderEquals();
    if (!headerEquals.isEmpty()) {
      bson.append("headerEquals", new Document(headerEquals));
    }
    List<String> headerPresent = doc.getHeaderPresent();
    if (!headerPresent.isEmpty()) {
      bson.append("headerPresent", new ArrayList<>(headerPresent));
    }

    // ===== 0.4.0 access-control fields =====
    List<String> allowedIps = doc.getAllowedIps();
    if (!allowedIps.isEmpty()) {
      bson.append("allowedIps", new ArrayList<>(allowedIps));
    }
    List<String> deniedIps = doc.getDeniedIps();
    if (!deniedIps.isEmpty()) {
      bson.append("deniedIps", new ArrayList<>(deniedIps));
    }
    Set<String> allowedKeys = doc.getAllowedKeys();
    if (!allowedKeys.isEmpty()) {
      bson.append("allowedKeys", new ArrayList<>(allowedKeys));
    }
    Set<String> deniedKeys = doc.getDeniedKeys();
    if (!deniedKeys.isEmpty()) {
      bson.append("deniedKeys", new ArrayList<>(deniedKeys));
    }

    return bson;
  }

  /**
   * Converts a BSON {@link Document} from MongoDB to a {@link RateLimitRuleDocument} DTO.
   *
   * <p>Missing fields default to their 0.3.x equivalents so that old documents round-trip
   * correctly:
   *
   * <ul>
   *   <li>{@code priority} → {@code 0}
   *   <li>matcher fields → empty (match-all)
   *   <li>access-control fields → empty (no restrictions)
   * </ul>
   *
   * @param doc the BSON document from MongoDB (must not be null)
   * @return the converted DTO
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

    Map<String, Object> attributes = parseAttributes(doc.get("attributes", Document.class));

    RateLimitRuleDocument ruleDoc =
        new RateLimitRuleDocument(
            id, name, enabled, scope, keyStrategyId, policy, bands, ruleSetId, attributes);

    // ===== 0.4.0 matcher fields =====
    int priority = doc.getInteger("priority", 0);

    @SuppressWarnings("unchecked")
    List<String> methods = (List<String>) doc.get("methods");

    @SuppressWarnings("unchecked")
    List<String> pathPatterns = (List<String>) doc.get("pathPatterns");

    @SuppressWarnings("unchecked")
    List<String> excludePathPatterns = (List<String>) doc.get("excludePathPatterns");

    Map<String, String> headerEquals = parseStringMap(doc.get("headerEquals", Document.class));

    @SuppressWarnings("unchecked")
    List<String> headerPresent = (List<String>) doc.get("headerPresent");

    ruleDoc.withMatcher(
        priority, methods, pathPatterns, excludePathPatterns, headerEquals, headerPresent);

    // ===== 0.4.0 access-control fields =====
    @SuppressWarnings("unchecked")
    List<String> allowedIps = (List<String>) doc.get("allowedIps");

    @SuppressWarnings("unchecked")
    List<String> deniedIps = (List<String>) doc.get("deniedIps");

    @SuppressWarnings("unchecked")
    List<String> allowedKeysList = (List<String>) doc.get("allowedKeys");
    Set<String> allowedKeys = allowedKeysList != null ? new HashSet<>(allowedKeysList) : null;

    @SuppressWarnings("unchecked")
    List<String> deniedKeysList = (List<String>) doc.get("deniedKeys");
    Set<String> deniedKeys = deniedKeysList != null ? new HashSet<>(deniedKeysList) : null;

    ruleDoc.withAccessControl(allowedIps, deniedIps, allowedKeys, deniedKeys);

    return ruleDoc;
  }

  /**
   * Converts a BSON band document to a {@link RateLimitBandDocument} DTO.
   *
   * <p>Missing 0.4.0 fields ({@code algorithm}, {@code quotaPeriod}, {@code zoneId}, {@code
   * slidingWindowBuckets}) default to the 0.3.x equivalents ({@code TOKEN_BUCKET}, null, {@code
   * UTC}, {@code 10}).
   *
   * @param d the BSON band document (must not be null)
   * @return the DTO
   */
  public static RateLimitBandDocument fromBsonBand(Document d) {
    long windowSeconds = d.getLong("windowSeconds");
    long capacity = d.getLong("capacity");
    String label = d.getString("label");

    // 0.4.0 fields with safe defaults for backward compatibility
    String algorithm = d.getString("algorithm");
    String quotaPeriod = d.getString("quotaPeriod");
    String zoneId = d.getString("zoneId");
    Integer slidingWindowBuckets = d.getInteger("slidingWindowBuckets");

    return new RateLimitBandDocument(
        windowSeconds,
        capacity,
        label,
        algorithm != null ? algorithm : "TOKEN_BUCKET",
        quotaPeriod,
        zoneId != null ? zoneId : "UTC",
        slidingWindowBuckets != null ? slidingWindowBuckets : 10);
  }

  private static Document toBson(RateLimitBandDocument doc) {
    Document bson =
        new Document()
            .append("windowSeconds", doc.getWindowSeconds())
            .append("capacity", doc.getCapacity())
            .append("label", doc.getLabel());

    // 0.4.0 fields — only persist non-default values to minimise document bloat
    String algorithm = doc.getAlgorithm();
    if (algorithm != null && !algorithm.equals("TOKEN_BUCKET")) {
      bson.append("algorithm", algorithm);
    } else {
      // Always persist algorithm for clarity (new documents)
      bson.append("algorithm", algorithm != null ? algorithm : "TOKEN_BUCKET");
    }

    String quotaPeriod = doc.getQuotaPeriod();
    if (quotaPeriod != null) {
      bson.append("quotaPeriod", quotaPeriod);
    }

    String zoneId = doc.getZoneId();
    if (zoneId != null && !zoneId.equals("UTC")) {
      bson.append("zoneId", zoneId);
    }

    int swBuckets = doc.getSlidingWindowBuckets();
    if (swBuckets != 10) {
      bson.append("slidingWindowBuckets", swBuckets);
    }

    return bson;
  }

  private static Map<String, Object> parseAttributes(Document attrDoc) {
    if (attrDoc == null || attrDoc.isEmpty()) {
      return Collections.emptyMap();
    }
    return new HashMap<>(attrDoc);
  }

  private static Map<String, String> parseStringMap(Document mapDoc) {
    if (mapDoc == null || mapDoc.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, String> result = new HashMap<>();
    for (Map.Entry<String, Object> e : mapDoc.entrySet()) {
      if (e.getValue() != null) {
        result.put(e.getKey(), e.getValue().toString());
      }
    }
    return result;
  }
}
