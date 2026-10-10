// org.fluxgate.core.ratelimiter.RateLimitRuleSet

package org.fluxgate.core.ratelimiter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.fluxgate.core.config.AccessControl;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.exception.InvalidRuleConfigException;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.match.PathPatternMatcher;
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

  // Optional access control
  private final AccessControl accessControl;

  private RateLimitRuleSet(Builder builder) {
    this.id = Objects.requireNonNull(builder.id, "id must not be null");
    this.description = builder.description;
    this.rules = List.copyOf(builder.rules);
    this.keyResolver = Objects.requireNonNull(builder.keyResolver, "keyResolver must not be null");
    this.metricsRecorder = builder.metricsRecorder;
    this.accessControl =
        builder.accessControl != null ? builder.accessControl : AccessControl.EMPTY;
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
    return rules; // already immutable: List.copyOf in the constructor
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
   * Returns the access control rules for this rule set. Never {@code null}; defaults to {@link
   * AccessControl#EMPTY}.
   *
   * @return the access control (never null)
   * @since 0.4.0
   */
  public AccessControl getAccessControl() {
    return accessControl;
  }

  /**
   * Returns the enabled rules whose {@link org.fluxgate.core.config.RuleMatcher} matches the given
   * context, sorted by {@link RateLimitRule#getPriority() priority} descending then by rule id
   * ascending for determinism.
   *
   * <p>The list defines the evaluation order: limiters should iterate it from index 0 (highest
   * priority). {@link PathPatternMatcher} is not stored on the rule set; callers supply it on each
   * call.
   *
   * @param context the current request context (must not be null)
   * @param pathMatcher the Ant-style path matcher to use (must not be null)
   * @return an unmodifiable, sorted list of matching rules (may be empty)
   * @since 0.4.0
   */
  public List<RateLimitRule> getMatchingRules(
      RequestContext context, PathPatternMatcher pathMatcher) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(pathMatcher, "pathMatcher must not be null");

    List<RateLimitRule> matched = new ArrayList<>();
    for (RateLimitRule rule : rules) {
      if (rule.isEnabled() && rule.getMatcher().matches(context, pathMatcher)) {
        matched.add(rule);
      }
    }
    // priority DESC, then id ASC for determinism
    matched.sort(
        Comparator.comparingInt(RateLimitRule::getPriority)
            .reversed()
            .thenComparing(RateLimitRule::getId));
    return Collections.unmodifiableList(matched);
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
    private AccessControl accessControl;

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
     * Sets the access control rules. Defaults to {@link AccessControl#EMPTY} (no restrictions).
     *
     * @param accessControl the access control (null means no restrictions)
     * @return this builder
     * @since 0.4.0
     */
    public Builder accessControl(AccessControl accessControl) {
      this.accessControl = accessControl;
      return this;
    }

    /**
     * Builds the rule set.
     *
     * @return the rule set
     * @throws InvalidRuleConfigException if no rule is configured, two rules share an id, or no key
     *     resolver is set
     */
    public RateLimitRuleSet build() {
      if (rules.isEmpty()) {
        throw new InvalidRuleConfigException(
            "rules must contain at least one RateLimitRule (ruleSetId: " + id + ")");
      }
      // Buckets and metrics are keyed by rule id, so two rules with one id would share a bucket.
      Set<String> ruleIds = new HashSet<>();
      for (RateLimitRule rule : rules) {
        if (!ruleIds.add(rule.getId())) {
          throw new InvalidRuleConfigException(
              "duplicate rule id '" + rule.getId() + "' (ruleSetId: " + id + ")", rule.getId());
        }
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
        && Objects.equals(metricsRecorder, that.metricsRecorder)
        && Objects.equals(accessControl, that.accessControl);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, description, rules, keyResolver, metricsRecorder, accessControl);
  }
}
