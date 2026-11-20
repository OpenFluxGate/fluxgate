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
 *
 * <p>Numeric fields are read through {@link Number}, so a value written as Int32, Int64 or an
 * integral Double (as the mongo shell and many tools do) loads the same way. A field of the wrong
 * type, a fractional or out-of-range number, or an unknown enum value is reported as an {@link
 * InvalidRuleDocumentException} rather than a {@link ClassCastException} or a silent default.
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
   * rule set carry the same lists; when copies diverge the repository merges them per list across
   * every copy of the rule set (union for deny lists, intersection for allow lists, a copy without
   * that list counting as empty), so the result does not depend on document order. The repository's
   * {@code aclUpdatedAt} marker, which makes a copy count even with no list, is not written here.
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
    String id = readString(doc, "id");
    String name = readString(doc, "name");
    boolean enabled = readBoolean(doc, "enabled", true);
    LimitScope scope = readEnum(doc, "scope", LimitScope.class);
    String keyStrategyId = readString(doc, "keyStrategyId");
    OnLimitExceedPolicy policy = readEnum(doc, "onLimitExceedPolicy", OnLimitExceedPolicy.class);
    String ruleSetId = readString(doc, "ruleSetId");

    List<RateLimitBandDocument> bands = new ArrayList<>();
    Object bandsValue = doc.get("bands");
    if (bandsValue != null) {
      if (!(bandsValue instanceof List)) {
        throw invalid("bands", "an array", bandsValue);
      }
      for (Object bd : (List<?>) bandsValue) {
        if (!(bd instanceof Document)) {
          throw invalid("bands[]", "an embedded document", bd);
        }
        bands.add(fromBsonBand((Document) bd));
      }
    }

    Map<String, Object> attributes = parseAttributes(readDocument(doc, "attributes"));

    RateLimitRuleDocument ruleDoc =
        new RateLimitRuleDocument(
            id, name, enabled, scope, keyStrategyId, policy, bands, ruleSetId, attributes);

    // ===== 0.4.0 matcher fields =====
    int priority = readInt(doc, "priority", 0);

    List<String> methods = readStringList(doc, "methods");
    List<String> pathPatterns = readStringList(doc, "pathPatterns");
    List<String> excludePathPatterns = readStringList(doc, "excludePathPatterns");
    Map<String, String> headerEquals = parseStringMap(readDocument(doc, "headerEquals"));
    List<String> headerPresent = readStringList(doc, "headerPresent");

    ruleDoc.withMatcher(
        priority, methods, pathPatterns, excludePathPatterns, headerEquals, headerPresent);

    // ===== 0.4.0 access-control fields =====
    List<String> allowedIps = readStringList(doc, "allowedIps");
    List<String> deniedIps = readStringList(doc, "deniedIps");
    List<String> allowedKeysList = readStringList(doc, "allowedKeys");
    Set<String> allowedKeys = allowedKeysList != null ? new HashSet<>(allowedKeysList) : null;
    List<String> deniedKeysList = readStringList(doc, "deniedKeys");
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
    long windowSeconds = readLong(d, "windowSeconds");
    long capacity = readLong(d, "capacity");
    String label = readString(d, "label");

    // 0.4.0 fields with safe defaults for backward compatibility; an empty string is "absent"
    String algorithm = emptyToNull(readString(d, "algorithm"));
    String quotaPeriod = emptyToNull(readString(d, "quotaPeriod"));
    String zoneId = emptyToNull(readString(d, "zoneId"));
    int slidingWindowBuckets = readInt(d, "slidingWindowBuckets", 10);

    try {
      return new RateLimitBandDocument(
          windowSeconds,
          capacity,
          label,
          algorithm != null ? algorithm : "TOKEN_BUCKET",
          quotaPeriod,
          zoneId != null ? zoneId : "UTC",
          slidingWindowBuckets);
    } catch (IllegalArgumentException e) {
      throw new InvalidRuleDocumentException("Invalid band: " + e.getMessage(), e);
    }
  }

  /* ========= tolerant, type-checked BSON readers ========= */

  private static InvalidRuleDocumentException invalid(String field, String expected, Object value) {
    return new InvalidRuleDocumentException(
        "Field '"
            + field
            + "' must be "
            + expected
            + " but is "
            + (value == null ? "null" : value.getClass().getSimpleName()));
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  private static String readString(Document d, String field) {
    Object value = d.get(field);
    if (value == null || value instanceof String) {
      return (String) value;
    }
    throw invalid(field, "a string", value);
  }

  private static boolean readBoolean(Document d, String field, boolean defaultValue) {
    Object value = d.get(field);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    throw invalid(field, "a boolean", value);
  }

  private static Document readDocument(Document d, String field) {
    Object value = d.get(field);
    if (value == null || value instanceof Document) {
      return (Document) value;
    }
    throw invalid(field, "an embedded document", value);
  }

  private static <E extends Enum<E>> E readEnum(Document d, String field, Class<E> type) {
    String value = readString(d, field);
    if (value == null) {
      throw new InvalidRuleDocumentException("Field '" + field + "' is missing");
    }
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException e) {
      throw new InvalidRuleDocumentException(
          "Field '" + field + "' has unknown " + type.getSimpleName() + " '" + value + "'", e);
    }
  }

  /** Reads a required integral number stored as Int32, Int64 or an integral Double. */
  private static long readLong(Document d, String field) {
    Object value = d.get(field);
    if (value == null) {
      throw new InvalidRuleDocumentException("Field '" + field + "' is missing");
    }
    return toLong(field, value);
  }

  private static int readInt(Document d, String field, int defaultValue) {
    Object value = d.get(field);
    if (value == null) {
      return defaultValue;
    }
    long asLong = toLong(field, value);
    if (asLong < Integer.MIN_VALUE || asLong > Integer.MAX_VALUE) {
      throw new InvalidRuleDocumentException(
          "Field '" + field + "' is out of the int range: " + asLong);
    }
    return (int) asLong;
  }

  private static long toLong(String field, Object value) {
    if (value instanceof Integer || value instanceof Long) {
      return ((Number) value).longValue();
    }
    if (value instanceof Double) {
      double d = (Double) value;
      if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)) {
        throw new InvalidRuleDocumentException(
            "Field '" + field + "' must be a whole number but is " + d);
      }
      if (d < Long.MIN_VALUE || d > Long.MAX_VALUE) {
        throw new InvalidRuleDocumentException("Field '" + field + "' is out of range: " + d);
      }
      return (long) d;
    }
    if (value instanceof org.bson.types.Decimal128) {
      try {
        return ((org.bson.types.Decimal128) value).bigDecimalValue().longValueExact();
      } catch (ArithmeticException e) {
        throw new InvalidRuleDocumentException(
            "Field '" + field + "' must be a whole number in the long range but is " + value, e);
      }
    }
    throw invalid(field, "a number", value);
  }

  private static List<String> readStringList(Document d, String field) {
    Object value = d.get(field);
    if (value == null) {
      return null;
    }
    if (!(value instanceof List)) {
      throw invalid(field, "an array of strings", value);
    }
    List<String> result = new ArrayList<>();
    for (Object element : (List<?>) value) {
      if (!(element instanceof String)) {
        throw invalid(field + "[]", "a string", element);
      }
      result.add((String) element);
    }
    return result;
  }

  private static Document toBson(RateLimitBandDocument doc) {
    Document bson =
        new Document()
            .append("windowSeconds", doc.getWindowSeconds())
            .append("capacity", doc.getCapacity())
            .append("label", doc.getLabel());

    // 0.4.0 fields: the algorithm always (getAlgorithm() defaults to TOKEN_BUCKET), so a document
    // says which algorithm it uses; the others only when they differ from their default.
    bson.append("algorithm", doc.getAlgorithm());

    String quotaPeriod = doc.getQuotaPeriod();
    if (quotaPeriod != null) {
      bson.append("quotaPeriod", quotaPeriod);
    }

    // UTC is the default under any spelling ("Z" from ZoneOffset.UTC, "Etc/UTC", ...): omit it
    String zoneId = doc.getZoneId();
    if (!RateLimitRuleConverter.isUtc(zoneId)) {
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
