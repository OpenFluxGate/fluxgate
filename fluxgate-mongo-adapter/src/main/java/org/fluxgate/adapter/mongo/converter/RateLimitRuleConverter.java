package org.fluxgate.adapter.mongo.converter;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.fluxgate.adapter.mongo.model.RateLimitBandDocument;
import org.fluxgate.adapter.mongo.model.RateLimitRuleDocument;
import org.fluxgate.core.config.AccessControl;
import org.fluxgate.core.config.QuotaPeriod;
import org.fluxgate.core.config.RateLimitAlgorithm;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.config.RuleMatcher;
import org.fluxgate.core.match.CidrSet;

/** Converts between core domain objects and MongoDB documents. */
public final class RateLimitRuleConverter {

  private RateLimitRuleConverter() {}

  /**
   * Converts a MongoDB rule document to a core domain {@link RateLimitRule}.
   *
   * <p>Missing 0.4.0 fields default to the same values as the core builders so that a document
   * written by 0.3.x rounds-trips correctly.
   *
   * @param doc the document to convert (may be null → returns null)
   * @return the domain rule, or null if {@code doc} is null
   */
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
            .ruleSetId(doc.getRuleSetId())
            .priority(doc.getPriority());

    if (doc.getBands() != null) {
      for (RateLimitBandDocument bandDoc : doc.getBands()) {
        builder.addBand(toDomain(bandDoc));
      }
    }

    if (doc.getAttributes() != null && !doc.getAttributes().isEmpty()) {
      builder.attributes(doc.getAttributes());
    }

    // Build matcher from 0.4.0 fields (empty lists produce matchAll)
    RuleMatcher matcher = buildMatcher(doc);
    builder.matcher(matcher);

