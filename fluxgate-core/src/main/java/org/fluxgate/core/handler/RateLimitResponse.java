package org.fluxgate.core.handler;

import java.util.Objects;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.ratelimiter.RateLimitResult;

/**
 * Response from rate limit handler.
 *
 * <p>Contains the result of a rate limit check including:
 *
 * <ul>
 *   <li>Whether the request is allowed
 *   <li>Remaining tokens in the bucket
 *   <li>Time to wait before retry (if rejected)
 *   <li>Policy to apply when limit is exceeded
 *   <li>Capacity of the band that produced the decision, its window, its label and when it resets
 * </ul>
 *
 * <p>Unknown numeric values are represented as {@code -1}.
 */
public final class RateLimitResponse {

  private final boolean allowed;
  private final long remainingTokens;
  private final long retryAfterMillis;
  private final OnLimitExceedPolicy onLimitExceedPolicy;
  private final long limit;
  private final long resetTimeMillis;
  private final long windowSeconds;
  private final String bandLabel;

  private RateLimitResponse(
      boolean allowed,
      long remainingTokens,
      long retryAfterMillis,
      OnLimitExceedPolicy onLimitExceedPolicy,
      long limit,
      long resetTimeMillis,
      long windowSeconds,
      String bandLabel) {
    this.allowed = allowed;
    this.remainingTokens = remainingTokens;
    this.retryAfterMillis = retryAfterMillis;
    this.onLimitExceedPolicy = onLimitExceedPolicy;
    this.limit = limit;
    this.resetTimeMillis = resetTimeMillis;
    this.windowSeconds = windowSeconds;
    this.bandLabel = bandLabel;
  }

  /**
   * Creates an allowed response.
   *
   * @param remainingTokens Number of tokens remaining (-1 if unknown)
   * @param retryAfterMillis Milliseconds until next token available
   * @return Allowed response
   */
  public static RateLimitResponse allowed(long remainingTokens, long retryAfterMillis) {
    return new RateLimitResponse(
        true, remainingTokens, retryAfterMillis, null, -1L, -1L, -1L, null);
  }

  /**
   * Creates an allowed response carrying the band capacity and reset time.
   *
   * @param remainingTokens Number of tokens remaining (-1 if unknown)
   * @param retryAfterMillis Milliseconds until next token available
   * @param limit Capacity of the most restrictive band (-1 if unknown)
   * @param resetTimeMillis Epoch millis when that bucket is full again (-1 if unknown)
   * @return Allowed response
   */
  public static RateLimitResponse allowed(
      long remainingTokens, long retryAfterMillis, long limit, long resetTimeMillis) {
    return new RateLimitResponse(
        true, remainingTokens, retryAfterMillis, null, limit, resetTimeMillis, -1L, null);
  }

  /**
   * Creates a rejected response with default REJECT_REQUEST policy.
   *
   * @param retryAfterMillis Milliseconds to wait before retry
   * @return Rejected response
   */
  public static RateLimitResponse rejected(long retryAfterMillis) {
    return new RateLimitResponse(
        false, 0, retryAfterMillis, OnLimitExceedPolicy.REJECT_REQUEST, -1L, -1L, -1L, null);
  }

  /**
   * Creates a rejected response with a specific policy.
   *
   * @param retryAfterMillis Milliseconds to wait before retry
   * @param policy The policy to apply when limit is exceeded
   * @return Rejected response
   */
  public static RateLimitResponse rejected(long retryAfterMillis, OnLimitExceedPolicy policy) {
    return new RateLimitResponse(false, 0, retryAfterMillis, policy, -1L, -1L, -1L, null);
  }

  /**
   * Creates a rejected response carrying the real remaining tokens, the band capacity and the reset
   * time.
   *
   * @param retryAfterMillis Milliseconds to wait before retry
   * @param policy The policy to apply when limit is exceeded
   * @param remainingTokens Number of tokens remaining in the rejecting bucket (-1 if unknown)
   * @param limit Capacity of the rejecting band (-1 if unknown)
   * @param resetTimeMillis Epoch millis when that bucket is full again (-1 if unknown)
   * @return Rejected response
   */
  public static RateLimitResponse rejected(
      long retryAfterMillis,
      OnLimitExceedPolicy policy,
      long remainingTokens,
      long limit,
      long resetTimeMillis) {
    return new RateLimitResponse(
        false, remainingTokens, retryAfterMillis, policy, limit, resetTimeMillis, -1L, null);
  }

