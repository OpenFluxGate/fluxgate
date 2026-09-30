package org.fluxgate.core.ratelimiter.impl.bucket4j;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.EstimationProbe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.exception.InvalidRuleConfigException;
import org.fluxgate.core.exception.MissingRateLimitKeyException;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory Bucket4j-based implementation of {@link RateLimiter}.
 *
 * <p>This implementation is suitable for single-node or local testing. For Redis/Hazelcast
 * distributed backends, create another implementation that delegates to the appropriate Bucket4j
 * extension.
 *
 * <p>One {@link Bucket} is kept per (rule set, rule, resolved key, band), mirroring the bucket
 * layout of the distributed implementations. Buckets live in a bounded Caffeine cache: at most
 * {@code maximumSize} entries, each evicted after {@code expireAfterAccess} of inactivity. A fixed
 * expiry is used rather than one derived from each rule's window because the cache is shared by all
 * rule sets; pick a value comfortably above your longest window if you rate limit over long
 * windows, since evicting a bucket resets its tokens.
 *
 * <p><strong>Eviction resets a limit.</strong> An evicted bucket is recreated full on its next
 * request, so a caller able to mint identities (see the {@code SECURITY.md} note on identity
 * headers) can push a victim's bucket out of the cache and start over. {@link #getEvictionCount()}
 * counts every eviction so an operator can alert on an eviction rate that has no business being
 * there; wire it as the {@code fluxgate.limiter.bucket_evictions} meter. This limiter is for a
 * single instance: do not combine it with a forgeable identity scope.
 *
 * <p>Consumption across rules and bands is two-phase: every band is first checked without
 * consuming, and tokens are only taken when all of them can serve the request. A rejected request
 * therefore does not drain the buckets that would have allowed it. The phases are not one atomic
 * operation, so under concurrency a bucket can still be drained between the two phases; that case
 * is detected and the tokens taken so far are handed back.
 */
public class Bucket4jRateLimiter implements RateLimiter {

  private static final Logger log = LoggerFactory.getLogger(Bucket4jRateLimiter.class);

  /** Default upper bound on the number of cached buckets. */
  public static final long DEFAULT_MAXIMUM_SIZE = 100_000L;

  /** Default idle time after which a bucket is evicted. */
  public static final Duration DEFAULT_EXPIRE_AFTER_ACCESS = Duration.ofHours(1);

  private static final long NANOS_PER_MILLI = 1_000_000L;

  /** Bounded bucket cache keyed by (ruleSetId, ruleId, logical key, band). */
  private final Cache<BucketKey, Bucket> buckets;

  private final long maximumSize;

  /** Number of buckets the cache has dropped, whether by size pressure or by expiry. */
  private final AtomicLong evictionCount = new AtomicLong();

  /** Creates a rate limiter with the default cache bounds. */
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
   * Creates a rate limiter with custom cache bounds.
   *
   * @param maximumSize the maximum number of cached buckets (must be positive)
   * @param expireAfterAccess the idle time after which a bucket is evicted (must be positive)
   */
  public Bucket4jRateLimiter(long maximumSize, Duration expireAfterAccess) {
    if (maximumSize <= 0) {
      throw new IllegalArgumentException("maximumSize must be > 0");
    }
    Objects.requireNonNull(expireAfterAccess, "expireAfterAccess must not be null");
    if (expireAfterAccess.isZero() || expireAfterAccess.isNegative()) {
      throw new IllegalArgumentException("expireAfterAccess must be > 0");
    }

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

    List<Candidate> candidates;
    try {
      candidates = collectCandidates(context, ruleSet, rules, permits);
    } catch (MissingRateLimitKeyException e) {
      RateLimitRule missingRule =
          rules.stream()
              .filter(r -> r.getId() != null && r.getId().equals(e.getRuleId()))
              .findFirst()
              .orElse(null);
      return record(context, ruleSet, missingKeyResult(e, missingRule));
    }

    if (candidates.isEmpty()) {
      // Every rule is disabled: nothing to enforce.
      return record(context, ruleSet, RateLimitResult.allowedWithoutRule());
    }

    return record(context, ruleSet, consumeTwoPhase(candidates, permits));
  }

