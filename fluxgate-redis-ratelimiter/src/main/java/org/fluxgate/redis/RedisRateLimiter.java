package org.fluxgate.redis;

import java.util.List;
import java.util.Objects;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.redis.store.BucketState;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production-ready Redis-backed distributed rate limiter implementation.
 *
 * <p>KEY IMPROVEMENTS: 1. Uses Redis TIME (not System.nanoTime()) - eliminates clock drift across
 * distributed nodes 2. Integer arithmetic only (no floating point) - eliminates precision loss 3.
 * Doesn't update state on rejection - prevents unfair rate limiting 4. Returns reset time - for
 * HTTP X-RateLimit-Reset header 5. TTL safety margin + max cap - prevents premature expiration and
 * runaway TTLs
 *
 * <p>This implementation: - Stores token buckets in Redis - Uses production Lua scripts for atomic
 * refill + consume operations - Supports multi-band rate limiting (e.g., 10/sec AND 100/min AND
 * 1000/hour) - Automatically expires buckets using Redis TTL
 *
 * <p>Thread-safe and suitable for distributed environments with multiple API gateway nodes.
 *
 * @see RedisTokenBucketStore
 * @see org.fluxgate.redis.script.LuaScriptLoader
 */
public class RedisRateLimiter implements RateLimiter {

  private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

  private static final String KEY_PREFIX = "fluxgate";

  private final RedisTokenBucketStore tokenBucketStore;

  /**
   * Create a new RedisRateLimiter with the given production token bucket store.
   *
   * @param tokenBucketStore Redis-backed token bucket store
   */
  public RedisRateLimiter(RedisTokenBucketStore tokenBucketStore) {
    this.tokenBucketStore =
        Objects.requireNonNull(tokenBucketStore, "tokenBucketStore must not be null");
  }

  @Override
  public RateLimitResult tryConsume(
      RequestContext context, RateLimitRuleSet ruleSet, long permits) {

    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(ruleSet, "ruleSet must not be null");

    if (permits <= 0) {
      throw new IllegalArgumentException("permits must be > 0");
    }

    // Get rules from the rule set
    List<RateLimitRule> rules = ruleSet.getRules();
    if (rules == null || rules.isEmpty()) {
      log.warn("No rules in ruleSet {}, allowing request by default", ruleSet.getId());
      return RateLimitResult.allowedWithoutRule();
    }

    // ========================================================================
    // Multi-Rule Rate Limiting with Per-Rule Key Resolution (Fail-Fast)
    // ========================================================================
    // Each rule can have a different LimitScope (PER_IP, PER_USER, PER_API_KEY, etc.)
    // The KeyResolver resolves the appropriate key based on the rule's scope.
    //
    // Example:
    // - Rule 1 (PER_IP): key = "192.168.1.100"
    // - Rule 2 (PER_USER): key = "user-123"
    //
    // IMPORTANT: Fail-Fast Strategy
    // - If ANY rule/band rejects, we immediately return (early return)
    // - This prevents unfair token consumption in subsequent rules
    // - Without early return: Rule 1 rejects, but Rule 2/3 still consume tokens → unfair
    // - With early return: Rule 1 rejects, Rule 2/3 are not checked → fair
    // ========================================================================

    RateLimitRule matchedRule = null;
    RateLimitKey matchedKey = null;
    long minRemainingTokens = Long.MAX_VALUE;

    for (RateLimitRule rule : rules) {
      if (!rule.isEnabled()) {
        continue;
      }

      List<RateLimitBand> bands = rule.getBands();
      if (bands == null || bands.isEmpty()) {
        continue;
      }

      // Resolve the rate limit key for THIS rule based on its LimitScope
      RateLimitKey logicalKey = ruleSet.getKeyResolver().resolve(context, rule);
      Objects.requireNonNull(
          logicalKey, "resolved RateLimitKey must not be null for rule: " + rule.getId());

      // Check each band in the rule
      for (RateLimitBand band : bands) {
        String bucketKey = buildBucketKey(ruleSet.getId(), rule.getId(), logicalKey, band);

        // Try to consume permits from this band's bucket
        BucketState state = tokenBucketStore.tryConsume(bucketKey, band, permits);

        // Track minimum remaining tokens across all bands
        minRemainingTokens = Math.min(minRemainingTokens, state.remainingTokens());

        if (!state.consumed()) {
          // ================================================================
          // EARLY RETURN: Fail-fast on first rejection
          // ================================================================
          // - Prevents subsequent rules from consuming tokens unfairly
          // - Lua script already ensures no state change on rejection
          // - Only the rejecting rule is recorded in metrics/logs
          // ================================================================
          log.debug(
              "Rate limit REJECTED for key {}: rule {}, band {}, wait {} ns",
              logicalKey.value(),
              rule.getId(),
              band.getLabel(),
              state.nanosToWaitForRefill());

          RateLimitResult rejectedResult =
              RateLimitResult.rejected(logicalKey, rule, state.nanosToWaitForRefill());

          // Record metrics for rejection
          RateLimitMetricsRecorder recorder = ruleSet.getMetricsRecorder();
          if (recorder != null) {
            recorder.record(context, rejectedResult);
          }

          return rejectedResult;
        }
      }

      // Track for allowed result (use first rule's key)
      if (matchedKey == null) {
        matchedKey = logicalKey;
        matchedRule = rule;
      }
    }

    // All rules/bands allowed - request is allowed
    if (matchedRule == null) {
      matchedRule = rules.get(0);
    }

    RateLimitResult result =
        RateLimitResult.allowed(
            matchedKey, matchedRule, minRemainingTokens, 0L // No wait time since allowed
            );

    log.debug(
        "Rate limit ALLOWED for key {}: matched rule {}, {} tokens remaining",
        matchedKey != null ? matchedKey.value() : "unknown",
        matchedRule.getId(),
        minRemainingTokens);

    // Record metrics if configured
    RateLimitMetricsRecorder recorder = ruleSet.getMetricsRecorder();
    if (recorder != null) {
      recorder.record(context, result);
    }

    return result;
  }

  /**
   * Build the Redis key for a token bucket.
   *
   * <p>Format: fluxgate:{ruleSetId}:{ruleId}:{keyValue}:{bandLabel}
   *
   * <p>Example: fluxgate:api-limits:per-ip-rule:192.168.1.100:100-per-minute
   *
   * @param ruleSetId ID of the rule set
   * @param ruleId ID of the rule
   * @param key Rate limit key (e.g., IP address, API key)
   * @param band Rate limit band
   * @return Redis key string
   */
  private String buildBucketKey(
      String ruleSetId, String ruleId, RateLimitKey key, RateLimitBand band) {
    String bandLabel = band.getLabel() != null ? band.getLabel() : "default";
    return String.format("%s:%s:%s:%s:%s", KEY_PREFIX, ruleSetId, ruleId, key.value(), bandLabel);
  }

  /** Close the underlying token bucket store. */
  public void close() {
    if (tokenBucketStore != null) {
      tokenBucketStore.close();
    }
  }
}
