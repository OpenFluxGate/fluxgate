package org.fluxgate.core.ratelimiter.impl.bucket4j;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.local.LocalBucketBuilder;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.fluxgate.core.config.RateLimitAlgorithm;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.exception.InvalidRuleConfigException;
import org.fluxgate.core.exception.MissingRateLimitKeyException;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.match.PathPatternMatcher;
import org.fluxgate.core.match.SimpleAntPathMatcher;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory Bucket4j-based implementation of {@link RateLimiter}.
 *
 * <p>This implementation is suitable for single-node or local testing. For distributed rate
 * limiting use the Redis-backed implementation.
 *
 * <p>One {@link Bucket} is kept per (rule set, rule, resolved key), built with Bucket4j's native
 * multi-bandwidth support so that all bands of a rule are evaluated atomically inside a single
 * {@code tryConsumeAndReturnRemaining} call. This eliminates the over-count race that affected the
 * previous per-band approach (where a concurrent consumer could drain a bucket between the estimate
 * and consume phases).
 *
 * <p>Buckets live in a bounded Caffeine cache: at most {@code maximumSize} entries, each evicted
 * after {@code expireAfterAccess} of inactivity. A fixed expiry is used rather than one derived
 * from each rule's window because the cache is shared by all rule sets; pick a value comfortably
 * above your longest window if you rate limit over long windows, since evicting a bucket resets its
 * tokens.
 *
 * <p><strong>Eviction resets a limit.</strong> An evicted bucket is recreated full on its next
 * request, so a caller able to mint identities can push a victim's bucket out of the cache and
 * start over. {@link #getEvictionCount()} counts every eviction; wire it as the {@code
 * fluxgate.limiter.bucket_evictions} meter.
 *
 * <p><strong>Known limitations vs. the Redis implementation:</strong>
 *
 * <ul>
 *   <li><em>Algorithm approximation.</em> {@link RateLimitAlgorithm#SLIDING_WINDOW} and {@link
 *       RateLimitAlgorithm#FIXED_WINDOW} bands are approximated with Bucket4j's interval-refill
 *       bandwidth ({@code refillIntervally}), which refills the full capacity at the end of each
 *       window. This is a coarser approximation than the Redis sliding-window hash or EXPIREAT
 *       counter; in particular, it does not honour calendar alignment ({@link
 *       org.fluxgate.core.config.QuotaPeriod}).
 *   <li><em>Binding-band identification.</em> Bucket4j's {@link ConsumptionProbe} reports the
 *       minimum remaining tokens across all bandwidths but does not identify which bandwidth was
 *       binding. This implementation picks the band with the smallest capacity as the binding band
 *       for {@code limit} and {@code bandLabel} reporting, which is correct for fresh buckets and a
 *       reasonable heuristic for partially consumed ones.
 *   <li><em>No distribution.</em> State is local to this JVM. Use the Redis limiter for multi-node
 *       deployments.
 * </ul>
 */
public class Bucket4jRateLimiter implements RateLimiter {

  private static final Logger log = LoggerFactory.getLogger(Bucket4jRateLimiter.class);

  /** Default upper bound on the number of cached buckets. */
  public static final long DEFAULT_MAXIMUM_SIZE = 100_000L;

  /** Default idle time after which a bucket is evicted. */
  public static final Duration DEFAULT_EXPIRE_AFTER_ACCESS = Duration.ofHours(1);

  private static final long NANOS_PER_MILLI = 1_000_000L;

  /**
   * Bounded bucket cache keyed by (ruleSetId, ruleId, logical key).
   *
   * <p>Each entry holds the multi-bandwidth {@link Bucket} for all bands of one rule and the band
   * list used to map the probe result back to a {@code bandLabel} and {@code limit}.
   */
  private final Cache<BucketKey, BucketEntry> buckets;

  private final long maximumSize;

  /** Number of buckets the cache has dropped, whether by size pressure or by expiry. */
  private final AtomicLong evictionCount = new AtomicLong();

  /** Path pattern matcher used to filter applicable rules. */
  private final PathPatternMatcher pathMatcher;

  /** Creates a rate limiter with the default cache bounds and the default path matcher. */
  public Bucket4jRateLimiter() {
    this(DEFAULT_MAXIMUM_SIZE, DEFAULT_EXPIRE_AFTER_ACCESS);
  }

  /**
   * Creates a rate limiter with a custom bucket limit and the default idle expiry.
   *
   * @param maximumSize the maximum number of cached buckets (must be positive)
   */
  public Bucket4jRateLimiter(long maximumSize) {
    this(maximumSize, DEFAULT_EXPIRE_AFTER_ACCESS);
  }

  /**
   * Creates a rate limiter with custom cache bounds and the default path matcher.
   *
   * @param maximumSize the maximum number of cached buckets (must be positive)
   * @param expireAfterAccess the idle time after which a bucket is evicted (must be positive)
   */
  public Bucket4jRateLimiter(long maximumSize, Duration expireAfterAccess) {
    this(maximumSize, expireAfterAccess, SimpleAntPathMatcher.INSTANCE);
  }

  /**
   * Creates a rate limiter with custom cache bounds and a custom path matcher.
   *
   * <p>The matcher is used to evaluate {@link RateLimitRuleSet#getMatchingRules} on every call, so
   * only rules whose {@link org.fluxgate.core.config.RuleMatcher} matches the incoming request are
   * enforced.
   *
   * @param maximumSize the maximum number of cached buckets (must be positive)
   * @param expireAfterAccess the idle time after which a bucket is evicted (must be positive)
   * @param pathMatcher path pattern matcher for rule filtering
   */
  public Bucket4jRateLimiter(
      long maximumSize, Duration expireAfterAccess, PathPatternMatcher pathMatcher) {
    if (maximumSize <= 0) {
      throw new IllegalArgumentException("maximumSize must be > 0");
    }
    Objects.requireNonNull(expireAfterAccess, "expireAfterAccess must not be null");
    if (expireAfterAccess.isZero() || expireAfterAccess.isNegative()) {
      throw new IllegalArgumentException("expireAfterAccess must be > 0");
    }
    this.pathMatcher = Objects.requireNonNull(pathMatcher, "pathMatcher must not be null");
    this.maximumSize = maximumSize;
    this.buckets =
        Caffeine.newBuilder()
            .maximumSize(maximumSize)
            .expireAfterAccess(expireAfterAccess)
            // Every eviction hands the affected key a full bucket again, so the eviction rate is a
            // security signal and not just a cache statistic. An explicit removal (a rule change
            // resetting the buckets) is not an eviction and must not show up in the count.
            .removalListener(
                (key, value, cause) -> {
                  if (cause.wasEvicted()) {
                    evictionCount.incrementAndGet();
                  }
                })
            .build();
  }

  /**
   * Returns how many buckets have been evicted from the cache.
   *
   * <p>Monotonically increasing, counting both size-based eviction and idle expiry. An eviction
   * resets the affected key's tokens, so a rate far above the normal churn of your keyspace means
   * either that {@code maximum-size} is too small for the traffic or that someone is cycling
   * identities to get their limit reset.
   *
   * @return the number of evicted buckets
   */
  public long getEvictionCount() {
    return evictionCount.get();
  }

  @Override
  public RateLimitResult tryConsume(
      RequestContext context, RateLimitRuleSet ruleSet, long permits) {
    return tryConsume(context, ruleSet, permits, pathMatcher);
  }

  @Override
  public RateLimitResult tryConsume(
      RequestContext context, RateLimitRuleSet ruleSet, long permits, PathPatternMatcher matcher) {

    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(ruleSet, "ruleSet must not be null");
    Objects.requireNonNull(matcher, "matcher must not be null");

    if (permits <= 0) {
      throw new IllegalArgumentException("permits must be > 0");
    }

    // getMatchingRules returns only enabled rules that match this request's path/method/headers.
    List<RateLimitRule> rules = ruleSet.getMatchingRules(context, matcher);
    if (rules.isEmpty()) {
      log.debug("No matching rules in ruleSet {}, nothing to enforce", ruleSet.getId());
      return record(context, ruleSet, RateLimitResult.allowedWithoutRule());
    }

    // ========================================================================
    // Multi-Rule Rate Limiting: evaluate each matching rule in order.
    // Each rule uses ONE multi-bandwidth Bucket for all its bands, so the
    // multi-band decision is atomic inside Bucket4j (no estimate/consume race).
    // Across rules there is still no atomicity — a rule that already allowed a
    // request keeps its tokens if a later rule rejects. Order rules from most
    // to least likely to reject, or use a single multi-band rule.
    // ========================================================================

    Binding binding = null;

    for (RateLimitRule rule : rules) {
      // rules from getMatchingRules are already enabled and path-matched
      List<RateLimitBand> bands = rule.getBands();
      if (bands == null || bands.isEmpty()) {
        continue;
      }

      // Pre-validate permits vs capacity before touching any bucket.
      for (RateLimitBand band : bands) {
        if (permits > band.getCapacity()) {
          throw new InvalidRuleConfigException(
              "permits ("
                  + permits
                  + ") exceed the capacity of band '"
                  + band.getKeyLabel()
                  + "' ("
                  + band.getCapacity()
                  + ")",
              rule.getId());
        }
      }

      RateLimitKey logicalKey;
      try {
        logicalKey = ruleSet.getKeyResolver().resolve(context, rule);
      } catch (MissingRateLimitKeyException e) {
        return record(context, ruleSet, missingKeyResult(e, rule));
      }
      Objects.requireNonNull(
          logicalKey, "resolved RateLimitKey must not be null for rule: " + rule.getId());

      // One bucket per (ruleSetId, ruleId, key) with all bands as Bucket4j bandwidths.
      // This makes multi-band evaluation atomic: a reject by one bandwidth leaves every
      // other bandwidth untouched, with no refund dance needed.
      final List<RateLimitBand> finalBands = bands;
      BucketKey bucketKey = new BucketKey(ruleSet.getId(), rule.getId(), logicalKey);
      BucketEntry entry = buckets.get(bucketKey, k -> createBucketEntry(finalBands));

      ConsumptionProbe probe = entry.bucket.tryConsumeAndReturnRemaining(permits);

      if (!probe.isConsumed()) {
        RateLimitBand bindingBand = findBindingBand(entry.bands);
        if (log.isDebugEnabled()) {
          log.debug(
              "Rate limit REJECTED for key {}: rule {}, band {}, wait {} ns",
              mask(logicalKey),
              rule.getId(),
              bindingBand != null ? bindingBand.getKeyLabel() : "unknown",
              probe.getNanosToWaitForRefill());
        }
        return record(
            context,
            ruleSet,
            rejectedResult(
                logicalKey,
                rule,
                bindingBand,
                probe.getRemainingTokens(),
                probe.getNanosToWaitForRefill()));
      }

      long remaining = probe.getRemainingTokens();
      if (binding == null || remaining < binding.remainingTokens) {
        RateLimitBand bindingBand = findBindingBand(entry.bands);
        binding = new Binding(logicalKey, rule, bindingBand, remaining);
      }
    }

    if (binding == null) {
      log.debug("No rule with bands in ruleSet {}, nothing to enforce", ruleSet.getId());
      return record(context, ruleSet, RateLimitResult.allowedWithoutRule());
    }

    if (log.isDebugEnabled()) {
      log.debug(
          "Rate limit ALLOWED for key {}: binding rule {}, band {}, {} tokens remaining",
          mask(binding.key),
          binding.rule.getId(),
          binding.band != null ? binding.band.getKeyLabel() : "unknown",
          binding.remainingTokens);
    }

    return record(context, ruleSet, allowedResult(binding));
  }

  /**
   * Creates a {@link BucketEntry} for all bands of a rule.
   *
   * <p>Each band is added as a Bucket4j {@link Bandwidth}. {@link RateLimitAlgorithm#TOKEN_BUCKET}
   * bands use greedy refill (continuous); {@link RateLimitAlgorithm#SLIDING_WINDOW} and {@link
   * RateLimitAlgorithm#FIXED_WINDOW} bands use interval refill (all tokens restored at once at the
   * end of each window), which is an approximation — see the class-level Javadoc for details.
   */
  private static BucketEntry createBucketEntry(List<RateLimitBand> bands) {
    LocalBucketBuilder builder = Bucket.builder();
    for (RateLimitBand band : bands) {
      builder.addLimit(toBandwidth(band));
    }
    return new BucketEntry(builder.build(), bands);
  }

  private static Bandwidth toBandwidth(RateLimitBand band) {
    Duration window = band.getWindow();
    long capacity = band.getCapacity();
    RateLimitAlgorithm alg = band.getAlgorithm();

    if (alg == RateLimitAlgorithm.SLIDING_WINDOW || alg == RateLimitAlgorithm.FIXED_WINDOW) {
      // Approximation: refill all tokens at once at the end of the window, similar to a tumbling
      // counter. QuotaPeriod calendar-alignment is NOT supported by the in-memory limiter.
      return Bandwidth.builder().capacity(capacity).refillIntervally(capacity, window).build();
    }
    // TOKEN_BUCKET (default): greedy continuous refill.
    return Bandwidth.builder().capacity(capacity).refillGreedy(capacity, window).build();
  }

  /**
   * Returns the band with the smallest capacity, which is the most likely binding band in steady
   * state. This is a heuristic: Bucket4j's {@link ConsumptionProbe} reports only the minimum
   * remaining across all bandwidths, not which bandwidth produced it.
   */
  private static RateLimitBand findBindingBand(List<RateLimitBand> bands) {
    if (bands == null || bands.isEmpty()) {
      return null;
    }
    RateLimitBand binding = bands.get(0);
    for (int i = 1; i < bands.size(); i++) {
      RateLimitBand b = bands.get(i);
      if (b.getCapacity() < binding.getCapacity()) {
        binding = b;
      }
    }
    return binding;
  }

  private RateLimitResult allowedResult(Binding binding) {
    long resetMs =
        System.currentTimeMillis() + millisUntilFull(binding.band, binding.remainingTokens);
    return RateLimitResult.builder(binding.key)
        .allowed(true)
        .matchedRule(binding.rule)
        .remainingTokens(binding.remainingTokens)
        .nanosToWaitForRefill(0L)
        .limit(binding.band != null ? binding.band.getCapacity() : -1L)
        .resetTimeMillis(resetMs)
        .policy(binding.rule.getOnLimitExceedPolicy())
        .bandLabel(binding.band != null ? binding.band.getKeyLabel() : null)
        .build();
  }

  private RateLimitResult rejectedResult(
      RateLimitKey key,
      RateLimitRule rule,
      RateLimitBand band,
      long remainingTokens,
      long nanosToWaitForRefill) {

    long resetMs = System.currentTimeMillis() + millisUntilFull(band, remainingTokens);
    return RateLimitResult.builder(key)
        .allowed(false)
        .matchedRule(rule)
        .remainingTokens(remainingTokens)
        .nanosToWaitForRefill(nanosToWaitForRefill)
        .limit(band != null ? band.getCapacity() : -1L)
        .resetTimeMillis(resetMs)
        .policy(rule.getOnLimitExceedPolicy())
        .bandLabel(band != null ? band.getKeyLabel() : null)
        .build();
  }

  private static RateLimitResult missingKeyResult(
      MissingRateLimitKeyException e, RateLimitRule rule) {
    log.debug("Rejecting request because no rate limit key could be resolved: {}", e.getMessage());
    RateLimitResult.Builder builder =
        RateLimitResult.builder(RateLimitKey.of("missing-key:" + e.getRuleId()))
            .allowed(false)
            .remainingTokens(0L)
            .nanosToWaitForRefill(0L)
            .limit(-1L)
            .resetTimeMillis(-1L);
    if (rule != null) {
      builder.matchedRule(rule).policy(rule.getOnLimitExceedPolicy());
    }
    return builder.build();
  }

  /**
   * Milliseconds until the given band's bucket holds its full capacity again.
   *
   * <p>Assumes greedy (linear) refill; returns 0 when the bucket is already full or when {@code
   * band} is {@code null}.
   */
  private static long millisUntilFull(RateLimitBand band, long currentTokens) {
    if (band == null) {
      return 0L;
    }
    long capacity = band.getCapacity();
    long deficit = Math.max(0L, capacity - currentTokens);
    if (deficit == 0L) {
      return 0L;
    }
    long windowNanos = band.getWindow().toNanos();
    long nanos = (long) ((double) deficit / (double) capacity * (double) windowNanos);
    return toMillisRoundedUp(nanos);
  }

  private static long toMillisRoundedUp(long nanos) {
    if (nanos <= 0L) {
      return 0L;
    }
    return (nanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI;
  }

  private static String mask(RateLimitKey key) {
    String value = key.value();
    if (value.length() <= 4) {
      return "***";
    }
    return value.substring(0, 4) + "***";
  }

  private RateLimitResult record(
      RequestContext context, RateLimitRuleSet ruleSet, RateLimitResult result) {
    RateLimitMetricsRecorder recorder = ruleSet.getMetricsRecorder();
    if (recorder != null) {
      recorder.record(context, result);
    }
    return result;
  }

  /**
   * Discards every cached bucket belonging to the given rule set.
   *
   * <p>Used when a rule set is reloaded: the new bands no longer match the buckets built from the
   * previous definition.
   *
   * @param ruleSetId the rule set whose buckets should be dropped
   */
  public void reset(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");

    int removed = 0;
    Iterator<BucketKey> keys = buckets.asMap().keySet().iterator();
    while (keys.hasNext()) {
      if (ruleSetId.equals(keys.next().ruleSetId)) {
        keys.remove();
        removed++;
      }
    }

    log.debug("Discarded {} bucket(s) of rule set '{}'", removed, ruleSetId);
  }

  /** Discards every cached bucket. */
  public void resetAll() {
    buckets.invalidateAll();
    log.debug("Discarded all buckets");
  }

  /**
   * Returns the number of cached buckets after running pending cache maintenance.
   *
   * <p>Maintenance is forced so that the returned value respects the configured maximum size;
   * without it Caffeine may report entries that are already scheduled for eviction.
   *
   * @return the number of cached buckets
   */
  public long size() {
    buckets.cleanUp();
    return buckets.estimatedSize();
  }

  /**
   * Returns the configured upper bound on the number of cached buckets.
   *
   * @return the maximum cache size
   */
  public long getMaximumSize() {
    return maximumSize;
  }

  // ========================================================================
  // Inner classes
  // ========================================================================

  /**
   * Cache entry holding a multi-bandwidth {@link Bucket} and the band list used to map probe
   * results back to a {@code bandLabel} / {@code limit}.
   */
  private static final class BucketEntry {
    final Bucket bucket;
    final List<RateLimitBand> bands;

    BucketEntry(Bucket bucket, List<RateLimitBand> bands) {
      this.bucket = bucket;
      this.bands = bands;
    }
  }

  /** The (rule, key, band, remaining) tuple describing the most-restrictive allowed result. */
  private static final class Binding {
    final RateLimitKey key;
    final RateLimitRule rule;
    final RateLimitBand band;
    final long remainingTokens;

    Binding(RateLimitKey key, RateLimitRule rule, RateLimitBand band, long remainingTokens) {
      this.key = key;
      this.rule = rule;
      this.band = band;
      this.remainingTokens = remainingTokens;
    }
  }

  /**
   * Composite cache key: (ruleSetId, ruleId, logical {@link RateLimitKey}).
   *
   * <p>One bucket covers all bands of a rule for one resolved key, so the band label is no longer
   * part of the key (it was part of the old per-band cache key).
   */
  private static final class BucketKey {
    final String ruleSetId;
    final String ruleId;
    final RateLimitKey key;

    BucketKey(String ruleSetId, String ruleId, RateLimitKey key) {
      this.ruleSetId = Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
      this.ruleId = Objects.requireNonNull(ruleId, "ruleId must not be null");
      this.key = Objects.requireNonNull(key, "key must not be null");
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof BucketKey)) return false;
      BucketKey that = (BucketKey) o;
      return ruleSetId.equals(that.ruleSetId) && ruleId.equals(that.ruleId) && key.equals(that.key);
    }

    @Override
    public int hashCode() {
      return Objects.hash(ruleSetId, ruleId, key);
    }

    @Override
    public String toString() {
      return "BucketKey{ruleSetId='" + ruleSetId + "', ruleId='" + ruleId + "', key=" + key + '}';
    }
  }
}
