package org.fluxgate.core.engine;

import java.util.Objects;
import java.util.Optional;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;

/**
 * High-level entry point for rate limiting. Responsibilities: - Resolve {@link RateLimitRuleSet} by
 * id via {@link RateLimitRuleSetProvider} - Delegate actual token consumption to {@link
 * RateLimiter} - Define behavior when rule set is missing
 */
public final class RateLimitEngine {

  /** Strategy when no rule set is found for a given id. */
  public enum OnMissingRuleSetStrategy {
    /** Throw an IllegalArgumentException when the rule set id is not found. */
    THROW,

    /**
     * Fail-open: allow the request without applying any rate limiting. This will return an
     * "allowed" result with null rule information.
     */
    ALLOW
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

  public static Builder builder() {
    return new Builder();
  }

  /** Check rate limit with a default of 1 permit. */
  public RateLimitResult check(String ruleSetId, RequestContext context) {
    return check(ruleSetId, context, 1L);
  }

  /** Check rate limit for the given ruleSetId and permits. */
  public RateLimitResult check(String ruleSetId, RequestContext context, long permits) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    Objects.requireNonNull(context, "context must not be null");

    Optional<RateLimitRuleSet> optionalRuleSet = ruleSetProvider.findById(ruleSetId);

    RateLimitRuleSet ruleSet =
        optionalRuleSet.orElseGet(
            () -> {
              if (onMissingRuleSetStrategy == OnMissingRuleSetStrategy.THROW) {
                throw new IllegalArgumentException("Unknown ruleSetId: " + ruleSetId);
              }
              // ALLOW: create a "virtual" empty rule set that effectively does nothing.
              // The RateLimiter implementation should handle this gracefully,
              // or we can simply short-circuit here.
              return null;
            });

    if (ruleSet == null) {
      // Fail-open branch: do not call RateLimiter at all.
      return RateLimitResult.allowedWithoutRule();
    }

    return rateLimiter.tryConsume(context, ruleSet, permits);
  }

  public static final class Builder {
    private RateLimitRuleSetProvider ruleSetProvider;
    private RateLimiter rateLimiter;
    private OnMissingRuleSetStrategy onMissingRuleSetStrategy = OnMissingRuleSetStrategy.THROW;

    private Builder() {}

    public Builder ruleSetProvider(RateLimitRuleSetProvider ruleSetProvider) {
      this.ruleSetProvider = ruleSetProvider;
      return this;
    }

    public Builder rateLimiter(RateLimiter rateLimiter) {
      this.rateLimiter = rateLimiter;
      return this;
    }

    public Builder onMissingRuleSetStrategy(OnMissingRuleSetStrategy strategy) {
      this.onMissingRuleSetStrategy = strategy;
      return this;
    }

    public RateLimitEngine build() {
      return new RateLimitEngine(this);
    }
  }
}