  /**
   * Resolves keys and buckets for all enabled rules without touching any tokens.
   *
   * @throws MissingRateLimitKeyException if the key resolver refuses to resolve a key
   * @throws InvalidRuleConfigException if a band can never serve the requested permits
   */
  private List<Candidate> collectCandidates(
      RequestContext context, RateLimitRuleSet ruleSet, List<RateLimitRule> rules, long permits) {

    List<Candidate> candidates = new ArrayList<>();

    for (RateLimitRule rule : rules) {
      if (!rule.isEnabled()) {
        continue;
      }

      // Resolve the rate limit key for THIS rule based on its LimitScope
      RateLimitKey logicalKey = ruleSet.getKeyResolver().resolve(context, rule);
      Objects.requireNonNull(
          logicalKey, "resolved RateLimitKey must not be null for rule: " + rule.getId());

      List<RateLimitBand> bands = rule.getBands();
      if (bands == null || bands.isEmpty()) {
        throw new InvalidRuleConfigException(
            "rule must contain at least one RateLimitBand", rule.getId());
      }

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

        BucketKey bucketKey =
            new BucketKey(ruleSet.getId(), rule.getId(), logicalKey, band.getKeyLabel());
        Bucket bucket = buckets.get(bucketKey, key -> createBucketForBand(band));
        candidates.add(new Candidate(rule, logicalKey, band, bucket));
      }
    }

