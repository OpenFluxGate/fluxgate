package org.fluxgate.spring.reload.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.reload.RuleCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caffeine-based implementation of {@link RuleCache}.
 *
 * <p>Provides high-performance, thread-safe local caching of rate limit rule sets with configurable
 * TTL and maximum size.
 *
 * <p>Two properties make it safe under load:
 *
 * <ul>
 *   <li><b>Atomic loading</b> - {@link #getOrLoad(String, Function)} uses Caffeine's {@code
 *       get(key, loader)}, so a cache miss runs the loader exactly once per key even when thousands
 *       of requests miss at the same moment (for example right after a full reload). The plain
 *       get/load/put sequence used before let every in-flight request hit MongoDB.
 *   <li><b>Negative caching</b> - a rule set id that does not exist is remembered for a short TTL,
 *       so one mistyped id can no longer turn into a rule store query on every single request.
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * RuleCache cache = new CaffeineRuleCache(
 *     Duration.ofMinutes(5),  // TTL
 *     1000,                   // max size
 *     Duration.ofSeconds(5)   // negative TTL
 * );
 * }</pre>
 */
public class CaffeineRuleCache implements RuleCache {

  private static final Logger log = LoggerFactory.getLogger(CaffeineRuleCache.class);

  /** Negative TTL applied when none is configured explicitly. */
  public static final Duration DEFAULT_NEGATIVE_TTL = Duration.ofSeconds(5);

  /**
   * Monotonically increasing generation counter. Incremented whenever a cache entry or the entire
   * cache is invalidated. {@link #getOrLoad} snapshots the generation before calling the loader and
   * checks it afterwards; if the generation changed while the loader ran (because an invalidation
   * raced in), the miss-cache entry is not recorded — the next request should see the fresh rule.
   */
  private final AtomicLong generation = new AtomicLong();

  private final Cache<String, RateLimitRuleSet> cache;
  private final Cache<String, Boolean> missCache;
  private final Duration ttl;
  private final int maxSize;
  private final Duration negativeTtl;

  /**
   * Creates a new Caffeine-based rule cache with the default negative TTL.
   *
   * @param ttl time-to-live for cached entries
   * @param maxSize maximum number of entries to cache
   */
  public CaffeineRuleCache(Duration ttl, int maxSize) {
    this(ttl, maxSize, DEFAULT_NEGATIVE_TTL);
  }

  /**
   * Creates a new Caffeine-based rule cache.
   *
   * @param ttl time-to-live for cached entries
   * @param maxSize maximum number of entries to cache
   * @param negativeTtl time-to-live for "not found" results; zero or negative disables negative
   *     caching
   */
  public CaffeineRuleCache(Duration ttl, int maxSize, Duration negativeTtl) {
    this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
    this.maxSize = maxSize;
    this.negativeTtl = negativeTtl != null ? negativeTtl : Duration.ZERO;

    this.cache =
        Caffeine.newBuilder()
            .expireAfterWrite(ttl)
            .maximumSize(maxSize)
            .recordStats()
            .removalListener(
                (key, value, cause) -> {
                  if (cause.wasEvicted()) {
                    log.debug("Rule set evicted from cache: {} (cause: {})", key, cause);
                  }
                })
            .build();

    boolean negativeCachingEnabled = !this.negativeTtl.isZero() && !this.negativeTtl.isNegative();
    this.missCache =
        negativeCachingEnabled
            ? Caffeine.newBuilder()
                .expireAfterWrite(this.negativeTtl)
                .maximumSize(Math.max(maxSize, 1))
                .build()
            : null;

    log.info(
        "CaffeineRuleCache initialized with ttl={}, maxSize={}, negativeTtl={}",
        ttl,
        maxSize,
        negativeCachingEnabled ? this.negativeTtl : "disabled");
  }

  @Override
  public Optional<RateLimitRuleSet> get(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    return Optional.ofNullable(cache.getIfPresent(ruleSetId));
  }

