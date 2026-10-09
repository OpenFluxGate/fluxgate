package org.fluxgate.testkit.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.key.MissingKeyBehavior;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;

/**
 * Fluent builders for the rules a test needs, without the ceremony of the production builders.
 *
 * <p>A test rarely cares about rule names, attributes or band labels; it cares that "ten requests
 * per minute per IP" is in force. This class exists so that intent fits on one line:
 *
 * <pre>{@code
 * RateLimitRuleSet ruleSet =
 *     FluxgateTestRules.rule("per-ip")
 *         .perIp()
 *         .band(Duration.ofMinutes(1), 10)
 *         .toRuleSet("api-limits");
 * }</pre>
 *
 * <p>Several rules go into one rule set with {@link #ruleSet(String, RuleBuilder...)}:
 *
 * <pre>{@code
 * RateLimitRuleSet ruleSet =
 *     FluxgateTestRules.ruleSet(
 *         "api-limits",
 *         FluxgateTestRules.rule("burst").perIp().band(Duration.ofSeconds(1), 5),
 *         FluxgateTestRules.rule("sustained").perIp().band(Duration.ofMinutes(1), 100));
 * }</pre>
 *
 * <p>The produced rules go through the real {@link RateLimitRule.Builder}, so the same validation a
 * production rule gets applies here: a rule needs at least one band, and two bands of one rule may
 * not share a derived key label.
 *
 * @see InMemoryRateLimitHandler
 * @see org.fluxgate.testkit.junit.FluxgateInMemoryExtension
 */
public final class FluxgateTestRules {

  private FluxgateTestRules() {}

  /**
   * Starts building a rule.
   *
   * <p>The rule is enabled, scoped {@link LimitScope#PER_IP} and rejects on limit exceed until you
   * say otherwise.
   *
   * @param ruleId the rule id (must not be null or blank)
   * @return a new rule builder
   */
  public static RuleBuilder rule(String ruleId) {
    return new RuleBuilder(ruleId);
  }

  /**
   * Combines several rule builders into one rule set, resolving keys the way the starters do.
   *
   * <p>The rule set gets a {@link LimitScopeKeyResolver} with {@link
   * MissingKeyBehavior#FALLBACK_TO_IP}, which is the default a Spring Boot application runs with.
   *
   * @param ruleSetId the rule set id (must not be null or blank)
   * @param rules the rules to include (at least one)
   * @return the rule set
   */
  public static RateLimitRuleSet ruleSet(String ruleSetId, RuleBuilder... rules) {
    return ruleSet(ruleSetId, new LimitScopeKeyResolver(), rules);
  }

  /**
   * Combines several rule builders into one rule set with the given missing key behavior.
   *
   * @param ruleSetId the rule set id (must not be null or blank)
   * @param missingKeyBehavior what the resolver does when a scope's value is absent
   * @param rules the rules to include (at least one)
   * @return the rule set
   */
  public static RateLimitRuleSet ruleSet(
      String ruleSetId, MissingKeyBehavior missingKeyBehavior, RuleBuilder... rules) {
    return ruleSet(ruleSetId, new LimitScopeKeyResolver(missingKeyBehavior), rules);
  }

  /**
   * Combines several rule builders into one rule set with an explicit key resolver.
   *
   * @param ruleSetId the rule set id (must not be null or blank)
   * @param keyResolver the resolver the limiter will call (must not be null)
   * @param rules the rules to include (at least one)
   * @return the rule set
   */
  public static RateLimitRuleSet ruleSet(
      String ruleSetId, KeyResolver keyResolver, RuleBuilder... rules) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    Objects.requireNonNull(keyResolver, "keyResolver must not be null");
    Objects.requireNonNull(rules, "rules must not be null");
    if (rules.length == 0) {
      throw new IllegalArgumentException("at least one rule is required");
    }

