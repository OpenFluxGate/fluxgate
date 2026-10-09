package org.fluxgate.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fluxgate.core.exception.InvalidRuleConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core configuration object describing a rate limit rule. This rule is intentionally
 * storage-agnostic and engine-agnostic. It does not depend on Redis, MongoDB, HTTP frameworks, etc.
 * Adapters will translate this rule into engine-specific configuration (e.g. Bucket4j).
 *
 * <p>Equality is value-based over every field, so a rule loaded twice from the same storage
 * compares equal. Reload strategies depend on this to tell a real rule change from a fresh
 * deserialisation.
 */
public final class RateLimitRule {

  private static final Logger log = LoggerFactory.getLogger(RateLimitRule.class);

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

  /**
   * Priority of this rule within the rule set. Higher values win; ties are broken by rule id
   * ascending for determinism.
   *
   * @since 0.4.0
   */
  private final int priority;

  /**
   * Predicate that decides whether this rule applies to a given request.
   *
   * @since 0.4.0
   */
  private final RuleMatcher matcher;

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
    verifyDistinctBandKeyLabels(this.id, this.bands);
    this.attributes =
        builder.attributes.isEmpty()
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(builder.attributes));
    this.priority = builder.priority;
    this.matcher = builder.matcher != null ? builder.matcher : RuleMatcher.matchAll();
  }

  /**
   * Rejects bands that share a {@link RateLimitBand#getKeyLabel() key label}.
   *
   * <p>Two bands with the same key label would share a storage bucket and silently destroy each
   * other's limits, so this is a configuration error rather than a warning.
   */
  private static void verifyDistinctBandKeyLabels(String ruleId, List<RateLimitBand> bands) {
    Set<String> seen = new HashSet<>();
    for (RateLimitBand band : bands) {
      String keyLabel = band.getKeyLabel();
      if (!seen.add(keyLabel)) {
        throw new InvalidRuleConfigException("Duplicate band key label '" + keyLabel + "'", ruleId);
      }
    }
  }

  /**
   * Returns the rule identifier.
   *
   * @return the rule id (never null)
   */
  public String getId() {
    return id;
  }

  /**
   * Returns the display name, defaulting to the rule id when none was set.
   *
   * @return the rule name (never null)
   */
  public String getName() {
    return name;
  }

  /**
   * Returns whether this rule is enabled.
   *
   * @return true if the rule should be evaluated
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Returns the scope that determines which bucket a request maps to.
   *
   * @return the limit scope (never null)
   */
  public LimitScope getScope() {
    return scope;
  }

  /**
   * Returns the identifier of the key strategy, for example "ip", "userId" or "apiKey".
   *
   * @return the key strategy id (never null)
   */
  public String getKeyStrategyId() {
    return keyStrategyId;
  }

  /**
   * Returns the policy applied when the limit is exceeded.
   *
   * @return the policy (never null)
   */
  public OnLimitExceedPolicy getOnLimitExceedPolicy() {
    return onLimitExceedPolicy;
  }

  /**
   * Returns the bands of this rule, in configuration order.
   *
   * @return an unmodifiable list of bands (never empty)
   */
  public List<RateLimitBand> getBands() {
    return Collections.unmodifiableList(bands);
  }

  /**
   * May be null if this rule is not associated with a specific rule set.
   *
   * @return the rule set id, or null if none was set
   */
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
   * Returns a specific attribute value cast to the expected type, or {@link Optional#empty()} if
   * not present or the value cannot be cast to {@code type}.
   *
   * <p>A type mismatch is logged at {@code DEBUG} level and never propagates as an exception.
   *
   * <p>Example: {@code Optional<String> tier = rule.getAttribute("tier", String.class);}
   *
   * @param key the attribute key
   * @param type the expected type
   * @param <T> the type parameter
   * @return an {@link Optional} containing the cast value, or empty if absent or incompatible
   * @since 0.4.0
   */
  public <T> Optional<T> getAttribute(String key, Class<T> type) {
    Object value = attributes.get(key);
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(type.cast(value));
    } catch (ClassCastException e) {
      log.debug(
          "Attribute '{}' on rule '{}' is of type {} but expected {}; returning empty",
          key,
          id,
          value.getClass().getName(),
          type.getName());
      return Optional.empty();
    }
  }

  /**
   * Returns the priority of this rule. Higher priority rules are evaluated first; ties are broken
   * by rule id ascending.
   *
   * @return the priority (default 0)
   * @since 0.4.0
   */
  public int getPriority() {
    return priority;
  }

  /**
   * Returns the matcher that decides whether this rule applies to a given request.
   *
   * @return the matcher (never null; defaults to {@link RuleMatcher#matchAll()})
   * @since 0.4.0
   */
  public RuleMatcher getMatcher() {
    return matcher;
  }

  /**
   * Creates a new builder for a rule with the given id.
   *
   * @param id the rule id (must not be null)
   * @return a new builder
   */
  public static Builder builder(String id) {
    return new Builder(id);
  }

  /** Builder for {@link RateLimitRule}. */
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
    private int priority = 0;
    private RuleMatcher matcher;

    private Builder(String id) {
      this.id = Objects.requireNonNull(id, "id must not be null");
    }

    /**
     * Sets the display name; defaults to the rule id when not set.
     *
     * @param name the display name
     * @return this builder
     */
    public Builder name(String name) {
      this.name = name;
      return this;
    }

    /**
     * Sets whether the rule is enabled.
     *
     * @param enabled true to evaluate this rule
     * @return this builder
     */
    public Builder enabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    /**
     * Sets the scope that determines which bucket a request maps to.
     *
     * @param scope the limit scope
     * @return this builder
     */
    public Builder scope(LimitScope scope) {
      this.scope = scope;
      return this;
    }

    /**
     * References a key strategy defined in SPI, e.g. "ip", "userId", "apiKey".
     *
     * @param keyStrategyId the key strategy id
     * @return this builder
     */
    public Builder keyStrategyId(String keyStrategyId) {
      this.keyStrategyId = keyStrategyId;
      return this;
    }

    /**
     * Sets the policy applied when the limit is exceeded.
     *
     * @param policy the policy
     * @return this builder
     */
    public Builder onLimitExceedPolicy(OnLimitExceedPolicy policy) {
      this.onLimitExceedPolicy = policy;
      return this;
    }

    /**
     * Adds a band to this rule. Bands must have distinct key labels.
     *
     * @param band the band to add (must not be null)
     * @return this builder
     */
    public Builder addBand(RateLimitBand band) {
      this.bands.add(Objects.requireNonNull(band, "band must not be null"));
      return this;
    }

    /**
     * Optional: set the logical rule set id this rule belongs to. Only used for observability
     * (logging/metrics).
     *
     * @param ruleSetId the rule set id
     * @return this builder
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

    /**
     * Sets the evaluation priority. Higher values are evaluated first; ties are broken by rule id
     * ascending. Defaults to 0.
     *
     * @param priority the priority
     * @return this builder
     * @since 0.4.0
     */
    public Builder priority(int priority) {
      this.priority = priority;
      return this;
    }

    /**
     * Sets the matcher that decides whether this rule applies to a given request. Defaults to
     * {@link RuleMatcher#matchAll()}.
     *
     * @param matcher the matcher (must not be null)
     * @return this builder
     * @since 0.4.0
     */
    public Builder matcher(RuleMatcher matcher) {
      this.matcher = Objects.requireNonNull(matcher, "matcher must not be null");
      return this;
    }

    /**
     * Builds the rule.
     *
     * @return the rule
     * @throws IllegalArgumentException if no band is configured
     * @throws org.fluxgate.core.exception.InvalidRuleConfigException if two bands share a key label
     */
    public RateLimitRule build() {
      return new RateLimitRule(this);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RateLimitRule)) return false;
    RateLimitRule that = (RateLimitRule) o;
    return enabled == that.enabled
        && priority == that.priority
        && Objects.equals(id, that.id)
        && Objects.equals(name, that.name)
        && scope == that.scope
        && Objects.equals(keyStrategyId, that.keyStrategyId)
        && onLimitExceedPolicy == that.onLimitExceedPolicy
        && Objects.equals(bands, that.bands)
        && Objects.equals(ruleSetId, that.ruleSetId)
        && Objects.equals(attributes, that.attributes)
        && Objects.equals(matcher, that.matcher);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        id,
        name,
        enabled,
        scope,
        keyStrategyId,
        onLimitExceedPolicy,
        bands,
        ruleSetId,
        attributes,
        priority,
        matcher);
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
        + ", priority="
        + priority
        + ", matcher="
        + matcher
        + '}';
  }
}
