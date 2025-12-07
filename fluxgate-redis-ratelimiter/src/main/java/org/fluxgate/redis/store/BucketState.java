package org.fluxgate.redis.store;

/**
 * Represents the result of a token bucket consume operation.
 *
 * <p>Production implementation includes: - resetTimeMillis for HTTP X-RateLimit-Reset header - All
 * data from production Lua script using Redis TIME (not System.nanoTime())
 *
 * @param consumed Whether the permits were successfully consumed (1 = allowed, 0 = rejected)
 * @param remainingTokens Number of tokens remaining in the bucket
 * @param nanosToWaitForRefill Nanoseconds to wait until enough tokens are available
 * @param resetTimeMillis Unix timestamp in milliseconds when bucket will be full again
 */
public record BucketState(
    boolean consumed, long remainingTokens, long nanosToWaitForRefill, long resetTimeMillis) {
  /** Create a BucketState for a successful consumption. */
  public static BucketState allowed(long remainingTokens, long resetTimeMillis) {
    return new BucketState(true, remainingTokens, 0, resetTimeMillis);
  }

  /** Create a BucketState for a rejected consumption. */
  public static BucketState rejected(long remainingTokens, long nanosToWait, long resetTimeMillis) {
    return new BucketState(false, remainingTokens, nanosToWait, resetTimeMillis);
  }

  /** Get retry-after time in seconds (for HTTP Retry-After header). */
  public long getRetryAfterSeconds() {
    return (nanosToWaitForRefill + 999_999_999) / 1_000_000_000; // Round up
  }
}
