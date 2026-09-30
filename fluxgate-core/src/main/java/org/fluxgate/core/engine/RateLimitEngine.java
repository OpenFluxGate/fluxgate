package org.fluxgate.core.engine;

import java.util.Objects;
import java.util.Optional;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;

/**
 * High-level entry point for rate limiting. Responsibilities: - Resolve {@link RateLimitRuleSet} by
 * id via {@link RateLimitRuleSetProvider} - Delegate actual token consumption to {@link
 * RateLimiter} - Define behavior when rule set is missing
 *
 * <p>This is the canonical entry point: Spring starters wire {@code
 * fluxgate.ratelimit.missing-rule-behavior} onto {@link OnMissingRuleSetStrategy} and expose the
 * engine through a {@code FluxgateRateLimitHandler}.
 */
public final class RateLimitEngine {

  /** Prefix of the synthetic key reported when no rule set exists and the strategy is DENY. */
  private static final String MISSING_RULE_SET_KEY_PREFIX = "missing-rule-set:";

  /** Strategy when no rule set is found for a given id. */
  public enum OnMissingRuleSetStrategy {
    /** Throw an IllegalArgumentException when the rule set id is not found. */
    THROW,

    /**
     * Fail-open: allow the request without applying any rate limiting. This will return an
     * "allowed" result with null rule information.
     */
    ALLOW,

    /**
     * Fail-closed: reject the request without applying any rate limiting. The returned result has
     * no matched rule, {@code nanosToWaitForRefill = 0} and a synthetic key of the form {@code
     * missing-rule-set:<id>} so the rejection is traceable in metrics and logs.
     */
    DENY
  }

  private final RateLimitRuleSetProvider ruleSetProvider;
  private final RateLimiter rateLimiter;
  private final OnMissingRuleSetStrategy onMissingRuleSetStrategy;

  private RateLimitEngine(Builder builder) {
    this.ruleSetProvider =
        Objects.requireNonNull(builder.ruleSetProvider, "ruleSetProvider must not be null");
    this.rateLimiter = Objects.requireNonNull(builder.rateLimiter, "rateLimiter must not be null");
    this.onMissingRuleSetStrategy =
        Objects.requireNonNull(
            builder.onMissingRuleSetStrategy, "onMissingRuleSetStrategy must not be null");
  }

  /**
   * Creates a new builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Check rate limit with a default of 1 permit.
   *
   * @param ruleSetId the rule set to apply (must not be null)
   * @param context request-scoped information (must not be null)
   * @return the rate limit result, never null
   */
  public RateLimitResult check(String ruleSetId, RequestContext context) {
    return check(ruleSetId, context, 1L);
  }

  /**
   * Check rate limit for the given ruleSetId and permits.
   *
   * <p>Never returns {@code null}: a missing rule set is resolved by {@link
   * OnMissingRuleSetStrategy}, and a {@link RateLimiter} that breaks its contract by returning
   * {@code null} raises an {@link IllegalStateException} instead of leaking the null to callers.
   *
   * @param ruleSetId the rule set to apply (must not be null)
   * @param context request-scoped information (must not be null)
   * @param permits number of permits to consume
   * @return the rate limit result, never null
   */
  public RateLimitResult check(String ruleSetId, RequestContext context, long permits) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    Objects.requireNonNull(context, "context must not be null");

    Optional<RateLimitRuleSet> optionalRuleSet = ruleSetProvider.findById(ruleSetId);
    if (!optionalRuleSet.isPresent()) {
      return onMissingRuleSet(ruleSetId);
    }

    RateLimitResult result = rateLimiter.tryConsume(context, optionalRuleSet.get(), permits);
    if (result == null) {
      throw new IllegalStateException(
          "RateLimiter "
              + rateLimiter.getClass().getName()
              + " returned null for ruleSetId: "
              + ruleSetId);
    }
    return result;
  }

  private RateLimitResult onMissingRuleSet(String ruleSetId) {
    switch (onMissingRuleSetStrategy) {
      case THROW:
        throw new IllegalArgumentException("Unknown ruleSetId: " + ruleSetId);
      case DENY:
        // Fail-closed branch: do not call RateLimiter at all.
        return RateLimitResult.builder(RateLimitKey.of(MISSING_RULE_SET_KEY_PREFIX + ruleSetId))
            .allowed(false)
            .remainingTokens(0L)
            .nanosToWaitForRefill(0L)
            .build();
      case ALLOW:
      default:
        // Fail-open branch: do not call RateLimiter at all.
        return RateLimitResult.allowedWithoutRule();
    }
  }

  /** Builder for {@link RateLimitEngine}. */
  public static final class Builder {
    private RateLimitRuleSetProvider ruleSetProvider;
    private RateLimiter rateLimiter;
    private OnMissingRuleSetStrategy onMissingRuleSetStrategy = OnMissingRuleSetStrategy.THROW;

    private Builder() {}

    /**
     * Sets the provider used to resolve rule sets by id.
     *
     * @param ruleSetProvider the rule set provider (required)
     * @return this builder
     */
    public Builder ruleSetProvider(RateLimitRuleSetProvider ruleSetProvider) {
      this.ruleSetProvider = ruleSetProvider;
      return this;
    }

    /**
     * Sets the rate limiter that performs the actual token consumption.
     *
     * @param rateLimiter the rate limiter (required)
     * @return this builder
     */
    public Builder rateLimiter(RateLimiter rateLimiter) {
      this.rateLimiter = rateLimiter;
      return this;
    }

    /**
     * Sets the behaviour applied when no rule set exists for the requested id.
     *
     * @param strategy the strategy; defaults to {@link OnMissingRuleSetStrategy#THROW}
     * @return this builder
     */
    public Builder onMissingRuleSetStrategy(OnMissingRuleSetStrategy strategy) {
      this.onMissingRuleSetStrategy = strategy;
      return this;
    }

    /**
     * Builds the engine.
     *
     * @return the engine
     * @throws NullPointerException if the rule set provider or rate limiter is not set
     */
    public RateLimitEngine build() {
      return new RateLimitEngine(this);
    }
  }
}