  @Override
  public Optional<RateLimitRuleSet> getOrLoad(
      String ruleSetId, Function<String, Optional<RateLimitRuleSet>> loader) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    Objects.requireNonNull(loader, "loader must not be null");

    RateLimitRuleSet cached = cache.getIfPresent(ruleSetId);
    if (cached != null) {
      return Optional.of(cached);
    }

    if (missCache != null && missCache.getIfPresent(ruleSetId) != null) {
      log.trace("Negative cache hit for rule set: {}", ruleSetId);
      return Optional.empty();
    }

    // Snapshot the generation before calling the loader.  If an invalidation races in while the
    // loader runs, the generation will have changed, and the negative entry must not be recorded —
    // the next request will then re-run the loader against the freshly-loaded (or absent) rule.
    long genBefore = generation.get();

    // Caffeine holds the per-key lock for the duration of the mapping function, so concurrent
    // misses for the same id collapse into a single load. Returning null records no mapping.
    RateLimitRuleSet loaded = cache.get(ruleSetId, key -> loader.apply(key).orElse(null));

    if (loaded == null) {
      if (missCache != null && generation.get() == genBefore) {
        missCache.put(ruleSetId, Boolean.TRUE);
        log.debug("Rule set not found, caching the miss for {}: {}", negativeTtl, ruleSetId);
      }
      return Optional.empty();
    }
    return Optional.of(loaded);
  }

  @Override
  public void put(String ruleSetId, RateLimitRuleSet ruleSet) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    Objects.requireNonNull(ruleSet, "ruleSet must not be null");
    cache.put(ruleSetId, ruleSet);
    invalidateMiss(ruleSetId);
    log.trace("Cached rule set: {}", ruleSetId);
  }

  @Override
  public void invalidate(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    generation.incrementAndGet();
    cache.invalidate(ruleSetId);
    invalidateMiss(ruleSetId);
    log.debug("Invalidated rule set from cache: {}", ruleSetId);
  }

  @Override
  public void invalidateAll() {
    generation.incrementAndGet();
    cache.invalidateAll();
    if (missCache != null) {
      missCache.invalidateAll();
    }
    log.info("Invalidated all cached rule sets");
  }

  @Override
  public Set<String> getCachedRuleSetIds() {
    return new HashSet<>(cache.asMap().keySet());
  }

  @Override
  public int size() {
    return (int) cache.estimatedSize();
  }

  @Override
  public Optional<CacheStats> getStats() {
    com.github.benmanes.caffeine.cache.stats.CacheStats stats = cache.stats();
    return Optional.of(
        CacheStats.of(
            stats.hitCount(), stats.missCount(), stats.evictionCount(), cache.estimatedSize()));
  }

  /**
   * Returns the configured TTL.
   *
   * @return the cache TTL
   */
  public Duration getTtl() {
    return ttl;
  }

  /**
   * Returns the configured maximum size.
   *
   * @return the maximum cache size
   */
  public int getMaxSize() {
    return maxSize;
  }

  /**
   * Returns the configured negative TTL.
   *
   * @return the negative TTL; {@link Duration#ZERO} when negative caching is disabled
   */
  public Duration getNegativeTtl() {
    return missCache != null ? negativeTtl : Duration.ZERO;
  }

  /**
   * Returns the current number of cached "not found" results.
   *
   * @return the negative cache size, always {@code 0} when negative caching is disabled
   */
  public int negativeSize() {
    return missCache != null ? (int) missCache.estimatedSize() : 0;
  }

  /**
   * Performs cache maintenance (cleanup expired entries).
   *
   * <p>This is typically called automatically by Caffeine, but can be invoked manually if needed.
   */
  public void cleanUp() {
    cache.cleanUp();
    if (missCache != null) {
      missCache.cleanUp();
    }
  }

  /** Drops a remembered miss so a freshly created rule set is visible immediately. */
  private void invalidateMiss(String ruleSetId) {
    if (missCache != null) {
      missCache.invalidate(ruleSetId);
    }
  }
}
