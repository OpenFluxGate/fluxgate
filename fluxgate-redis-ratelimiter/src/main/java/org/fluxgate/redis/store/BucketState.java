package org.fluxgate.redis.store;

/**
 * Represents the result of a token bucket consume operation.
 *
 * @param consumed Whether the permits were successfully consumed
 * @param remainingTokens Number of tokens remaining in the bucket
 * @param nanosToWaitForRefill Nanoseconds to wait until enough tokens are available
 */
public record BucketState(
        boolean consumed,
        long remainingTokens,
        long nanosToWaitForRefill
) {
    /**
     * Create a BucketState for a successful consumption.
     */
    public static BucketState allowed(long remainingTokens) {
        return new BucketState(true, remainingTokens, 0);
    }

    /**
     * Create a BucketState for a rejected consumption.
     */
    public static BucketState rejected(long remainingTokens, long nanosToWait) {
        return new BucketState(false, remainingTokens, nanosToWait);
    }
}
