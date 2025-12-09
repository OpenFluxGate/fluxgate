package org.fluxgate.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Core configuration object describing a rate limit rule. This rule is intentionally
 * storage-agnostic and engine-agnostic. It does not depend on Redis, MongoDB, HTTP frameworks, etc.
 * Adapters will translate this rule into engine-specific configuration (e.g. Bucket4j).
 */
public final class RateLimitRule {

  private final String id;
  private final String name;
  private final boolean enabled;
  private final LimitScope scope;

  /**
   * Identifier of the key strategy (for example "ip", "userId", "apiKey"). The actual
   * implementation will be resolved via SPI.
   */
  private final String keyStrategyId;

  /** How to behave when the limit is exceeded. */
  private final OnLimitExceedPolicy onLimitExceedPolicy;

  private final List<RateLimitBand> bands;

  /**
   * Optional: logical rule set identifier this rule belongs to. Used mainly for logging / metrics
   * in adapters.
   */
  private final String ruleSetId;

  /**
   * Custom attributes for user-defined metadata.
   *
   * <p>This field allows users to store arbitrary key-value pairs for their own purposes. FluxGate
   * does not interpret these attributes; they are passed through to storage and can be used for:
   *
   * <ul>
   *   <li>Tagging rules by team, environment, or tier (e.g., "team": "billing", "tier": "premium")
   *   <li>Storing external references (e.g., "jiraTicket": "RATE-123")
   *   <li>Custom business logic in your application
   * </ul>
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * RateLimitRule.builder("premium-api")
   *     .attribute("tier", "premium")
   *     .attribute("team", "billing")
   *     .attribute("maxBurstOverride", 1000)
   *     .addBand(...)
   *     .build();
   * }</pre>
   *
   * <p>Note: This map is always non-null (empty map if no attributes are set).
   */
  private final Map<String, Object> attributes;

  private RateLimitRule(Builder builder) {
    this.id = Objects.requireNonNull(builder.id, "id must not be null");
    this.name = builder.name != null ? builder.name : builder.id;
    this.enabled = builder.enabled;
    this.scope = Objects.requireNonNull(builder.scope, "scope must not be null");
    this.keyStrategyId =
        Objects.requireNonNull(builder.keyStrategyId, "keyStrategyId must not be null");
    this.onLimitExceedPolicy =
        Objects.requireNonNull(builder.onLimitExceedPolicy, "onLimitExceedPolicy must not be null");
    this.ruleSetId = builder.ruleSetId; // nullable

    if (builder.bands.isEmpty()) {
      throw new IllegalArgumentException("At least one RateLimitBand must be configured");
    }
    this.bands = List.copyOf(builder.bands);
    this.attributes =
        builder.attributes.isEmpty()
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(builder.attributes));
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

  public List<RateLimitBand> getBands() {
    return Collections.unmodifiableList(bands);
  }

  /** May be null if this rule is not associated with a specific rule set. */
  public String getRuleSetIdOrNull() {
    return ruleSetId;
  }

  /**
   * Returns custom attributes associated with this rule.
   *
   * @return an unmodifiable map of attributes (never null, may be empty)
   */
  public Map<String, Object> getAttributes() {
    return attributes;
  }

  /**
   * Returns a specific attribute value, or null if not present.
   *
   * @param key the attribute key
   * @return the attribute value, or null if not found
   */
  public Object getAttribute(String key) {
    return attributes.get(key);
  }

  /**
   * Returns a specific attribute value cast to the expected type, or null if not present.
   *
   * <p>Example: {@code String tier = rule.getAttribute("tier", String.class);}
   *
   * @param key the attribute key
   * @param type the expected type
   * @param <T> the type parameter
   * @return the attribute value cast to the expected type, or null if not found
   * @throws ClassCastException if the value cannot be cast to the expected type
   */
  @SuppressWarnings("unchecked")
  public <T> T getAttribute(String key, Class<T> type) {
    Object value = attributes.get(key);
    return value != null ? (T) value : null;
  }

  public static Builder builder(String id) {
    return new Builder(id);
  }

  public static final class Builder {
    private final String id;
    private String name;
    private boolean enabled = true;
    private LimitScope scope = LimitScope.PER_API_KEY;
    private String keyStrategyId = "apiKey"; // sensible default
    private OnLimitExceedPolicy onLimitExceedPolicy = OnLimitExceedPolicy.REJECT_REQUEST;
    private final List<RateLimitBand> bands = new ArrayList<>();
    private String ruleSetId; // optional
    private Map<String, Object> attributes = Collections.emptyMap();

    private Builder(String id) {
      this.id = Objects.requireNonNull(id, "id must not be null");
    }

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder enabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    public Builder scope(LimitScope scope) {
      this.scope = scope;
      return this;
    }

    /** References a key strategy defined in SPI, e.g. "ip", "userId", "apiKey". */
    public Builder keyStrategyId(String keyStrategyId) {
      this.keyStrategyId = keyStrategyId;
      return this;
    }

    public Builder onLimitExceedPolicy(OnLimitExceedPolicy policy) {
      this.onLimitExceedPolicy = policy;
      return this;
    }

    public Builder addBand(RateLimitBand band) {
      this.bands.add(Objects.requireNonNull(band, "band must not be null"));
      return this;
    }

    /**
     * Optional: set the logical rule set id this rule belongs to. Only used for observability
     * (logging/metrics).
     */
    public Builder ruleSetId(String ruleSetId) {
      this.ruleSetId = ruleSetId;
      return this;
    }

    /**
     * Sets all custom attributes at once, replacing any existing attributes.
     *
     * <p>Use this when you have a pre-built map of attributes. For adding individual attributes,
     * use {@link #attribute(String, Object)} instead.
     *
     * @param attributes the attributes map (null is treated as empty)
     * @return this builder
     */
    public Builder attributes(Map<String, Object> attributes) {
      this.attributes = attributes != null ? new HashMap<>(attributes) : Collections.emptyMap();
      return this;
    }

    /**
     * Adds a single custom attribute.
     *
     * <p>Example:
     *
     * <pre>{@code
     * RateLimitRule.builder("premium-api")
     *     .attribute("tier", "premium")
     *     .attribute("team", "billing")
     *     .build();
     * }</pre>
     *
     * @param key the attribute key (must not be null)
     * @param value the attribute value
     * @return this builder
     */
    public Builder attribute(String key, Object value) {
      Objects.requireNonNull(key, "attribute key must not be null");
      if (this.attributes.isEmpty()) {
        this.attributes = new HashMap<>();
      }
      this.attributes.put(key, value);
      return this;
    }

    public RateLimitRule build() {
      return new RateLimitRule(this);
    }
  }

  @Override
  public String toString() {
    return "RateLimitRule{"
        + "id='"
        + id
        + '\''
        + ", name='"
        + name
        + '\''
        + ", enabled="
        + enabled
        + ", scope="
        + scope
        + ", keyStrategyId='"
        + keyStrategyId
        + '\''
        + ", onLimitExceedPolicy="
        + onLimitExceedPolicy
        + ", bands="
        + bands
        + ", ruleSetId='"
        + ruleSetId
        + '\''
        + ", attributes="
        + attributes
        + '}';
  }
}
