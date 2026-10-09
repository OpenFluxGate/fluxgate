package org.fluxgate.core.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fluxgate.core.config.AccessControl;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.match.PathPatternMatcher;
import org.fluxgate.core.match.SimpleAntPathMatcher;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitResult.DecisionReason;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;

/**
 * High-level entry point for rate limiting. Responsibilities: - Resolve {@link RateLimitRuleSet} by
 * id via {@link RateLimitRuleSetProvider} - Evaluate {@link AccessControl} before delegating to the
 * limiter - Delegate actual token consumption to {@link RateLimiter} - Define behavior when rule
 * set is missing
 *
 * <p>This is the canonical entry point: Spring starters wire {@code
 * fluxgate.ratelimit.missing-rule-behavior} onto {@link OnMissingRuleSetStrategy} and expose the
 * engine through a {@code FluxgateRateLimitHandler}.
 */
public final class RateLimitEngine {

  /** Prefix of the synthetic key reported when no rule set exists and the strategy is DENY. */
  private static final String MISSING_RULE_SET_KEY_PREFIX = "missing-rule-set:";

  /** Prefix of the synthetic key reported when access control denies the request. */
  private static final String DENIED_KEY_PREFIX = "denied:";

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
     * no matched rule, {@code nanosToWait = 0} and a synthetic key of the form {@code
     * missing-rule-set:<id>} so the rejection is traceable in metrics and logs.
     */
    DENY
  }

  private final RateLimitRuleSetProvider ruleSetProvider;
  private final RateLimiter rateLimiter;
  private final OnMissingRuleSetStrategy onMissingRuleSetStrategy;
  private final PathPatternMatcher pathMatcher;

  private RateLimitEngine(Builder builder) {
    this.ruleSetProvider =
        Objects.requireNonNull(builder.ruleSetProvider, "ruleSetProvider must not be null");
    this.rateLimiter = Objects.requireNonNull(builder.rateLimiter, "rateLimiter must not be null");
    this.onMissingRuleSetStrategy =
        Objects.requireNonNull(
            builder.onMissingRuleSetStrategy, "onMissingRuleSetStrategy must not be null");
    this.pathMatcher =
        builder.pathMatcher != null ? builder.pathMatcher : SimpleAntPathMatcher.INSTANCE;
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
   * <p>Access control is evaluated before calling the limiter: {@link AccessControl.Decision#DENY}
   * returns a rejected result immediately; {@link AccessControl.Decision#ALLOW_BYPASS} returns an
   * allowed result without consuming any tokens.
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

    RateLimitRuleSet ruleSet = optionalRuleSet.get();

    // ===== access control =====
    AccessControl accessControl = ruleSet.getAccessControl();
    if (!accessControl.isEmpty()) {
      List<RateLimitKey> resolvedKeys = new ArrayList<>();
      RateLimitKey primaryKey = resolveKeysForAccessControl(context, ruleSet, resolvedKeys);
      AccessControl.Decision decision =
          accessControl.evaluate(context.getClientIp(), primaryKey, resolvedKeys);
      if (decision == AccessControl.Decision.DENY) {
        return RateLimitResult.builder(
                deniedKey(accessControl, context.getClientIp(), resolvedKeys))
            .allowed(false)
            .remainingTokens(0L)
            .nanosToWaitForRefill(0L)
            .decisionReason(DecisionReason.ACCESS_DENIED)
            .build();
      }
      if (decision == AccessControl.Decision.ALLOW_BYPASS) {
        return RateLimitResult.builder(null)
            .allowed(true)
            .remainingTokens(-1L)
            .nanosToWaitForRefill(0L)
            .decisionReason(DecisionReason.ACCESS_BYPASS)
            .build();
      }
    }

    // ===== delegate to limiter =====
    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, permits, pathMatcher);
    if (result == null) {
      throw new IllegalStateException(
          "RateLimiter "
              + rateLimiter.getClass().getName()
              + " returned null for ruleSetId: "
              + ruleSetId);
    }
    return result;
  }

  /**
   * Resolves the rate limit keys used for access control key-list evaluation.
   *
   * <p>Adds the key of every matching rule (highest priority first) to {@code keys}, so a denied
   * key is caught regardless of which matching rule resolves it. Rules whose key cannot be resolved
   * are skipped. Falls back to a single synthetic IP key when no key could be resolved; {@code
   * keys} is never left empty.
   *
   * @return the key of the highest-priority matching rule (the only key allowed to grant a
   *     key-based bypass), the synthetic IP key when no rule matches, or {@code null} when the
   *     highest-priority rule's key cannot be resolved
   */
  private RateLimitKey resolveKeysForAccessControl(
      RequestContext context, RateLimitRuleSet ruleSet, List<RateLimitKey> keys) {
    List<RateLimitRule> matchingRules = ruleSet.getMatchingRules(context, pathMatcher);
    RateLimitKey primaryKey = null;
    for (int i = 0; i < matchingRules.size(); i++) {
      try {
        RateLimitKey key = ruleSet.getKeyResolver().resolve(context, matchingRules.get(i));
        keys.add(key);
        if (i == 0) {
          primaryKey = key;
        }
      } catch (Exception e) {
        // skip: the IP lists are still checked against the client IP
      }
    }
    if (keys.isEmpty()) {
      // fallback: synthetic IP key
      String ip = context.getClientIp();
      RateLimitKey fallback =
          RateLimitKey.of("ip:" + (ip != null && !ip.isEmpty() ? ip : "unknown"));
      keys.add(fallback);
      if (matchingRules.isEmpty()) {
        primaryKey = fallback;
      }
    }
    return primaryKey;
  }

  /**
   * Builds the synthetic key reported for a denied request, naming the cause: {@code
   * denied:ip:<clientIp>} when the client IP is denied, otherwise {@code denied:<key>} for the
   * first denied key.
   */
  private static RateLimitKey deniedKey(
      AccessControl accessControl, String clientIp, List<RateLimitKey> keys) {
    if (clientIp != null
        && !clientIp.isEmpty()
        && !accessControl.getDeniedIps().isEmpty()
        && accessControl.getDeniedIps().contains(clientIp)) {
      return RateLimitKey.of(DENIED_KEY_PREFIX + "ip:" + clientIp);
    }
    for (RateLimitKey key : keys) {
      if (accessControl.getDeniedKeys().contains(key.value())) {
        return RateLimitKey.of(DENIED_KEY_PREFIX + key.value());
      }
    }
    return RateLimitKey.of(DENIED_KEY_PREFIX + keys.get(0).value());
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
            .decisionReason(DecisionReason.MISSING_RULE_SET)
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
    private PathPatternMatcher pathMatcher;

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
     * Sets the path pattern matcher used to filter applicable rules. Defaults to {@link
     * SimpleAntPathMatcher#INSTANCE} (case-sensitive).
     *
     * @param pathMatcher the path matcher (null restores the default)
     * @return this builder
     * @since 0.4.0
     */
    public Builder pathMatcher(PathPatternMatcher pathMatcher) {
      this.pathMatcher = pathMatcher;
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