    return candidates;
  }

  /**
   * Checks every band first and only then consumes, so that a request rejected by one band leaves
   * the other buckets untouched.
   */
  private RateLimitResult consumeTwoPhase(List<Candidate> candidates, long permits) {
    // Phase 1: can every band serve this request?
    for (Candidate candidate : candidates) {
      EstimationProbe probe = candidate.bucket.estimateAbilityToConsume(permits);
      if (!probe.canBeConsumed()) {
        return rejectedResult(
            candidate, probe.getRemainingTokens(), probe.getNanosToWaitForRefill());
      }
    }

    // Phase 2: take the tokens. Nothing is expected to fail here; a concurrent consumer that
    // drained a bucket in between is compensated by refunding what this request already took.
    List<Candidate> consumed = new ArrayList<>(candidates.size());
    for (Candidate candidate : candidates) {
      ConsumptionProbe probe = candidate.bucket.tryConsumeAndReturnRemaining(permits);
      if (!probe.isConsumed()) {
        refund(consumed, permits);
        log.debug(
            "Band '{}' of rule '{}' was drained concurrently; refunded {} permits to {} band(s)",
            candidate.band.getKeyLabel(),
            candidate.rule.getId(),
            permits,
            consumed.size());
        return rejectedResult(
            candidate, probe.getRemainingTokens(), probe.getNanosToWaitForRefill());
      }
      candidate.remainingTokens = probe.getRemainingTokens();
      consumed.add(candidate);
    }

    return allowedResult(mostRestrictive(consumed));
  }

  private void refund(List<Candidate> consumed, long permits) {
    for (Candidate candidate : consumed) {
      candidate.bucket.addTokens(permits);
    }
  }

  /** Returns the candidate with the fewest tokens left, which is the one worth reporting. */
  private Candidate mostRestrictive(List<Candidate> consumed) {
    Candidate binding = consumed.get(0);
    for (Candidate candidate : consumed) {
      if (candidate.remainingTokens < binding.remainingTokens) {
        binding = candidate;
      }
    }
    return binding;
  }

  private RateLimitResult allowedResult(Candidate binding) {
    return RateLimitResult.builder(binding.key)
        .allowed(true)
        .matchedRule(binding.rule)
        .remainingTokens(binding.remainingTokens)
        .nanosToWaitForRefill(0L)
        .limit(binding.band.getCapacity())
        .resetTimeMillis(System.currentTimeMillis() + millisUntilFull(binding))
        .policy(binding.rule.getOnLimitExceedPolicy())
        .bandLabel(binding.band.getKeyLabel())
        .build();
  }

  private RateLimitResult rejectedResult(
      Candidate binding, long remainingTokens, long nanosToWaitForRefill) {
    return RateLimitResult.builder(binding.key)
        .allowed(false)
        .matchedRule(binding.rule)
        .remainingTokens(remainingTokens)
        .nanosToWaitForRefill(nanosToWaitForRefill)
        .limit(binding.band.getCapacity())
        // resetTimeMillis = epoch millis when the bucket is FULL again, not just when it has
        // enough tokens to serve this request. Mirrors the allow-path semantics and the Lua script.
        .resetTimeMillis(
            System.currentTimeMillis() + millisUntilFull(binding.band, remainingTokens))
        .policy(binding.rule.getOnLimitExceedPolicy())
        .bandLabel(binding.band.getKeyLabel())
        .build();
  }

  private RateLimitResult missingKeyResult(MissingRateLimitKeyException e, RateLimitRule rule) {
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
   * Milliseconds until the binding band's bucket holds its full capacity again.
   *
   * <p>Refill is greedy and linear, so the remaining deficit maps directly onto a fraction of the
   * window.
   */
  private long millisUntilFull(Candidate binding) {
    return millisUntilFull(binding.band, binding.remainingTokens);
  }

  /**
   * Milliseconds until {@code band} is full given that it currently holds {@code currentTokens}.
   *
   * <p>Used on the reject path where the Candidate's {@code remainingTokens} field has not yet been
   * set (it is populated only during phase-2 consumption).
   */
  private long millisUntilFull(RateLimitBand band, long currentTokens) {
    long capacity = band.getCapacity();
    long deficit = Math.max(0L, capacity - currentTokens);
    if (deficit == 0L) {
      return 0L;
    }
    long windowNanos = band.getWindow().toNanos();
    return toMillisRoundedUp((long) ((double) deficit / (double) capacity * (double) windowNanos));
  }

  private static long toMillisRoundedUp(long nanos) {
    if (nanos <= 0L) {
      return 0L;
    }
    return (nanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI;
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

  /**
   * Creates a Bucket for a single band of a rule.
   *
   * @param band the rate limit band
   * @return a new Bucket configured with that band's bandwidth
   */
  private Bucket createBucketForBand(RateLimitBand band) {
    return Bucket.builder().addLimit(toBandwidth(band)).build();
  }

  private Bandwidth toBandwidth(RateLimitBand band) {
    Duration window = band.getWindow();
    long capacity = band.getCapacity();

    // For now we use greedy refill (full amount over the window).
    return Bandwidth.builder().capacity(capacity).refillGreedy(capacity, window).build();
  }

  /** One (rule, key, band) triple in flight for a single request. */
  private static final class Candidate {
    private final RateLimitRule rule;
    private final RateLimitKey key;
    private final RateLimitBand band;
    private final Bucket bucket;
    private long remainingTokens = -1L;

    private Candidate(RateLimitRule rule, RateLimitKey key, RateLimitBand band, Bucket bucket) {
      this.rule = rule;
      this.key = key;
      this.band = band;
      this.bucket = bucket;
    }
  }

  /** Composite key for the bucket cache: (ruleSetId, ruleId, logical RateLimitKey, band). */
  private static final class BucketKey {
    private final String ruleSetId;
    private final String ruleId;
    private final RateLimitKey key;
    private final String bandLabel;

    private BucketKey(String ruleSetId, String ruleId, RateLimitKey key, String bandLabel) {
      this.ruleSetId = Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
      this.ruleId = Objects.requireNonNull(ruleId, "ruleId must not be null");
      this.key = Objects.requireNonNull(key, "key must not be null");
      this.bandLabel = Objects.requireNonNull(bandLabel, "bandLabel must not be null");
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof BucketKey)) return false;
      BucketKey that = (BucketKey) o;
      return ruleSetId.equals(that.ruleSetId)
          && ruleId.equals(that.ruleId)
          && key.equals(that.key)
          && bandLabel.equals(that.bandLabel);
    }

    @Override
    public int hashCode() {
      return Objects.hash(ruleSetId, ruleId, key, bandLabel);
    }

    @Override
    public String toString() {
      return "BucketKey{"
          + "ruleSetId='"
          + ruleSetId
          + '\''
          + ", ruleId='"
          + ruleId
          + '\''
          + ", key="
          + key
          + ", bandLabel='"
          + bandLabel
          + '\''
          + '}';
    }
  }
}
