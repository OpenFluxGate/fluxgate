package org.fluxgate.adapter.mongo.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;

/**
 * MongoDB document representation of a single rate limit rule.
 *
 * <p>Fields added in 0.4.0 ({@code priority}, {@code methods}, {@code pathPatterns}, {@code
 * excludePathPatterns}, {@code headerEquals}, {@code headerPresent}, and the access-control lists
 * {@code allowedIps}, {@code deniedIps}, {@code allowedKeys}, {@code deniedKeys}) are optional for
 * backward compatibility: documents written by 0.3.x that lack them default to the same values as
 * the core builders, so an old document rounds-trips to the same domain object it would have
 * produced before.
 *
 * <p><b>Access control placement:</b> the rule-set-level {@link
 * org.fluxgate.core.config.AccessControl} is stored redundantly on every rule document that belongs
 * to the rule set. All rules in a rule set must carry the same access-control lists; the repository
 * uses the lists from the first document it finds. This avoids a separate {@code
 * rate_limit_rule_set_meta} collection while keeping the access control durable in MongoDB.
 */
public class RateLimitRuleDocument {

  private String id;
  private String name;
  private boolean enabled;
  private LimitScope scope;
  private String keyStrategyId;
  private OnLimitExceedPolicy onLimitExceedPolicy;
  private List<RateLimitBandDocument> bands;
  private String ruleSetId;

  /**
   * Custom attributes for user-defined metadata.
   *
   * <p>This field is stored as a nested document in MongoDB, allowing queries like:
   *
   * <pre>{@code
   * db.rate_limit_rules.find({"attributes.tier": "premium"})
   * }</pre>
   *
   * <p>Note: This map may be null or empty if no attributes are set.
   */
  private Map<String, Object> attributes;

  // ===== 0.4.0 fields =====

  /**
   * Priority of this rule within the rule set. Higher values win.
   *
   * <p>Absent in 0.3.x documents — defaults to {@code 0}.
   *
   * @since 0.4.0
   */
  private int priority;

  /**
   * Matcher: HTTP methods (upper-case). Empty means "any method".
   *
   * @since 0.4.0
   */
  private List<String> methods;

  /**
   * Matcher: Ant-style path include patterns. Empty means "any path".
   *
   * @since 0.4.0
   */
  private List<String> pathPatterns;

  /**
   * Matcher: Ant-style path exclude patterns. Empty means "no exclusions".
   *
   * @since 0.4.0
   */
  private List<String> excludePathPatterns;

  /**
   * Matcher: header name (lower-cased) → expected exact value.
   *
   * @since 0.4.0
   */
  private Map<String, String> headerEquals;

  /**
   * Matcher: header names (lower-cased) that must be present.
   *
   * @since 0.4.0
   */
  private List<String> headerPresent;

  /**
   * Rule-set-level access control: allowed IP CIDRs.
   *
   * <p>Stored redundantly on each rule document for the rule set. The repository uses the value
   * from the first rule document for the rule set.
   *
   * @since 0.4.0
   */
  private List<String> allowedIps;

  /**
   * Rule-set-level access control: denied IP CIDRs.
   *
   * @since 0.4.0
   */
  private List<String> deniedIps;

  /**
   * Rule-set-level access control: allowed resolved key values (e.g. {@code "user:alice"}).
   *
   * @since 0.4.0
   */
  private Set<String> allowedKeys;

  /**
   * Rule-set-level access control: denied resolved key values.
   *
   * @since 0.4.0
   */
  private Set<String> deniedKeys;

  /** No-arg constructor for MongoDB driver deserialization. */
  protected RateLimitRuleDocument() {}

  /**
   * Creates a new rule document without custom attributes (backward-compatible constructor).
   *
   * @param id rule id (must not be null)
   * @param name display name (must not be null)
   * @param enabled whether the rule is active
   * @param scope limit scope (must not be null)
   * @param keyStrategyId key strategy identifier (must not be null)
   * @param onLimitExceedPolicy policy on limit exceeded (must not be null)
   * @param bands list of bands (must not be null)
   * @param ruleSetId rule set id (must not be null)
   */
  public RateLimitRuleDocument(
      String id,
      String name,
      boolean enabled,
      LimitScope scope,
      String keyStrategyId,
      OnLimitExceedPolicy onLimitExceedPolicy,
      List<RateLimitBandDocument> bands,
      String ruleSetId) {
    this(id, name, enabled, scope, keyStrategyId, onLimitExceedPolicy, bands, ruleSetId, null);
  }