  /**
   * Converts a {@link RateLimitResult} into a response.
   *
   * <p>The wait time is converted from nanoseconds to milliseconds <b>rounding up</b>, so a sub-
   * millisecond wait never becomes {@code Retry-After: 0} and sends the client into a busy loop.
   * {@code 0} stays {@code 0} and {@code -1} (unknown) stays {@code -1}. Policy, limit, reset time
   * and the real remaining tokens are carried over unchanged.
   *
   * <p>The band window is derived by matching {@link RateLimitResult#getBandLabel()} against the
   * bands of the matched rule, so an HTTP layer can emit {@code RateLimit-Policy:
   * <limit>;w=<window>} without consulting the rule itself. It is {@code -1} when the band cannot
   * be identified.
   *
   * @param result the rate limit result (must not be null)
   * @return the equivalent response
   */
  public static RateLimitResponse from(RateLimitResult result) {
    Objects.requireNonNull(result, "result must not be null");
    return new RateLimitResponse(
        result.isAllowed(),
        result.getRemainingTokens(),
        nanosToMillisCeil(result.getNanosToWaitForRefill()),
        result.getPolicy(),
        result.getLimit(),
        result.getResetTimeMillis(),
        resolveWindowSeconds(result),
        result.getBandLabel());
  }

  /**
   * Creates a new builder.
   *
   * <p>Prefer the factories above for the common cases; the builder exists for callers that need to
   * set the band window or label explicitly, such as an HTTP layer assembling headers.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
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
   * Number of remaining tokens in the bucket. Returns -1 if unknown.
   *
   * @return the remaining tokens, or -1 if unknown
   */
  public long getRemainingTokens() {
    return remainingTokens;
  }

  /**
   * Milliseconds to wait before retry. Relevant when rejected.
   *
   * @return the retry delay in milliseconds, or -1 if unknown
   */
  public long getRetryAfterMillis() {
    return retryAfterMillis;
  }

  /**
   * The policy to apply when the limit is exceeded. Returns null if allowed, or the matched rule's
   * policy if rejected.
   *
   * @return the policy, or null if none applies
   */
  public OnLimitExceedPolicy getOnLimitExceedPolicy() {
    return onLimitExceedPolicy;
  }

  /**
   * Capacity of the band that produced this response: the rejecting band when rejected, the most
   * restrictive band when allowed. Returns -1 if unknown.
   *
   * @return the band capacity, or -1 if unknown
   */
  public long getLimit() {
    return limit;
  }

  /**
   * Epoch millis at which the bucket behind {@link #getLimit()} is full again. Returns -1 if
   * unknown.
   *
   * @return the reset time in epoch millis, or -1 if unknown
   */
  public long getResetTimeMillis() {
    return resetTimeMillis;
  }

  /**
   * Length in seconds of the window behind {@link #getLimit()}. Returns -1 if unknown.
   *
   * <p>Together with {@link #getLimit()} this yields the {@code RateLimit-Policy} header value, for
   * example {@code 100;w=60}. The header unit is whole seconds, so a sub-second window is rounded
   * <b>up</b> to {@code 1} rather than reported as {@code 0}: a {@code w=0} policy is meaningless
   * to a client, and rounding up keeps its backoff conservative.
   *
   * @return the window length in seconds, or -1 if unknown
   */
  public long getWindowSeconds() {
    return windowSeconds;
  }

  /**
   * Label of the band that produced this response. Returns null if unknown.
   *
   * @return the band label, or null if unknown
   * @see RateLimitBand#getKeyLabel()
   */
  public String getBandLabel() {
    return bandLabel;
  }

  /**
   * Check if this response should wait for refill instead of immediate rejection.
   *
   * @return true if the request was rejected with the WAIT_FOR_REFILL policy
   */
  public boolean shouldWaitForRefill() {
    return !allowed && onLimitExceedPolicy == OnLimitExceedPolicy.WAIT_FOR_REFILL;
  }

  /**
   * Finds the band named by the result and returns its window length in seconds.
   *
   * <p>The length is rounded up to at least one second, because the header unit is whole seconds
   * and truncating a 500ms window to {@code 0} produced a {@code RateLimit-Policy} value no client
   * can act on.
   *
   * @param result the rate limit result
   * @return the window length in seconds, or {@code -1} if the band cannot be identified
   */
  private static long resolveWindowSeconds(RateLimitResult result) {
    RateLimitRule rule = result.getMatchedRule();
    String label = result.getBandLabel();
    if (rule == null || label == null) {
      return -1L;
    }
    for (RateLimitBand band : rule.getBands()) {
      if (label.equals(band.getKeyLabel()) || label.equals(band.getLabel())) {
        return millisToSecondsCeil(band.getWindow().toMillis());
      }
    }
    return -1L;
  }

