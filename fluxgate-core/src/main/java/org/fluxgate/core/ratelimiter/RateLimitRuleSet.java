// org.fluxgate.core.ratelimiter.RateLimitRuleSet

package org.fluxgate.core.ratelimiter;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.exception.InvalidRuleConfigException;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;

/**
 * Groups related rate limit rules together with a key resolution strategy and optional metrics
 * recording.
 *
 * <p>Equality is value-based over every field. The {@link KeyResolver} and {@link
 * RateLimitMetricsRecorder} are compared with their own {@code equals}, so two rule sets built from
 * the same storage document compare equal only when they also share these collaborators.
 */
public final class RateLimitRuleSet {

  private final String id; // e.g. "auth-api-default"
  private final String description; // optional
  private final List<RateLimitRule> rules;

  // Strategy: how to resolve RateLimitKey from RequestContext
  private final KeyResolver keyResolver;

  // Optional metrics hook
  private final RateLimitMetricsRecorder metricsRecorder;

  private RateLimitRuleSet(Builder builder) {
    this.id = Objects.requireNonNull(builder.id, "id must not be null");
    this.description = builder.description;
    this.rules = List.copyOf(builder.rules);
    this.keyResolver = Objects.requireNonNull(builder.keyResolver, "keyResolver must not be null");
    this.metricsRecorder = builder.metricsRecorder;
  }

  /**
   * Returns the rule set identifier.
   *
   * @return the rule set id (never null)
   */
  public String getId() {
    return id;
  }

  /**
   * Returns the optional description.
   *
   * @return the description, or null if none was set
   */
  public String getDescription() {
    return description;
  }

  /**
   * Returns the rules of this rule set, in configuration order.
   *
   * @return an unmodifiable list of rules (never empty)
   */
  public List<RateLimitRule> getRules() {
    return Collections.unmodifiableList(rules);
  }

  /**
   * Returns the strategy that resolves a {@code RateLimitKey} from a request context.
   *
   * @return the key resolver (never null)
   */
  public KeyResolver getKeyResolver() {
    return keyResolver;
  }

  /**
   * Returns the optional metrics hook.
   *
   * @return the metrics recorder, or null if none was set
   */
  public RateLimitMetricsRecorder getMetricsRecorder() {
    return metricsRecorder;
  }

  /**
   * Creates a new builder for a rule set with the given id.
   *
   * @param id the rule set id (must not be null)
   * @return a new builder
   */
  public static Builder builder(String id) {
    return new Builder(id);
  }

  /** Builder for {@link RateLimitRuleSet}. */
  public static final class Builder {
    private final String id;
    private String description;
    private List<RateLimitRule> rules = List.of();
    private KeyResolver keyResolver;
    private RateLimitMetricsRecorder metricsRecorder;

    /**
     * Constructs a builder for a rule set with the given id.
     *
     * @param id the rule set id (must not be null)
     */
    public Builder(String id) {
      this.id = id;
    }

    /**
     * Sets the optional description.
     *
     * @param description the description
     * @return this builder
     */
    public Builder description(String description) {
      this.description = description;
      return this;
    }

    /**
     * Sets the rules of this rule set, replacing any previously set rules.
     *
     * @param rules the rules (must not be null and must contain at least one rule)
     * @return this builder
     */
    public Builder rules(List<RateLimitRule> rules) {
      this.rules = List.copyOf(rules);
      return this;
    }

    /**
     * Sets the strategy that resolves a {@code RateLimitKey} from a request context.
     *
     * @param keyResolver the key resolver (required)
     * @return this builder
     */
    public Builder keyResolver(KeyResolver keyResolver) {
      this.keyResolver = keyResolver;
      return this;
    }

    /**
     * Sets the optional metrics hook.
     *
     * @param metricsRecorder the metrics recorder
     * @return this builder
     */
    public Builder metricsRecorder(RateLimitMetricsRecorder metricsRecorder) {
      this.metricsRecorder = metricsRecorder;
      return this;
    }

    /**
     * Builds the rule set.
     *
     * @return the rule set
     * @throws InvalidRuleConfigException if no rule is configured or no key resolver is set
     */
    public RateLimitRuleSet build() {
      if (rules.isEmpty()) {
        throw new InvalidRuleConfigException(
            "rules must contain at least one RateLimitRule (ruleSetId: " + id + ")");
      }
      if (keyResolver == null) {
        throw new InvalidRuleConfigException(
            "keyResolver must not be null (ruleSetId: " + id + ")");
      }
      return new RateLimitRuleSet(this);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RateLimitRuleSet)) return false;
    RateLimitRuleSet that = (RateLimitRuleSet) o;
    return Objects.equals(id, that.id)
        && Objects.equals(description, that.description)
        && Objects.equals(rules, that.rules)
        && Objects.equals(keyResolver, that.keyResolver)
        && Objects.equals(metricsRecorder, that.metricsRecorder);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, description, rules, keyResolver, metricsRecorder);
  }
}