    List<RateLimitRule> built = new ArrayList<>(rules.length);
    for (RuleBuilder rule : rules) {
      built.add(Objects.requireNonNull(rule, "rules must not contain null").build(ruleSetId));
    }
    return RateLimitRuleSet.builder(ruleSetId).rules(built).keyResolver(keyResolver).build();
  }

  /**
   * A single rule under construction.
   *
   * <p>Instances are mutable and not thread-safe, which is the right trade-off inside a test
   * method.
   */
  public static final class RuleBuilder {

    private final String ruleId;
    private final List<RateLimitBand> bands = new ArrayList<>();
    private String name;
    private boolean enabled = true;
    private LimitScope scope = LimitScope.PER_IP;
    private String keyStrategyId;
    private OnLimitExceedPolicy policy = OnLimitExceedPolicy.REJECT_REQUEST;

    private RuleBuilder(String ruleId) {
      this.ruleId = Objects.requireNonNull(ruleId, "ruleId must not be null");
    }

    /**
     * Sets the human readable rule name. Defaults to the rule id.
     *
     * @param name the rule name
     * @return this builder
     */
    public RuleBuilder name(String name) {
      this.name = name;
      return this;
    }

    /**
     * Marks the rule disabled, so the limiter skips it.
     *
     * <p>Useful for asserting that a disabled rule really is ignored.
     *
     * @return this builder
     */
    public RuleBuilder disabled() {
      this.enabled = false;
      return this;
    }

    /**
     * Limits per client IP ({@code RequestContext.clientIp}).
     *
     * @return this builder
     */
    public RuleBuilder perIp() {
      this.scope = LimitScope.PER_IP;
      this.keyStrategyId = null;
      return this;
    }

    /**
     * Limits per user id ({@code RequestContext.userId}).
     *
     * @return this builder
     */
    public RuleBuilder perUser() {
      this.scope = LimitScope.PER_USER;
      this.keyStrategyId = null;
      return this;
    }

    /**
     * Limits per API key ({@code RequestContext.apiKey}).
     *
     * @return this builder
     */
    public RuleBuilder perApiKey() {
      this.scope = LimitScope.PER_API_KEY;
      this.keyStrategyId = null;
      return this;
    }

    /**
     * Uses one bucket for every request.
     *
     * @return this builder
     */
    public RuleBuilder global() {
      this.scope = LimitScope.GLOBAL;
      this.keyStrategyId = null;
      return this;
    }

    /**
     * Limits per value of a {@code RequestContext} attribute.
     *
     * @param attributeName the attribute the key is read from (must not be null or blank)
     * @return this builder
     */
    public RuleBuilder perAttribute(String attributeName) {
      this.scope = LimitScope.CUSTOM;
      this.keyStrategyId = Objects.requireNonNull(attributeName, "attributeName must not be null");
      return this;
    }

    /**
     * Makes the rule wait for a refill instead of rejecting.
     *
     * @return this builder
     */
    public RuleBuilder waitForRefill() {
      this.policy = OnLimitExceedPolicy.WAIT_FOR_REFILL;
      return this;
    }

    /**
     * Adds a band.
     *
     * <p>The band gets no explicit label, so it keys itself as {@code
     * <capacity>-per-<windowSeconds>s}, exactly as it would in production.
     *
     * @param window the window length (must be positive)
     * @param capacity the number of permits per window (must be positive)
     * @return this builder
     */
    public RuleBuilder band(Duration window, long capacity) {
      bands.add(RateLimitBand.builder(window, capacity).build());
      return this;
    }

    /**
     * Adds a labelled band.
     *
     * @param window the window length (must be positive)
     * @param capacity the number of permits per window (must be positive)
     * @param label the band label, which becomes the bucket key segment
     * @return this builder
     */
    public RuleBuilder band(Duration window, long capacity, String label) {
      bands.add(RateLimitBand.builder(window, capacity).label(label).build());
      return this;
    }

    /**
     * Builds the rule on its own, without a rule set.
     *
     * @return the rule
     */
    public RateLimitRule build() {
      return build(null);
    }

    /**
     * Builds the rule and wraps it in a rule set of its own.
     *
     * @param ruleSetId the rule set id (must not be null or blank)
     * @return the rule set containing just this rule
     */
    public RateLimitRuleSet toRuleSet(String ruleSetId) {
      return FluxgateTestRules.ruleSet(ruleSetId, this);
    }

    private RateLimitRule build(String ruleSetId) {
      if (bands.isEmpty()) {
        throw new IllegalArgumentException(
            "rule '" + ruleId + "' needs at least one band; call band(window, capacity)");
      }

      RateLimitRule.Builder builder =
          RateLimitRule.builder(ruleId)
              .name(name != null ? name : ruleId)
              .enabled(enabled)
              .scope(scope)
              .onLimitExceedPolicy(policy);

      if (keyStrategyId != null) {
        builder.keyStrategyId(keyStrategyId);
      }
      if (ruleSetId != null) {
        builder.ruleSetId(ruleSetId);
      }
      for (RateLimitBand band : bands) {
        builder.addBand(band);
      }
      return builder.build();
    }

    @Override
    public String toString() {
      return "RuleBuilder{id="
          + ruleId
          + ", scope="
          + scope
          + ", bands="
          + Arrays.toString(bands.toArray())
          + '}';
    }
  }
}
