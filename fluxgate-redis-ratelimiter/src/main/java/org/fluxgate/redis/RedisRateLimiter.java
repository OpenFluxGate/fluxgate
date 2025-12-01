package org.fluxgate.redis;

import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.redis.store.BucketState;
import org.fluxgate.redis.store.TokenBucketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Redis-backed distributed rate limiter implementation.
 * <p>
 * This implementation:
 * - Stores token buckets in Redis
 * - Uses Lua scripts for atomic refill + consume operations
 * - Supports multi-band rate limiting
 * - Automatically expires buckets using Redis TTL
 * <p>
 * Thread-safe and suitable for distributed environments.
 */
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private static final String KEY_PREFIX = "fluxgate";

    private final TokenBucketStore tokenBucketStore;

    /**
     * Create a new RedisRateLimiter with the given token bucket store.
     *
     * @param tokenBucketStore Redis-backed token bucket store
     */
    public RedisRateLimiter(TokenBucketStore tokenBucketStore) {
        this.tokenBucketStore = Objects.requireNonNull(
                tokenBucketStore, "tokenBucketStore must not be null");
    }

    @Override
    public RateLimitResult tryConsume(RequestContext context,
                                      RateLimitRuleSet ruleSet,
                                      long permits) {

        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");

        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }

        // Resolve the rate limit key for this request
        RateLimitKey logicalKey = ruleSet.getKeyResolver().resolve(context);
        Objects.requireNonNull(logicalKey, "resolved RateLimitKey must not be null");

        // Get rules from the rule set
        List<RateLimitRule> rules = ruleSet.getRules();
        if (rules == null || rules.isEmpty()) {
            log.warn("No rules in ruleSet {}, allowing request by default", ruleSet.getId());
            return RateLimitResult.allowedWithoutRule();
        }

        // Check each rule
        // For multi-band rules, we check each band independently
        // If ANY band rejects, the entire request is rejected
        RateLimitRule matchedRule = null;
        long minRemainingTokens = Long.MAX_VALUE;
        long maxNanosToWait = 0;
        boolean anyRejected = false;

        for (RateLimitRule rule : rules) {
            if (!rule.isEnabled()) {
                continue;
            }

            List<RateLimitBand> bands = rule.getBands();
            if (bands == null || bands.isEmpty()) {
                continue;
            }

            // Check each band in the rule
            for (RateLimitBand band : bands) {
                String bucketKey = buildBucketKey(ruleSet.getId(), rule.getId(), logicalKey, band);

                // Try to consume permits from this band's bucket
                BucketState state = tokenBucketStore.tryConsume(bucketKey, band, permits);

                // Track minimum remaining tokens across all bands
                minRemainingTokens = Math.min(minRemainingTokens, state.remainingTokens());

                if (!state.consumed()) {
                    // This band rejected the request
                    anyRejected = true;
                    maxNanosToWait = Math.max(maxNanosToWait, state.nanosToWaitForRefill());
                    matchedRule = rule; // Track which rule caused the rejection
                }
            }
        }

        // Build result
        RateLimitResult result;
        if (anyRejected) {
            // At least one band rejected
            result = RateLimitResult.rejected(
                    logicalKey,
                    matchedRule,
                    maxNanosToWait
            );
        } else {
            // All bands allowed
            result = RateLimitResult.allowed(
                    logicalKey,
                    matchedRule != null ? matchedRule : rules.get(0),
                    minRemainingTokens,
                    0L
            );
        }

        // Record metrics if configured
        RateLimitMetricsRecorder recorder = ruleSet.getMetricsRecorder();
        if (recorder != null) {
            recorder.record(context, result);
        }

        return result;
    }

    /**
     * Build the Redis key for a token bucket.
     * <p>
     * Format: fluxgate:{ruleSetId}:{ruleId}:{keyValue}:{bandLabel}
     *
     * @param ruleSetId ID of the rule set
     * @param ruleId ID of the rule
     * @param key Rate limit key (e.g., IP address, API key)
     * @param band Rate limit band
     * @return Redis key string
     */
    private String buildBucketKey(String ruleSetId,
                                   String ruleId,
                                   RateLimitKey key,
                                   RateLimitBand band) {
        String bandLabel = band.getLabel() != null ? band.getLabel() : "default";
        return String.format("%s:%s:%s:%s:%s",
                KEY_PREFIX,
                ruleSetId,
                ruleId,
                key.value(),
                bandLabel);
    }

    /**
     * Close the underlying token bucket store.
     */
    public void close() {
        if (tokenBucketStore != null) {
            tokenBucketStore.close();
        }
    }
}
