package org.fluxgate.core.ratelimiter;

import java.util.Objects;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.RateLimitKey;

/**
 * Result of a single rate limit evaluation.
 *
 * <p>Besides the allow/reject decision the result carries everything an HTTP layer needs to build
 * rate limit headers: the capacity of the band that produced the decision, the epoch millis at
 * which that bucket is full again, the label of that band and the policy to apply on rejection.
 * Unknown numeric values are represented as {@code -1}.
 */
public final class RateLimitResult {

  private final boolean allowed;
  private final long remainingTokens;
  private final long nanosToWaitForRefill;
  private final RateLimitKey key;
  private final RateLimitRule matchedRule;
  private final long limit;
  private final long resetTimeMillis;
  private final OnLimitExceedPolicy policy;
  private final String bandLabel;

  private RateLimitResult(Builder builder) {
    this.allowed = builder.allowed;
    this.remainingTokens = builder.remainingTokens;
    this.nanosToWaitForRefill = builder.nanosToWaitForRefill;
    this.key = builder.key;
    this.matchedRule = builder.matchedRule;
    this.limit = builder.limit;
    this.resetTimeMillis = builder.resetTimeMillis;
    this.policy =
        builder.policy != null
            ? builder.policy
            : (builder.matchedRule != null ? builder.matchedRule.getOnLimitExceedPolicy() : null);
    this.bandLabel = builder.bandLabel;
  }

  /**
   * Whether the request is allowed.
   *
   * @return true if the request may proceed
   */
  public boolean isAllowed() {
    return allowed;
  }

  /**
   * Remaining tokens in the bucket after this call. May be {@code -1} if unknown or not tracked.
   *
   * @return the remaining tokens, or {@code -1} if unknown
   */
  public long getRemainingTokens() {
    return remainingTokens;
  }

  /**
   * Estimated nanoseconds to wait until enough tokens are available. {@code 0} when allowed and
   * immediately reusable, {@code -1} if unknown.
   *
   * @return the wait time in nanoseconds, or {@code -1} if unknown
   */
  public long getNanosToWaitForRefill() {
    return nanosToWaitForRefill;
  }

  /**
   * Returns the resolved key whose bucket was evaluated.
   *
   * @return the rate limit key, or null when no rule was matched
   */
  public RateLimitKey getKey() {
    return key;
  }

  /**
   * The rule that was used to evaluate this request. May be {@code null} if not applicable.
   *
   * @return the matched rule, or null if none applied
   */
  public RateLimitRule getMatchedRule() {
    return matchedRule;
  }

  /**
   * Returns true if a rule was matched for this request.
   *
   * @return true when {@link #getMatchedRule()} is non-null
   */
  public boolean hasRule() {
    return matchedRule != null;
  }

  /**
   * Capacity of the band that produced this result: the rejecting band when rejected, the most
   * restrictive band when allowed. {@code -1} if unknown.
   *
   * @return the band capacity, or {@code -1} if unknown
   */
  public long getLimit() {
    return limit;
  }

  /**
   * Epoch millis at which the bucket behind {@link #getLimit()} is full again. {@code -1} if
   * unknown.
   *
   * @return the reset time in epoch millis, or {@code -1} if unknown
   */
  public long getResetTimeMillis() {
    return resetTimeMillis;
  }

  /**
   * Policy to apply when the limit is exceeded. Defaults to the matched rule's policy and is {@code
   * null} when no rule was matched and no policy was set explicitly.
   *
   * @return the policy, or null if none applies
   */
  public OnLimitExceedPolicy getPolicy() {
    return policy;
  }

  /**
   * Label of the band that produced this result. May be {@code null} if unknown or not tracked.
   *
   * @return the band label, or null if unknown
   * @see org.fluxgate.core.config.RateLimitBand#getKeyLabel()
   */
  public String getBandLabel() {
    return bandLabel;
  }

  /**
   * Creates a new builder for the given key.
   *
   * @param key the resolved rate limit key; may be null only for allowed results
   * @return a new builder
   */
  public static Builder builder(RateLimitKey key) {
    return new Builder(key);
  }

