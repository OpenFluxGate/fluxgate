package org.fluxgate.core.ratelimiter.impl.bucket4j;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;

/**
 * In-memory Bucket4j-based implementation of {@link RateLimiter}.
 *
 * <p>This implementation is suitable for single-node or local testing. For Redis/Hazelcast
 * distributed backends, create another implementation that delegates to the appropriate Bucket4j
 * extension.
 */
public class Bucket4jRateLimiter implements RateLimiter {

  /** Bucket cache keyed by (ruleSetId + logical key). */
  private final ConcurrentMap<BucketKey, Bucket> buckets = new ConcurrentHashMap<>();

  @Override
  public RateLimitResult tryConsume(
      RequestContext context, RateLimitRuleSet ruleSet, long permits) {

    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(ruleSet, "ruleSet must not be null");

    if (permits <= 0) {
      throw new IllegalArgumentException("permits must be > 0");
    }

    List<RateLimitRule> rules = ruleSet.getRules();
    if (rules == null || rules.isEmpty()) {
      throw new IllegalArgumentException("ruleSet must contain at least one RateLimitRule");
    }

    // ========================================================================
    // Multi-Rule Rate Limiting with Per-Rule Key Resolution
    // ========================================================================
    // Each rule can have a different LimitScope (PER_IP, PER_USER, PER_API_KEY, etc.)
    // The KeyResolver resolves the appropriate key based on the rule's scope.
    // ========================================================================

    RateLimitRule matchedRule = null;
    RateLimitKey matchedKey = null;
    long minRemainingTokens = Long.MAX_VALUE;
    long maxNanosToWait = 0;
    boolean anyRejected = false;

    for (RateLimitRule rule : rules) {
      if (!rule.isEnabled()) {
        continue;
      }

      // Resolve the rate limit key for THIS rule based on its LimitScope
      RateLimitKey logicalKey = ruleSet.getKeyResolver().resolve(context, rule);
      Objects.requireNonNull(
          logicalKey, "resolved RateLimitKey must not be null for rule: " + rule.getId());

      // Create bucket key combining ruleSet, rule, and logical key
      BucketKey bucketKey = new BucketKey(ruleSet.getId() + ":" + rule.getId(), logicalKey);

      // Get or create Bucket for this key
      Bucket bucket = buckets.computeIfAbsent(bucketKey, key -> createBucketForRule(rule));

      // Try consume
      ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(permits);

      minRemainingTokens = Math.min(minRemainingTokens, probe.getRemainingTokens());

      if (!probe.isConsumed()) {
        anyRejected = true;
        maxNanosToWait = Math.max(maxNanosToWait, probe.getNanosToWaitForRefill());
        matchedRule = rule;
        matchedKey = logicalKey;
      }

      // Track the key for logging (use first rule's key if not rejected)
      if (matchedKey == null) {
        matchedKey = logicalKey;
        matchedRule = rule;
      }
    }

    // Build result
    RateLimitResult result;
    if (anyRejected) {
      result = RateLimitResult.rejected(matchedKey, matchedRule, maxNanosToWait);
    } else {
      result = RateLimitResult.allowed(matchedKey, matchedRule, minRemainingTokens, 0L);
    }

    // Metrics hook
    RateLimitMetricsRecorder recorder = ruleSet.getMetricsRecorder();
    if (recorder != null) {
      recorder.record(context, result);
    }

    return result;
  }

  /**
   * Creates a Bucket for a single rule with all its bands.
   *
   * @param rule the rate limit rule
   * @return a new Bucket configured with the rule's bands
   */
  private Bucket createBucketForRule(RateLimitRule rule) {
    io.github.bucket4j.local.LocalBucketBuilder builder = Bucket.builder();

    List<RateLimitBand> bands = rule.getBands();
    if (bands == null || bands.isEmpty()) {
      throw new IllegalArgumentException("rule must contain at least one RateLimitBand");
    }

    for (RateLimitBand band : bands) {
      builder.addLimit(toBandwidth(band));
    }

    return builder.build();
  }

  private Bandwidth toBandwidth(RateLimitBand band) {
    Duration window = band.getWindow();
    long capacity = band.getCapacity();

    // For now we use greedy refill (full amount over the window).
    return Bandwidth.builder().capacity(capacity).refillGreedy(capacity, window).build();
  }

  /** Composite key for bucket cache: (ruleSetId, logical RateLimitKey). */
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
      if (!(o instanceof BucketKey)) return false;
      BucketKey that = (BucketKey) o;
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
      return "BucketKey{" + "ruleSetId='" + ruleSetId + '\'' + ", key=" + key + '}';
    }
  }
}
