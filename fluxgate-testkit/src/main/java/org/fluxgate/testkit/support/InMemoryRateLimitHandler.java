package org.fluxgate.testkit.support;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;

/**
 * A {@link FluxgateRateLimitHandler} that needs no Redis, no MongoDB and no Spring context.
 *
 * <p>It is the same pipeline a Spring Boot application runs — {@link RateLimitEngine} over a {@link
 * Bucket4jRateLimiter} — with the rule sets held in a map instead of a database. That matters: a
 * test written against this handler exercises the real key resolution, the real multi-band
 * evaluation and the real {@link RateLimitResponse} mapping, so it fails when those change.
 *
 * <p>What it deliberately does not do is distribute: buckets live in this instance's cache, so a
 * test cannot use it to make claims about cluster-wide limits.
 *
 * <pre>{@code
 * InMemoryRateLimitHandler handler =
 *     InMemoryRateLimitHandler.withRuleSet(
 *         FluxgateTestRules.rule("per-ip").perIp().band(Duration.ofMinutes(1), 2)
 *             .toRuleSet("api-limits"));
 *
 * RequestContext ctx = RequestContext.builder().clientIp("10.0.0.1").build();
 *
 * assertThat(handler.tryConsume(ctx, "api-limits").isAllowed()).isTrue();
 * assertThat(handler.tryConsume(ctx, "api-limits").isAllowed()).isTrue();
 * assertThat(handler.tryConsume(ctx, "api-limits").isAllowed()).isFalse();
 *
 * handler.reset(); // next test starts with full buckets
 * }</pre>
 *
 * <p>Instances are thread-safe, so a concurrency test can hammer one handler from several threads.
 *
 * @see FluxgateTestRules
 * @see org.fluxgate.testkit.junit.FluxgateInMemoryExtension
 */
public final class InMemoryRateLimitHandler implements FluxgateRateLimitHandler {

  /** Bucket cache size; generous enough that a test never evicts by accident. */
  private static final long MAX_BUCKETS = 10_000L;

  /** Bucket idle expiry; long enough that a slow test never loses state by accident. */
  private static final Duration EXPIRE_AFTER_ACCESS = Duration.ofHours(1);

  private final Map<String, RateLimitRuleSet> ruleSets;
  private final Bucket4jRateLimiter rateLimiter;
  private final RateLimitEngine engine;

  private InMemoryRateLimitHandler(
      Map<String, RateLimitRuleSet> ruleSets, RateLimitEngine.OnMissingRuleSetStrategy strategy) {

    this.ruleSets = ruleSets;
    this.rateLimiter = new Bucket4jRateLimiter(MAX_BUCKETS, EXPIRE_AFTER_ACCESS);
    this.engine =
        RateLimitEngine.builder()
            .ruleSetProvider(provider())
            .rateLimiter(rateLimiter)
            .onMissingRuleSetStrategy(strategy)
            .build();
  }

  /**
   * Creates a handler serving exactly one rule set.
   *
   * @param ruleSet the rule set (must not be null)
   * @return the handler
   */
  public static InMemoryRateLimitHandler withRuleSet(RateLimitRuleSet ruleSet) {
    Objects.requireNonNull(ruleSet, "ruleSet must not be null");
    return builder().ruleSet(ruleSet).build();
  }

  /**
   * Creates a handler serving several rule sets, keyed by their own ids.
   *
   * @param ruleSets the rule sets (must not be null and must not be empty)
   * @return the handler
   */
  public static InMemoryRateLimitHandler withRuleSets(Collection<RateLimitRuleSet> ruleSets) {
    Objects.requireNonNull(ruleSets, "ruleSets must not be null");
    Builder builder = builder();
    ruleSets.forEach(builder::ruleSet);
    return builder.build();
  }