  /**
   * Creates an allowed result.
   *
   * @param key the resolved rate limit key
   * @param rule the matched rule
   * @param remainingTokens remaining tokens after this call ({@code -1} if unknown)
   * @param nanosToWaitForRefill nanoseconds until the next token is available
   * @return the allowed result
   */
  public static RateLimitResult allowed(
      RateLimitKey key, RateLimitRule rule, long remainingTokens, long nanosToWaitForRefill) {

    return builder(key)
        .allowed(true)
        .matchedRule(rule)
        .remainingTokens(remainingTokens)
        .nanosToWaitForRefill(nanosToWaitForRefill)
        .build();
  }

  /**
   * Creates an allowed result that also carries the band capacity and reset time.
   *
   * @param key the resolved rate limit key
   * @param rule the matched rule
   * @param remainingTokens remaining tokens after this call ({@code -1} if unknown)
   * @param nanosToWaitForRefill nanoseconds until the next token is available
   * @param limit capacity of the most restrictive band ({@code -1} if unknown)
   * @param resetTimeMillis epoch millis when that bucket is full again ({@code -1} if unknown)
   * @return the allowed result
   */
  public static RateLimitResult allowed(
      RateLimitKey key,
      RateLimitRule rule,
      long remainingTokens,
      long nanosToWaitForRefill,
      long limit,
      long resetTimeMillis) {

    return builder(key)
        .allowed(true)
        .matchedRule(rule)
        .remainingTokens(remainingTokens)
        .nanosToWaitForRefill(nanosToWaitForRefill)
        .limit(limit)
        .resetTimeMillis(resetTimeMillis)
        .build();
  }

  /**
   * Creates a rejected result that reports zero remaining tokens.
   *
   * @param key the resolved rate limit key (must not be null)
   * @param rule the matched rule
   * @param nanosToWaitForRefill nanoseconds until enough tokens are available
   * @return the rejected result
   */
  public static RateLimitResult rejected(
      RateLimitKey key, RateLimitRule rule, long nanosToWaitForRefill) {

    return builder(key)
        .allowed(false)
        .matchedRule(rule)
        .remainingTokens(0L)
        .nanosToWaitForRefill(nanosToWaitForRefill)
        .build();
  }

  /**
   * Creates a rejected result that also carries the real remaining tokens, the band capacity and
   * the reset time.
   *
   * @param key the resolved rate limit key
   * @param rule the matched rule
   * @param remainingTokens remaining tokens in the rejecting bucket ({@code -1} if unknown)
   * @param nanosToWaitForRefill nanoseconds until enough tokens are available
   * @param limit capacity of the rejecting band ({@code -1} if unknown)
   * @param resetTimeMillis epoch millis when that bucket is full again ({@code -1} if unknown)
   * @return the rejected result
   */
  public static RateLimitResult rejected(
      RateLimitKey key,
      RateLimitRule rule,
      long remainingTokens,
      long nanosToWaitForRefill,
      long limit,
      long resetTimeMillis) {

    return builder(key)
        .allowed(false)
        .matchedRule(rule)
        .remainingTokens(remainingTokens)
        .nanosToWaitForRefill(nanosToWaitForRefill)
        .limit(limit)
        .resetTimeMillis(resetTimeMillis)
        .build();
  }

  /**
   * Creates an allowed result for a request that matched no rule.
   *
   * <p>Remaining tokens are reported as {@code -1} (unknown) rather than {@code Long.MAX_VALUE}: no
   * bucket was consulted, so no quota can be advertised.
   *
   * @return the allowed result without a rule
   */
  public static RateLimitResult allowedWithoutRule() {
    return RateLimitResult.builder(null)
        .allowed(true)
        .remainingTokens(-1L) // unknown: no bucket was consulted
        .nanosToWaitForRefill(0)
        .matchedRule(null)
        .build();
  }

