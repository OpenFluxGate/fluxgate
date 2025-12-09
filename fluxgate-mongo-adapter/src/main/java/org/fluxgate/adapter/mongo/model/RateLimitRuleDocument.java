package org.fluxgate.adapter.mongo.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;

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

  protected RateLimitRuleDocument() {}

  /**
   * Creates a new RateLimitRuleDocument without custom attributes.
   *
   * <p>This constructor is provided for backward compatibility and convenience when attributes are
   * not needed.
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
   * Creates a new RateLimitRuleDocument with custom attributes.
   *
   * <p>Example with attributes:
   *
   * <pre>{@code
   * new RateLimitRuleDocument(
   *     "rule-1", "Premium API", true, LimitScope.PER_IP, "ip",
   *     OnLimitExceedPolicy.REJECT_REQUEST, bands, "api-limits",
   *     Map.of("tier", "premium", "team", "billing"));
   * }</pre>
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
   * @return the attributes map (may be null or empty)
   */
  public Map<String, Object> getAttributes() {
    return attributes;
  }
}
