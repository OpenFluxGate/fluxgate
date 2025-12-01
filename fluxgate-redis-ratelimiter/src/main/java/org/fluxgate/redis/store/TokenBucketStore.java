package org.fluxgate.redis.store;

import org.fluxgate.core.config.RateLimitBand;

/**
 * Abstraction for storing and managing token buckets.
 * <p>
 * Implementations must ensure atomic refill + consume operations.
 */
public interface TokenBucketStore {

    /**
     * Try to consume the specified number of permits from a token bucket.
     * <p>
     * This operation is atomic: it refills tokens based on elapsed time,
     * then attempts to consume the requested permits.
     *
     * @param bucketKey Unique key for the bucket (e.g., "fluxgate:rule-1:192.168.1.1:per-second")
     * @param band Rate limit band configuration (capacity, window)
     * @param permits Number of permits to consume
     * @return BucketState indicating success/failure and remaining tokens
     */
    BucketState tryConsume(String bucketKey, RateLimitBand band, long permits);

    /**
     * Close/cleanup resources.
     */
    void close();
}
