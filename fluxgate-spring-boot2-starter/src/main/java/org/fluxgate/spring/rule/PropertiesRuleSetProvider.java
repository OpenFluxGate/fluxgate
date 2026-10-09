package org.fluxgate.spring.rule;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fluxgate.core.config.AccessControl;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.config.RuleMatcher;
import org.fluxgate.core.match.CidrSet;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.properties.FluxgateProperties.AccessControlProperties;
import org.fluxgate.spring.properties.FluxgateProperties.BandProperties;
import org.fluxgate.spring.properties.FluxgateProperties.MatcherProperties;
import org.fluxgate.spring.properties.FluxgateProperties.RuleProperties;
import org.fluxgate.spring.properties.FluxgateProperties.RuleSetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RateLimitRuleSetProvider} that serves rule sets from YAML / properties files bound via
 * {@code fluxgate.ratelimit.rule-sets[n]}.
 *
 * <p>All rule sets are built eagerly at construction time so configuration errors surface during
 * application startup rather than at the first request. The built rule sets are looked up in O(1)
 * by id from an immutable map.
 *
 * <p>The {@link org.fluxgate.core.key.KeyResolver} must be supplied by the caller; a default
 * resolver is provided by {@link
 * org.fluxgate.spring.autoconfigure.FluxgateRateLimiterAutoConfiguration}.
 *
 * @since 0.4.0
 */
public final class PropertiesRuleSetProvider implements RateLimitRuleSetProvider {

  private static final Logger log = LoggerFactory.getLogger(PropertiesRuleSetProvider.class);

  private final Map<String, RateLimitRuleSet> ruleSets;

  /**
   * Builds the provider from a list of rule set properties.
   *
   * @param ruleSetPropertiesList the YAML-bound rule set list (may be empty, must not be null)
   * @param keyResolver the key resolver to attach to every rule set
   * @throws IllegalArgumentException when a rule set or rule is missing a required field, or when a
   *     band configuration is invalid
   */
  public PropertiesRuleSetProvider(
      List<RuleSetProperties> ruleSetPropertiesList,
      org.fluxgate.core.key.KeyResolver keyResolver) {
    Map<String, RateLimitRuleSet> map = new LinkedHashMap<>();
    for (RuleSetProperties rsp : ruleSetPropertiesList) {
      RateLimitRuleSet ruleSet = buildRuleSet(rsp, keyResolver);
      if (map.putIfAbsent(ruleSet.getId(), ruleSet) != null) {
        throw new IllegalArgumentException(
            "Duplicate rule set id in fluxgate.ratelimit.rule-sets: '" + ruleSet.getId() + "'");
      }
      log.info(
          "Loaded YAML rule set '{}' ({} rule(s))", ruleSet.getId(), ruleSet.getRules().size());
    }
    this.ruleSets = Collections.unmodifiableMap(map);
  }

  @Override
  public Optional<RateLimitRuleSet> findById(String ruleSetId) {
    return Optional.ofNullable(ruleSets.get(ruleSetId));
  }

  /** Returns the number of rule sets loaded from properties. */
  public int size() {
    return ruleSets.size();
  }

  /** Returns all loaded rule set ids in insertion order. */
  public Set<String> ruleSetIds() {
    return ruleSets.keySet();
  }

  // ===== private builder helpers =====

  private static RateLimitRuleSet buildRuleSet(
      RuleSetProperties rsp, org.fluxgate.core.key.KeyResolver keyResolver) {
    String id = rsp.getId();
    if (id == null || id.trim().isEmpty()) {
      throw new IllegalArgumentException(
          "A fluxgate.ratelimit.rule-sets entry is missing its required 'id' field");
    }

    List<RateLimitRule> rules = new ArrayList<>();
    for (RuleProperties rp : rsp.getRules()) {
      rules.add(buildRule(rp, id));
    }

    AccessControl ac = buildAccessControl(rsp.getAccessControl());

    RateLimitRuleSet.Builder builder =
        RateLimitRuleSet.builder(id).keyResolver(keyResolver).rules(rules).accessControl(ac);

    if (rsp.getDescription() != null) {
      builder.description(rsp.getDescription());
    }

    return builder.build();
  }