  /** Rounds a window length up to whole seconds, never below one. */
  private static long millisToSecondsCeil(long millis) {
    return Math.max(1L, (millis + 999L) / 1000L);
  }

  private static long nanosToMillisCeil(long nanos) {
    if (nanos <= 0) {
      return nanos; // 0 stays 0, -1 (unknown) stays -1
    }
    return (nanos + 999_999L) / 1_000_000L;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RateLimitResponse)) return false;
    RateLimitResponse that = (RateLimitResponse) o;
    return allowed == that.allowed
        && remainingTokens == that.remainingTokens
        && retryAfterMillis == that.retryAfterMillis
        && limit == that.limit
        && resetTimeMillis == that.resetTimeMillis
        && windowSeconds == that.windowSeconds
        && onLimitExceedPolicy == that.onLimitExceedPolicy
        && Objects.equals(bandLabel, that.bandLabel);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        allowed,
        remainingTokens,
        retryAfterMillis,
        onLimitExceedPolicy,
        limit,
        resetTimeMillis,
        windowSeconds,
        bandLabel);
  }

  @Override
  public String toString() {
    return "RateLimitResponse{"
        + "allowed="
        + allowed
        + ", remainingTokens="
        + remainingTokens
        + ", retryAfterMillis="
        + retryAfterMillis
        + ", onLimitExceedPolicy="
        + onLimitExceedPolicy
        + ", limit="
        + limit
        + ", resetTimeMillis="
        + resetTimeMillis
        + ", windowSeconds="
        + windowSeconds
        + ", bandLabel='"
        + bandLabel
        + '\''
        + '}';
  }

  /** Builder for {@link RateLimitResponse}. */
  public static final class Builder {
    private boolean allowed;
    private long remainingTokens = -1L;
    private long retryAfterMillis = -1L;
    private OnLimitExceedPolicy onLimitExceedPolicy;
    private long limit = -1L;
    private long resetTimeMillis = -1L;
    private long windowSeconds = -1L;
    private String bandLabel;

    private Builder() {}

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
     * Sets the remaining tokens in the bucket; {@code -1} if unknown.
     *
     * @param remainingTokens the remaining tokens
     * @return this builder
     */
    public Builder remainingTokens(long remainingTokens) {
      this.remainingTokens = remainingTokens;
      return this;
    }

    /**
     * Sets the milliseconds to wait before retry; {@code -1} if unknown.
     *
     * @param retryAfterMillis the retry delay in milliseconds
     * @return this builder
     */
    public Builder retryAfterMillis(long retryAfterMillis) {
      this.retryAfterMillis = retryAfterMillis;
      return this;
    }

    /**
     * Sets the policy to apply when the limit is exceeded.
     *
     * @param policy the policy
     * @return this builder
     */
    public Builder onLimitExceedPolicy(OnLimitExceedPolicy policy) {
      this.onLimitExceedPolicy = policy;
      return this;
    }

    /**
     * Sets the capacity of the band that produced this response; {@code -1} if unknown.
     *
     * @param limit the band capacity
     * @return this builder
     */
    public Builder limit(long limit) {
      this.limit = limit;
      return this;
    }

    /**
     * Sets the epoch millis when the bucket is full again; {@code -1} if unknown.
     *
     * @param resetTimeMillis the reset time in epoch millis
     * @return this builder
     */
    public Builder resetTimeMillis(long resetTimeMillis) {
      this.resetTimeMillis = resetTimeMillis;
      return this;
    }

    /**
     * Sets the window length in seconds of the band that produced this response; {@code -1} if
     * unknown.
     *
     * @param windowSeconds the window length in seconds
     * @return this builder
     */
    public Builder windowSeconds(long windowSeconds) {
      this.windowSeconds = windowSeconds;
      return this;
    }

    /**
     * Sets the label of the band that produced this response; may be null.
     *
     * @param bandLabel the band label
     * @return this builder
     */
    public Builder bandLabel(String bandLabel) {
      this.bandLabel = bandLabel;
      return this;
    }

    /**
     * Builds the response.
     *
     * @return the response
     */
    public RateLimitResponse build() {
      return new RateLimitResponse(
          allowed,
          remainingTokens,
          retryAfterMillis,
          onLimitExceedPolicy,
          limit,
          resetTimeMillis,
          windowSeconds,
          bandLabel);
    }
  }
}
