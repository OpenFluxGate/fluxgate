package org.fluxgate.core.ratelimiter.impl.bucket4j;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory Bucket4j-based implementation of {@link RateLimiter}.
 *
 * This implementation is suitable for single-node or local testing.
 * For Redis/Hazelcast distributed backends, create another implementation
 * that delegates to the appropriate Bucket4j extension.
 */
public class Bucket4jRateLimiter implements RateLimiter {

    /**
     * Bucket cache keyed by (ruleSetId + logical key).
     */
    private final ConcurrentMap<BucketKey, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryConsume(RequestContext context,
                                      RateLimitRuleSet ruleSet,
                                      long permits) {

        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");

        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }

        // 1) Resolve logical key
        RateLimitKey logicalKey = ruleSet.getKeyResolver().resolve(context);
        Objects.requireNonNull(logicalKey, "resolved RateLimitKey must not be null");

        BucketKey bucketKey = new BucketKey(ruleSet.getId(), logicalKey);

        // 2) Get or create Bucket for this key
        Bucket bucket = buckets.computeIfAbsent(bucketKey,
                key -> createBucket(ruleSet));

        // For now we simply pick the first rule as "matched rule".
        // If you support multiple rules per set later, we can enhance this.
        RateLimitRule matchedRule = firstRule(ruleSet);

        // 3) Try consume
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(permits);

        RateLimitResult result;
        if (probe.isConsumed()) {
            result = RateLimitResult.allowed(
                    logicalKey,
                    matchedRule,
                    probe.getRemainingTokens(),
                    probe.getNanosToWaitForRefill()
            );
        } else {
            result = RateLimitResult.rejected(
                    logicalKey,
                    matchedRule,
                    probe.getNanosToWaitForRefill()
            );
        }

        // 4) Metrics hook
        RateLimitMetricsRecorder recorder = ruleSet.getMetricsRecorder();
        if (recorder != null) {
            recorder.record(context, result);
        }

        return result;
    }

    private Bucket createBucket(RateLimitRuleSet ruleSet) {
        var builder = Bucket.builder();  // LocalBucketBuilder 로 추론됨

        List<RateLimitRule> rules = ruleSet.getRules();
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("ruleSet must contain at least one RateLimitRule");
        }

        for (RateLimitRule rule : rules) {
            List<RateLimitBand> bands = rule.getBands();
            if (bands == null || bands.isEmpty()) {
                continue;
            }
            for (RateLimitBand band : bands) {
                builder.addLimit(toBandwidth(band));
            }
        }

        return builder.build(); // 최종적으로 Bucket 반환
    }

    private Bandwidth toBandwidth(RateLimitBand band) {
        Duration window = band.getWindow();
        long capacity = band.getCapacity();

        // For now we use greedy refill (full amount over the window).
        return Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, window)
                .build();
    }

    private RateLimitRule firstRule(RateLimitRuleSet ruleSet) {
        List<RateLimitRule> rules = ruleSet.getRules();
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        return rules.getFirst();
    }

    /**
     * Composite key for bucket cache: (ruleSetId, logical RateLimitKey).
     */
    private static final class BucketKey {
        private final String ruleSetId;
        private final RateLimitKey key;

        private BucketKey(String ruleSetId, RateLimitKey key) {
            this.ruleSetId = Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
            this.key = Objects.requireNonNull(key, "key must not be null");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BucketKey that)) return false;
            return ruleSetId.equals(that.ruleSetId) && key.equals(that.key);
        }

        @Override
        public int hashCode() {
            int result = ruleSetId.hashCode();
            result = 31 * result + key.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "BucketKey{" +
                    "ruleSetId='" + ruleSetId + '\'' +
                    ", key=" + key +
                    '}';
        }
    }
}