  private static RateLimitRule buildRule(RuleProperties rp, String ruleSetId) {
    String id = rp.getId();
    if (id == null || id.trim().isEmpty()) {
      throw new IllegalArgumentException(
          "A rule in rule set '" + ruleSetId + "' is missing its required 'id' field");
    }

    RateLimitRule.Builder builder =
        RateLimitRule.builder(id)
            .name(rp.getName() != null ? rp.getName() : id)
            .enabled(rp.isEnabled())
            .priority(rp.getPriority())
            .scope(rp.getScope())
            .keyStrategyId(rp.getKeyStrategyId())
            .onLimitExceedPolicy(rp.getOnLimitExceedPolicy())
            .ruleSetId(ruleSetId);

    if (rp.getAttributes() != null && !rp.getAttributes().isEmpty()) {
      builder.attributes(rp.getAttributes());
    }

    builder.matcher(buildMatcher(rp.getMatcher()));

    for (BandProperties bp : rp.getBands()) {
      builder.addBand(buildBand(bp, id));
    }

    return builder.build();
  }

  private static RuleMatcher buildMatcher(MatcherProperties mp) {
    if (mp == null) {
      return RuleMatcher.matchAll();
    }
    List<String> methods = mp.getMethods();
    List<String> pathPatterns = mp.getPathPatterns();
    List<String> excludePathPatterns = mp.getExcludePathPatterns();
    Map<String, String> headerEquals = mp.getHeaderEquals();
    List<String> headerPresent = mp.getHeaderPresent();

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
      Set<String> methodSet = new HashSet<>();
      for (String m : methods) {
        methodSet.add(m.toUpperCase());
      }
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

  private static RateLimitBand buildBand(BandProperties bp, String ruleId) {
    if (bp.getWindow() == null) {
      throw new IllegalArgumentException(
          "Rule '" + ruleId + "': a band is missing its required 'window' field");
    }
    if (bp.getCapacity() <= 0) {
      throw new IllegalArgumentException(
          "Rule '" + ruleId + "': a band has non-positive capacity " + bp.getCapacity());
    }

    ZoneId zoneId;
    if (bp.getZoneId() != null && !bp.getZoneId().isEmpty()) {
      try {
        zoneId = ZoneId.of(bp.getZoneId());
      } catch (Exception e) {
        log.warn("Rule '{}': unknown zoneId '{}', falling back to UTC", ruleId, bp.getZoneId());
        zoneId = ZoneOffset.UTC;
      }
    } else {
      zoneId = ZoneOffset.UTC;
    }

    RateLimitBand.Builder builder =
        RateLimitBand.builder(bp.getWindow(), bp.getCapacity())
            .algorithm(
                bp.getAlgorithm() != null
                    ? bp.getAlgorithm()
                    : org.fluxgate.core.config.RateLimitAlgorithm.TOKEN_BUCKET)
            .zoneId(zoneId)
            .slidingWindowBuckets(bp.getSlidingWindowBuckets());

    if (bp.getLabel() != null) {
      builder.label(bp.getLabel());
    }
    if (bp.getQuotaPeriod() != null) {
      builder.quotaPeriod(bp.getQuotaPeriod());
    }

    return builder.build();
  }

  private static AccessControl buildAccessControl(AccessControlProperties acp) {
    if (acp == null) {
      return AccessControl.EMPTY;
    }
    List<String> allowedIps = acp.getAllowedIps();
    List<String> deniedIps = acp.getDeniedIps();
    List<String> allowedKeysList = acp.getAllowedKeys();
    List<String> deniedKeysList = acp.getDeniedKeys();

    boolean hasData =
        !allowedIps.isEmpty()
            || !deniedIps.isEmpty()
            || !allowedKeysList.isEmpty()
            || !deniedKeysList.isEmpty();

    if (!hasData) {
      return AccessControl.EMPTY;
    }

    AccessControl.Builder builder = AccessControl.builder();
    if (!allowedIps.isEmpty()) {
      builder.allowedIps(CidrSet.of(allowedIps));
    }
    if (!deniedIps.isEmpty()) {
      builder.deniedIps(CidrSet.of(deniedIps));
    }
    if (!allowedKeysList.isEmpty()) {
      builder.allowedKeys(new HashSet<>(allowedKeysList));
    }
    if (!deniedKeysList.isEmpty()) {
      builder.deniedKeys(new HashSet<>(deniedKeysList));
    }
    return builder.build();
  }
}