  /**
   * Creates a builder, for when the missing-rule-set behaviour matters.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public RateLimitResponse tryConsume(RequestContext context, String ruleSetId) {
    return tryConsume(context, ruleSetId, 1L);
  }

  @Override
  public RateLimitResponse tryConsume(RequestContext context, String ruleSetId, long permits) {
    return RateLimitResponse.from(engine.check(ruleSetId, context, permits));
  }

  /**
   * Discards every bucket, so the next request starts with a full quota.
   *
   * <p>Call this between test methods. {@link org.fluxgate.testkit.junit.FluxgateInMemoryExtension}
   * does it for you.
   */
  public void reset() {
    rateLimiter.resetAll();
  }

  /**
   * Discards the buckets of a single rule set, leaving the others alone.
   *
   * @param ruleSetId the rule set whose buckets are cleared
   */
  public void reset(String ruleSetId) {
    rateLimiter.reset(ruleSetId);
  }

  /**
   * Returns the number of live buckets, which is a useful assertion in its own right: a rule with
   * two bands and three distinct callers should hold six buckets, not three.
   *
   * @return the number of buckets currently held
   */
  public long bucketCount() {
    return rateLimiter.size();
  }

  /**
   * Returns the ids of the rule sets this handler serves.
   *
   * @return an unmodifiable set of rule set ids
   */
  public Set<String> getRuleSetIds() {
    return java.util.Collections.unmodifiableSet(ruleSets.keySet());
  }

  /**
   * Returns the rule set provider handed to the engine, for tests that want to wire it into
   * something else.
   *
   * @return a provider backed by this handler's rule sets
   */
  public RateLimitRuleSetProvider getRuleSetProvider() {
    return provider();
  }

  /**
   * Returns the underlying limiter, for tests that want to assert on it directly.
   *
   * @return the in-memory rate limiter
   */
  public Bucket4jRateLimiter getRateLimiter() {
    return rateLimiter;
  }

  private RateLimitRuleSetProvider provider() {
    return ruleSetId -> Optional.ofNullable(ruleSets.get(ruleSetId));
  }

  @Override
  public String toString() {
    return "InMemoryRateLimitHandler{ruleSets="
        + ruleSets.keySet()
        + ", buckets="
        + bucketCount()
        + '}';
  }

  /** Builder for {@link InMemoryRateLimitHandler}. */
  public static final class Builder {

    private final Map<String, RateLimitRuleSet> ruleSets = new LinkedHashMap<>();
    private RateLimitEngine.OnMissingRuleSetStrategy onMissingRuleSet =
        RateLimitEngine.OnMissingRuleSetStrategy.ALLOW;

    private Builder() {}

    /**
     * Registers a rule set under its own id.
     *
     * @param ruleSet the rule set (must not be null)
     * @return this builder
     */
    public Builder ruleSet(RateLimitRuleSet ruleSet) {
      Objects.requireNonNull(ruleSet, "ruleSet must not be null");
      ruleSets.put(ruleSet.getId(), ruleSet);
      return this;
    }

    /**
     * Sets what happens when a test asks for a rule set that was never registered.
     *
     * <p>The default is {@link RateLimitEngine.OnMissingRuleSetStrategy#ALLOW}, because a test
     * asking for an unknown rule set is usually asserting that an unlimited path stays unlimited.
     * Use {@code DENY} to mirror the fail-closed production default, or {@code THROW} to make the
     * typo in the rule set id fail loudly.
     *
     * @param strategy the strategy (must not be null)
     * @return this builder
     */
    public Builder onMissingRuleSet(RateLimitEngine.OnMissingRuleSetStrategy strategy) {
      this.onMissingRuleSet = Objects.requireNonNull(strategy, "strategy must not be null");
      return this;
    }

    /**
     * Builds the handler.
     *
     * @return the handler
     * @throws IllegalArgumentException if no rule set was registered
     */
    public InMemoryRateLimitHandler build() {
      if (ruleSets.isEmpty()) {
        throw new IllegalArgumentException("at least one rule set is required");
      }
      return new InMemoryRateLimitHandler(new LinkedHashMap<>(ruleSets), onMissingRuleSet);
    }
  }
}