  /** Builder for {@link RateLimitResult}. */
  public static final class Builder {
    private final RateLimitKey key;
    private boolean allowed;
    private long remainingTokens = -1L;
    private long nanosToWaitForRefill = -1L;
    private RateLimitRule matchedRule;
    private long limit = -1L;
    private long resetTimeMillis = -1L;
    private OnLimitExceedPolicy policy;
    private String bandLabel;

    private Builder(RateLimitKey key) {
      this.key = key;
    }

    /**
     * Sets whether the request is allowed.
     *
     * @param allowed true if the request may proceed
     * @return this builder
     */
    public Builder allowed(boolean allowed) {
      this.allowed = allowed;
      return this;
    }

    /**
     * Sets the remaining tokens after this call; {@code -1} if unknown.
     *
     * @param remainingTokens the remaining tokens
     * @return this builder
     */
    public Builder remainingTokens(long remainingTokens) {
      this.remainingTokens = remainingTokens;
      return this;
    }

    /**
     * Sets the nanoseconds to wait until enough tokens are available; {@code -1} if unknown.
     *
     * @param nanosToWaitForRefill the wait time in nanoseconds
     * @return this builder
     */
    public Builder nanosToWaitForRefill(long nanosToWaitForRefill) {
      this.nanosToWaitForRefill = nanosToWaitForRefill;
      return this;
    }

    /**
     * Sets the rule that produced this result; also supplies the default policy.
     *
     * @param matchedRule the matched rule
     * @return this builder
     */
    public Builder matchedRule(RateLimitRule matchedRule) {
      this.matchedRule = matchedRule;
      return this;
    }

    /**
     * Capacity of the band that produced this result; {@code -1} if unknown.
     *
     * @param limit the band capacity
     * @return this builder
     */
    public Builder limit(long limit) {
      this.limit = limit;
      return this;
    }

    /**
     * Epoch millis when the bucket is full again; {@code -1} if unknown.
     *
     * @param resetTimeMillis the reset time in epoch millis
     * @return this builder
     */
    public Builder resetTimeMillis(long resetTimeMillis) {
      this.resetTimeMillis = resetTimeMillis;
      return this;
    }

    /**
     * Policy to apply when the limit is exceeded; defaults to the matched rule's policy.
     *
     * @param policy the policy
     * @return this builder
     */
    public Builder policy(OnLimitExceedPolicy policy) {
      this.policy = policy;
      return this;
    }

    /**
     * Label of the band that produced this result; may be null.
     *
     * @param bandLabel the band label
     * @return this builder
     */
    public Builder bandLabel(String bandLabel) {
      this.bandLabel = bandLabel;
      return this;
    }

    /**
     * Builds the result.
     *
     * @return the result
     * @throws IllegalStateException if the result is rejected and no key was supplied
     */
    public RateLimitResult build() {
      if (!allowed && key == null) {
        throw new IllegalStateException("key must not be null for rejected result");
      }
      return new RateLimitResult(this);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RateLimitResult)) return false;
    RateLimitResult that = (RateLimitResult) o;
    return allowed == that.allowed
        && remainingTokens == that.remainingTokens
        && nanosToWaitForRefill == that.nanosToWaitForRefill
        && limit == that.limit
        && resetTimeMillis == that.resetTimeMillis
        && Objects.equals(key, that.key)
        && Objects.equals(matchedRule, that.matchedRule)
        && policy == that.policy
        && Objects.equals(bandLabel, that.bandLabel);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        allowed,
        remainingTokens,
        nanosToWaitForRefill,
        key,
        matchedRule,
        limit,
        resetTimeMillis,
        policy,
        bandLabel);
  }

  @Override
  public String toString() {
    return "RateLimitResult{"
        + "allowed="
        + allowed
        + ", remainingTokens="
        + remainingTokens
        + ", nanosToWaitForRefill="
        + nanosToWaitForRefill
        + ", key="
        + key
        + ", matchedRule="
        + matchedRule
        + ", limit="
        + limit
        + ", resetTimeMillis="
        + resetTimeMillis
        + ", policy="
        + policy
        + ", bandLabel='"
        + bandLabel
        + '\''
        + '}';
  }
}
