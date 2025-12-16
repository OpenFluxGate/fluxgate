package org.fluxgate.core.handler;

import org.fluxgate.core.config.OnLimitExceedPolicy;

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
 * </ul>
 */
public final class RateLimitResponse {

  private final boolean allowed;
  private final long remainingTokens;
  private final long retryAfterMillis;
  private final OnLimitExceedPolicy onLimitExceedPolicy;

  private RateLimitResponse(
      boolean allowed,
      long remainingTokens,
      long retryAfterMillis,
      OnLimitExceedPolicy onLimitExceedPolicy) {
    this.allowed = allowed;
    this.remainingTokens = remainingTokens;
    this.retryAfterMillis = retryAfterMillis;
    this.onLimitExceedPolicy = onLimitExceedPolicy;
  }

  /**
   * Creates an allowed response.
   *
   * @param remainingTokens Number of tokens remaining (-1 if unknown)
   * @param retryAfterMillis Milliseconds until next token available
   * @return Allowed response
   */
  public static RateLimitResponse allowed(long remainingTokens, long retryAfterMillis) {
    return new RateLimitResponse(true, remainingTokens, retryAfterMillis, null);
  }

  /**
   * Creates a rejected response with default REJECT_REQUEST policy.
   *
   * @param retryAfterMillis Milliseconds to wait before retry
   * @return Rejected response
   */
  public static RateLimitResponse rejected(long retryAfterMillis) {
    return new RateLimitResponse(false, 0, retryAfterMillis, OnLimitExceedPolicy.REJECT_REQUEST);
  }

  /**
   * Creates a rejected response with a specific policy.
   *
   * @param retryAfterMillis Milliseconds to wait before retry
   * @param policy The policy to apply when limit is exceeded
   * @return Rejected response
   */
  public static RateLimitResponse rejected(long retryAfterMillis, OnLimitExceedPolicy policy) {
    return new RateLimitResponse(false, 0, retryAfterMillis, policy);
  }

  /** Whether the request is allowed. */
  public boolean isAllowed() {
    return allowed;
  }

  /** Number of remaining tokens in the bucket. Returns -1 if unknown. */
  public long getRemainingTokens() {
    return remainingTokens;
  }

  /** Milliseconds to wait before retry. Relevant when rejected. */
  public long getRetryAfterMillis() {
    return retryAfterMillis;
  }

  /**
   * The policy to apply when the limit is exceeded. Returns null if allowed, or the matched rule's
   * policy if rejected.
   */
  public OnLimitExceedPolicy getOnLimitExceedPolicy() {
    return onLimitExceedPolicy;
  }

  /** Check if this response should wait for refill instead of immediate rejection. */
  public boolean shouldWaitForRefill() {
    return !allowed && onLimitExceedPolicy == OnLimitExceedPolicy.WAIT_FOR_REFILL;
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
        + '}';
  }
}