    return builder.build();
  }

  /**
   * Converts a core domain {@link RateLimitRule} to a MongoDB document.
   *
   * @param rule the rule to convert (may be null → returns null)
   * @return the document, or null if {@code rule} is null
   */
  public static RateLimitRuleDocument toDocument(RateLimitRule rule) {
    if (rule == null) {
      return null;
    }

    List<RateLimitBandDocument> bandDocs =
        rule.getBands().stream()
            .map(RateLimitRuleConverter::toDocument)
            .collect(Collectors.toList());

    String ruleSetId = rule.getRuleSetIdOrNull() != null ? rule.getRuleSetIdOrNull() : "default";

    RateLimitRuleDocument doc =
        new RateLimitRuleDocument(
            rule.getId(),
            rule.getName(),
            rule.isEnabled(),
            rule.getScope(),
            rule.getKeyStrategyId(),
            rule.getOnLimitExceedPolicy(),
            bandDocs,
            ruleSetId,
            rule.getAttributes());

    // Persist 0.4.0 matcher fields
    RuleMatcher matcher = rule.getMatcher();
    if (matcher != null) {
      doc.withMatcher(
          rule.getPriority(),
          matcher.getMethods().isEmpty() ? null : new ArrayList<>(matcher.getMethods()),
          matcher.getPathPatterns().isEmpty() ? null : new ArrayList<>(matcher.getPathPatterns()),
          matcher.getExcludePathPatterns().isEmpty()
              ? null
              : new ArrayList<>(matcher.getExcludePathPatterns()),
          matcher.getHeaderEquals().isEmpty() ? null : matcher.getHeaderEquals(),
          matcher.getHeaderPresent().isEmpty()
              ? null
              : new ArrayList<>(matcher.getHeaderPresent()));
    } else {
      doc.setPriority(rule.getPriority());
    }

    return doc;
  }

  /**
   * Converts a band document to a core {@link RateLimitBand}.
   *
   * <p>Missing 0.4.0 fields ({@code algorithm}, {@code zoneId}, {@code slidingWindowBuckets})
   * default via {@link RateLimitBandDocument}'s own getters.
   *
   * @param doc the band document to convert
   * @return the domain band
   */
  public static RateLimitBand toDomain(RateLimitBandDocument doc) {
    RateLimitAlgorithm algorithm = parseAlgorithm(doc.getAlgorithm());
    QuotaPeriod quotaPeriod = parseQuotaPeriod(doc.getQuotaPeriod());
    ZoneId zoneId = parseZoneId(doc.getZoneId());

    try {
      RateLimitBand.Builder builder =
          RateLimitBand.builder(Duration.ofSeconds(doc.getWindowSeconds()), doc.getCapacity())
              .label(doc.getLabel())
              .algorithm(algorithm)
              .zoneId(zoneId)
              .slidingWindowBuckets(doc.getSlidingWindowBuckets());

      if (quotaPeriod != null) {
        builder.quotaPeriod(quotaPeriod);
      }

      return builder.build();
    } catch (InvalidRuleDocumentException e) {
      throw e;
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new InvalidRuleDocumentException("Invalid band: " + e.getMessage(), e);
    }
  }

  /**
   * Converts a core {@link RateLimitBand} to a band document.
   *
   * @param band the band to convert
   * @return the band document
   */
  public static RateLimitBandDocument toDocument(RateLimitBand band) {
    return new RateLimitBandDocument(
        band.getWindow().toSeconds(),
        band.getCapacity(),
        band.getLabel() != null ? band.getLabel() : "default",
        band.getAlgorithm().name(),
        band.getQuotaPeriod() != null ? band.getQuotaPeriod().name() : null,
        isUtc(band.getZoneId().getId()) ? "UTC" : band.getZoneId().getId(),
        band.getSlidingWindowBuckets());
  }

  /**
   * Builds an {@link AccessControl} from the access-control lists embedded in a rule document.
   *
   * <p>Returns {@link AccessControl#EMPTY} when all four lists are absent or empty (the 0.3.x
   * case).
   *
   * @param doc the rule document
   * @return the access control (never null)
   */
  public static AccessControl toAccessControl(RateLimitRuleDocument doc) {
    return toAccessControl(
        doc.getAllowedIps(), doc.getDeniedIps(), doc.getAllowedKeys(), doc.getDeniedKeys());
  }

  /**
   * Builds an {@link AccessControl} from raw access-control lists.
   *
   * @param allowedIpsList allowed IP CIDRs (never null, may be empty)
   * @param deniedIpsList denied IP CIDRs (never null, may be empty)
   * @param allowedKeySet allowed resolved key values (never null, may be empty)
   * @param deniedKeySet denied resolved key values (never null, may be empty)
   * @return the access control, {@link AccessControl#EMPTY} when every list is empty
   * @throws IllegalArgumentException when a CIDR cannot be parsed
   * @since 0.4.0
   */
  public static AccessControl toAccessControl(
      List<String> allowedIpsList,
      List<String> deniedIpsList,
      Set<String> allowedKeySet,
      Set<String> deniedKeySet) {
    boolean hasData =
        !allowedIpsList.isEmpty()
            || !deniedIpsList.isEmpty()
            || !allowedKeySet.isEmpty()
            || !deniedKeySet.isEmpty();

    if (!hasData) {
      return AccessControl.EMPTY;
    }

    AccessControl.Builder acBuilder = AccessControl.builder();

    if (!allowedIpsList.isEmpty()) {
      acBuilder.allowedIps(CidrSet.of(allowedIpsList));
    }
    if (!deniedIpsList.isEmpty()) {
      acBuilder.deniedIps(CidrSet.of(deniedIpsList));
    }
    if (!allowedKeySet.isEmpty()) {
      acBuilder.allowedKeys(allowedKeySet);
    }
    if (!deniedKeySet.isEmpty()) {
      acBuilder.deniedKeys(deniedKeySet);
    }

    return acBuilder.build();
  }

  /**
   * Applies the {@link AccessControl} from a core {@link
   * org.fluxgate.core.ratelimiter.RateLimitRuleSet} onto a rule document.
   *
   * <p>Call this before persisting rule documents so access control survives a round-trip.
   *
   * @param doc the document to annotate
   * @param accessControl the access control to embed (may be null or empty — no-op)
   */
  public static void applyAccessControl(RateLimitRuleDocument doc, AccessControl accessControl) {
    // CidrSet does not expose its underlying entries, so it is impossible to recover the original
    // CIDR strings from an AccessControl object.  Callers must use applyAccessControlStrings()
    // and pass the raw string lists that were used to build the AccessControl.
    throw new UnsupportedOperationException(
        "applyAccessControl(RateLimitRuleDocument, AccessControl) cannot convert a CidrSet back"
            + " to strings; use applyAccessControlStrings(...) instead.");
  }

  /**
   * Applies raw access-control string lists onto a rule document.
   *
   * @param doc the document to annotate
   * @param allowedIps allowed IP CIDRs (may be null)
   * @param deniedIps denied IP CIDRs (may be null)
   * @param allowedKeys allowed resolved key values (may be null)
   * @param deniedKeys denied resolved key values (may be null)
   */
  public static void applyAccessControlStrings(
      RateLimitRuleDocument doc,
      List<String> allowedIps,
      List<String> deniedIps,
      Set<String> allowedKeys,
      Set<String> deniedKeys) {
    doc.withAccessControl(allowedIps, deniedIps, allowedKeys, deniedKeys);
  }

  // ===== private helpers =====

  private static RuleMatcher buildMatcher(RateLimitRuleDocument doc) {
    List<String> methods = doc.getMethods();
    List<String> pathPatterns = doc.getPathPatterns();
    List<String> excludePathPatterns = doc.getExcludePathPatterns();
    Map<String, String> headerEquals = doc.getHeaderEquals();
    List<String> headerPresent = doc.getHeaderPresent();

    boolean hasAny =
        !methods.isEmpty()
            || !pathPatterns.isEmpty()
            || !excludePathPatterns.isEmpty()
            || !headerEquals.isEmpty()
            || !headerPresent.isEmpty();

    if (!hasAny) {
      return RuleMatcher.matchAll();
    }

    RuleMatcher.Builder builder = RuleMatcher.builder();

    if (!methods.isEmpty()) {
      Set<String> methodSet = new HashSet<>(methods);
      builder.methods(methodSet);
    }
    for (String p : pathPatterns) {
      builder.addPathPattern(p);
    }
    for (String p : excludePathPatterns) {
      builder.addExcludePathPattern(p);
    }
    for (Map.Entry<String, String> e : headerEquals.entrySet()) {
      builder.headerEquals(e.getKey(), e.getValue());
    }
    for (String h : headerPresent) {
      builder.headerPresent(h);
    }

    return builder.build();
  }

  /**
   * Parses the algorithm. Absent means {@code TOKEN_BUCKET} (the 0.3.x behaviour); an unknown name
   * is rejected, because silently enforcing a different algorithm changes what the rule limits.
   */
  private static RateLimitAlgorithm parseAlgorithm(String name) {
    if (name == null || name.isEmpty()) {
      return RateLimitAlgorithm.TOKEN_BUCKET;
    }
    try {
      return RateLimitAlgorithm.valueOf(name);
    } catch (IllegalArgumentException e) {
      throw new InvalidRuleDocumentException("Unknown rate limit algorithm '" + name + "'", e);
    }
  }

  /** Parses the quota period. Absent means none; an unknown name is rejected. */
  private static QuotaPeriod parseQuotaPeriod(String name) {
    if (name == null || name.isEmpty()) {
      return null;
    }
    try {
      return QuotaPeriod.valueOf(name);
    } catch (IllegalArgumentException e) {
      throw new InvalidRuleDocumentException("Unknown quota period '" + name + "'", e);
    }
  }

  /**
   * Whether the zone id names UTC under any spelling ({@code "UTC"}, {@code "Z"}, {@code
   * "Etc/UTC"}, {@code "+00:00"}, ...). The core default is {@code ZoneOffset.UTC}, whose id is
   * {@code "Z"}, so a literal comparison with {@code "UTC"} would miss it.
   */
  static boolean isUtc(String id) {
    if (id == null || id.isEmpty()) {
      return true;
    }
    try {
      return ZoneId.of(id).normalized().equals(java.time.ZoneOffset.UTC);
    } catch (java.time.DateTimeException e) {
      return false;
    }
  }

  /**
   * Parses the zone id. Absent or any UTC spelling means {@code ZoneOffset.UTC} (the 0.3.x
   * behaviour and the core default); an invalid id is rejected instead of silently aligning
   * calendar quotas to UTC.
   */
  private static ZoneId parseZoneId(String id) {
    if (id == null || id.isEmpty()) {
      return java.time.ZoneOffset.UTC;
    }
    try {
      ZoneId zone = ZoneId.of(id);
      // Any UTC spelling reads back as the core default, so a written-then-read band stays equal
      return zone.normalized().equals(java.time.ZoneOffset.UTC) ? java.time.ZoneOffset.UTC : zone;
    } catch (java.time.DateTimeException e) {
      throw new InvalidRuleDocumentException("Invalid zone id '" + id + "'", e);
    }
  }
}
