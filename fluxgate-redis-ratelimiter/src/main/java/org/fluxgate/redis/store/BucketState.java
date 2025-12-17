package org.fluxgate.redis.store;

import java.util.Objects;

/**
 * Represents the result of a token bucket consume operation.
 *
 * <p>Production implementation includes: - resetTimeMillis for HTTP X-RateLimit-Reset header - All
 * data from production Lua script using Redis TIME (not System.nanoTime())
 */
public final class BucketState {

  private final boolean consumed;
  private final long remainingTokens;
  private final long nanosToWaitForRefill;
  private final long resetTimeMillis;

  /**
   * Creates a new BucketState.
   *
   * @param consumed Whether the permits were successfully consumed (1 = allowed, 0 = rejected)
   * @param remainingTokens Number of tokens remaining in the bucket
   * @param nanosToWaitForRefill Nanoseconds to wait until enough tokens are available
   * @param resetTimeMillis Unix timestamp in milliseconds when bucket will be full again
   */
  public BucketState(
      boolean consumed, long remainingTokens, long nanosToWaitForRefill, long resetTimeMillis) {
    this.consumed = consumed;
    this.remainingTokens = remainingTokens;
    this.nanosToWaitForRefill = nanosToWaitForRefill;
    this.resetTimeMillis = resetTimeMillis;
  }

  /** Create a BucketState for a successful consumption. */
  public static BucketState allowed(long remainingTokens, long resetTimeMillis) {
    return new BucketState(true, remainingTokens, 0, resetTimeMillis);
  }

  /** Create a BucketState for a rejected consumption. */
  public static BucketState rejected(long remainingTokens, long nanosToWait, long resetTimeMillis) {
    return new BucketState(false, remainingTokens, nanosToWait, resetTimeMillis);
  }

  /** Whether the permits were successfully consumed. */
  public boolean consumed() {
    return consumed;
  }

  /** Number of tokens remaining in the bucket. */
  public long remainingTokens() {
    return remainingTokens;
  }

  /** Nanoseconds to wait until enough tokens are available. */
  public long nanosToWaitForRefill() {
    return nanosToWaitForRefill;
  }

  /** Unix timestamp in milliseconds when bucket will be full again. */
  public long resetTimeMillis() {
    return resetTimeMillis;
  }

  /** Get retry-after time in seconds (for HTTP Retry-After header). */
  public long getRetryAfterSeconds() {
    return (nanosToWaitForRefill + 999_999_999) / 1_000_000_000; // Round up
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof BucketState)) return false;
    BucketState that = (BucketState) o;
    return consumed == that.consumed
        && remainingTokens == that.remainingTokens
        && nanosToWaitForRefill == that.nanosToWaitForRefill
        && resetTimeMillis == that.resetTimeMillis;
  }

  @Override
  public int hashCode() {
    return Objects.hash(consumed, remainingTokens, nanosToWaitForRefill, resetTimeMillis);
  }

  @Override
  public String toString() {
    return "BucketState{"
        + "consumed="
        + consumed
        + ", remainingTokens="
        + remainingTokens
        + ", nanosToWaitForRefill="
        + nanosToWaitForRefill
        + ", resetTimeMillis="
        + resetTimeMillis
        + '}';
  }
}