  /**
   * Creates a new rule document with custom attributes.
   *
   * @param id rule id (must not be null)
   * @param name display name (must not be null)
   * @param enabled whether the rule is active
   * @param scope limit scope (must not be null)
   * @param keyStrategyId key strategy identifier (must not be null)
   * @param onLimitExceedPolicy policy on limit exceeded (must not be null)
   * @param bands list of bands (must not be null)
   * @param ruleSetId rule set id (must not be null)
   * @param attributes optional user-defined metadata (may be null)
   */
  public RateLimitRuleDocument(
      String id,
      String name,
      boolean enabled,
      LimitScope scope,
      String keyStrategyId,
      OnLimitExceedPolicy onLimitExceedPolicy,
      List<RateLimitBandDocument> bands,
      String ruleSetId,
      Map<String, Object> attributes) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.name = Objects.requireNonNull(name, "name must not be null");
    this.enabled = enabled;
    this.scope = Objects.requireNonNull(scope, "scope must not be null");
    this.keyStrategyId = Objects.requireNonNull(keyStrategyId, "keyStrategyId must not be null");
    this.onLimitExceedPolicy =
        Objects.requireNonNull(onLimitExceedPolicy, "onLimitExceedPolicy must not be null");
    this.bands = List.copyOf(Objects.requireNonNull(bands, "bands must not be null"));
    this.ruleSetId = Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    this.attributes = attributes != null ? attributes : Collections.emptyMap();
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public LimitScope getScope() {
    return scope;
  }

  public String getKeyStrategyId() {
    return keyStrategyId;
  }

  public OnLimitExceedPolicy getOnLimitExceedPolicy() {
    return onLimitExceedPolicy;
  }

  public List<RateLimitBandDocument> getBands() {
    return bands;
  }

  public String getRuleSetId() {
    return ruleSetId;
  }

  /**
   * Returns custom attributes associated with this rule.
   *
   * @return the attributes map (never null)
   */
  public Map<String, Object> getAttributes() {
    return attributes != null ? attributes : Collections.emptyMap();
  }

  // ===== 0.4.0 accessors =====

  /**
   * Returns the rule priority. Higher values win; default is {@code 0}.
   *
   * @return the priority
   * @since 0.4.0
   */
  public int getPriority() {
    return priority;
  }

  public void setPriority(int priority) {
    this.priority = priority;
  }

  /**
   * Returns the matcher HTTP methods (upper-case). Empty means "any method".
   *
   * @return the methods list (never null)
   * @since 0.4.0
   */
  public List<String> getMethods() {
    return methods != null ? methods : Collections.emptyList();
  }

  public void setMethods(List<String> methods) {
    this.methods = methods;
  }

  /**
   * Returns the matcher Ant-style path include patterns. Empty means "any path".
   *
   * @return the path patterns list (never null)
   * @since 0.4.0
   */
  public List<String> getPathPatterns() {
    return pathPatterns != null ? pathPatterns : Collections.emptyList();
  }

  public void setPathPatterns(List<String> pathPatterns) {
    this.pathPatterns = pathPatterns;
  }

  /**
   * Returns the matcher Ant-style path exclude patterns. Empty means "no exclusions".
   *
   * @return the exclude path patterns list (never null)
   * @since 0.4.0
   */
  public List<String> getExcludePathPatterns() {
    return excludePathPatterns != null ? excludePathPatterns : Collections.emptyList();
  }

  public void setExcludePathPatterns(List<String> excludePathPatterns) {
    this.excludePathPatterns = excludePathPatterns;
  }

  /**
   * Returns the header-equals matcher map (header name lower-cased → expected value).
   *
   * @return the map (never null)
   * @since 0.4.0
   */
  public Map<String, String> getHeaderEquals() {
    return headerEquals != null ? headerEquals : Collections.emptyMap();
  }

  public void setHeaderEquals(Map<String, String> headerEquals) {
    this.headerEquals = headerEquals;
  }

  /**
   * Returns the header-present matcher list (lower-cased header names).
   *
   * @return the list (never null)
   * @since 0.4.0
   */
  public List<String> getHeaderPresent() {
    return headerPresent != null ? headerPresent : Collections.emptyList();
  }

  public void setHeaderPresent(List<String> headerPresent) {
    this.headerPresent = headerPresent;
  }

  /**
   * Returns the rule-set-level allowed IP CIDRs.
   *
   * @return the list (never null)
   * @since 0.4.0
   */
  public List<String> getAllowedIps() {
    return allowedIps != null ? allowedIps : Collections.emptyList();
  }

  public void setAllowedIps(List<String> allowedIps) {
    this.allowedIps = allowedIps;
  }

  /**
   * Returns the rule-set-level denied IP CIDRs.
   *
   * @return the list (never null)
   * @since 0.4.0
   */
  public List<String> getDeniedIps() {
    return deniedIps != null ? deniedIps : Collections.emptyList();
  }

  public void setDeniedIps(List<String> deniedIps) {
    this.deniedIps = deniedIps;
  }

  /**
   * Returns the rule-set-level allowed resolved key values.
   *
   * @return the set (never null)
   * @since 0.4.0
   */
  public Set<String> getAllowedKeys() {
    return allowedKeys != null ? allowedKeys : Collections.emptySet();
  }

  public void setAllowedKeys(Set<String> allowedKeys) {
    this.allowedKeys = allowedKeys;
  }

  /**
   * Returns the rule-set-level denied resolved key values.
   *
   * @return the set (never null)
   * @since 0.4.0
   */
  public Set<String> getDeniedKeys() {
    return deniedKeys != null ? deniedKeys : Collections.emptySet();
  }

  public void setDeniedKeys(Set<String> deniedKeys) {
    this.deniedKeys = deniedKeys;
  }

  // ===== builder-style setters for fluent construction =====

  /**
   * Sets all 0.4.0 matcher fields at once and returns this document for fluent construction.
   *
   * @param priority rule priority
   * @param methods HTTP methods (may be null)
   * @param pathPatterns path include patterns (may be null)
   * @param excludePathPatterns path exclude patterns (may be null)
   * @param headerEquals header equality constraints (may be null)
   * @param headerPresent header presence constraints (may be null)
   * @return this document
   */
  public RateLimitRuleDocument withMatcher(
      int priority,
      List<String> methods,
      List<String> pathPatterns,
      List<String> excludePathPatterns,
      Map<String, String> headerEquals,
      List<String> headerPresent) {
    this.priority = priority;
    this.methods = methods != null ? new ArrayList<>(methods) : null;
    this.pathPatterns = pathPatterns != null ? new ArrayList<>(pathPatterns) : null;
    this.excludePathPatterns =
        excludePathPatterns != null ? new ArrayList<>(excludePathPatterns) : null;
    this.headerEquals = headerEquals != null ? new HashMap<>(headerEquals) : null;
    this.headerPresent = headerPresent != null ? new ArrayList<>(headerPresent) : null;
    return this;
  }

  /**
   * Sets all 0.4.0 access-control fields and returns this document for fluent construction.
   *
   * @param allowedIps allowed IP CIDRs (may be null)
   * @param deniedIps denied IP CIDRs (may be null)
   * @param allowedKeys allowed resolved key values (may be null)
   * @param deniedKeys denied resolved key values (may be null)
   * @return this document
   */
  public RateLimitRuleDocument withAccessControl(
      List<String> allowedIps,
      List<String> deniedIps,
      Set<String> allowedKeys,
      Set<String> deniedKeys) {
    this.allowedIps = allowedIps != null ? new ArrayList<>(allowedIps) : null;
    this.deniedIps = deniedIps != null ? new ArrayList<>(deniedIps) : null;
    this.allowedKeys = allowedKeys != null ? new HashSet<>(allowedKeys) : null;
    this.deniedKeys = deniedKeys != null ? new HashSet<>(deniedKeys) : null;
    return this;
  }
}
