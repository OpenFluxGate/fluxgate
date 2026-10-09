package org.fluxgate.core.reload;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;

/**
 * Cache interface for storing and retrieving rate limit rule sets.
 *
 * <p>Implementations should be thread-safe and support concurrent access.
 */
public interface RuleCache {

  /**
   * Retrieves a cached rule set by ID.
   *
   * @param ruleSetId the ID of the rule set to retrieve
   * @return an Optional containing the rule set if cached, or empty if not found
   */
  Optional<RateLimitRuleSet> get(String ruleSetId);

  /**
   * Returns the cached rule set, loading and caching it on a miss.
   *
   * <p>The default implementation is a plain get/load/put sequence, so concurrent misses for the
   * same id can each run the loader. Implementations backed by a cache with atomic loading should
   * override this method to collapse those calls into one and to cache negative results.
   *
   * @param ruleSetId the ID of the rule set to retrieve
   * @param loader loader invoked on a cache miss
   * @return an Optional containing the rule set if cached or loaded, or empty if it does not exist
   */
  default Optional<RateLimitRuleSet> getOrLoad(
      String ruleSetId, Function<String, Optional<RateLimitRuleSet>> loader) {
    Optional<RateLimitRuleSet> cached = get(ruleSetId);
    if (cached.isPresent()) {
      return cached;
    }
    Optional<RateLimitRuleSet> loaded = loader.apply(ruleSetId);
    loaded.ifPresent(ruleSet -> put(ruleSetId, ruleSet));
    return loaded;
  }

  /**
   * Stores a rule set in the cache.
   *
   * @param ruleSetId the ID of the rule set
   * @param ruleSet the rule set to cache
   */
  void put(String ruleSetId, RateLimitRuleSet ruleSet);

  /**
   * Invalidates (removes) a specific rule set from the cache.
   *
   * @param ruleSetId the ID of the rule set to invalidate
   */
  void invalidate(String ruleSetId);

  /**
   * Invalidates all cached rule sets.
   *
   * <p>This should be used sparingly as it may impact performance during cache repopulation.
   */
  void invalidateAll();

  /**
   * Returns the IDs of all currently cached rule sets.
   *
   * <p>This is useful for polling strategies that need to check for changes in known rule sets.
   *
   * @return an unmodifiable set of cached rule set IDs
   */
  Set<String> getCachedRuleSetIds();

  /**
   * Returns the current number of cached entries.
   *
   * @return the cache size
   */
  int size();

  /**
   * Returns cache statistics if available.
   *
   * @return optional cache statistics
   */
  default Optional<CacheStats> getStats() {
    return Optional.empty();
  }

  /** Statistics about cache performance. */
  final class CacheStats {

    private final long hitCount;
    private final long missCount;
    private final long evictionCount;
    private final double hitRate;
    private final long estimatedSize;

    /**
     * Constructs cache statistics from already computed values.
     *
     * @param hitCount the number of cache hits
     * @param missCount the number of cache misses
     * @param evictionCount the number of evicted entries
     * @param hitRate the ratio of hits to total lookups
     * @param estimatedSize the estimated number of cached entries
     */
    public CacheStats(
        long hitCount, long missCount, long evictionCount, double hitRate, long estimatedSize) {
      this.hitCount = hitCount;
      this.missCount = missCount;
      this.evictionCount = evictionCount;
      this.hitRate = hitRate;
      this.estimatedSize = estimatedSize;
    }

    /**
     * Creates cache statistics, deriving the hit rate from the hit and miss counts.
     *
     * @param hitCount the number of cache hits
     * @param missCount the number of cache misses
     * @param evictionCount the number of evicted entries
     * @param estimatedSize the estimated number of cached entries
     * @return the cache statistics
     */
    public static CacheStats of(
        long hitCount, long missCount, long evictionCount, long estimatedSize) {
      double hitRate = hitCount + missCount > 0 ? (double) hitCount / (hitCount + missCount) : 0.0;
      return new CacheStats(hitCount, missCount, evictionCount, hitRate, estimatedSize);
    }

    /**
     * Returns the number of cache hits.
     *
     * @return the hit count
     */
    public long hitCount() {
      return hitCount;
    }

    /**
     * Returns the number of cache misses.
     *
     * @return the miss count
     */
    public long missCount() {
      return missCount;
    }

    /**
     * Returns the number of entries evicted from the cache.
     *
     * @return the eviction count
     */
    public long evictionCount() {
      return evictionCount;
    }

    /**
     * Returns the ratio of hits to total lookups.
     *
     * @return the hit rate between {@code 0.0} and {@code 1.0}
     */
    public double hitRate() {
      return hitRate;
    }

    /**
     * Returns the estimated number of cached entries.
     *
     * @return the estimated cache size
     */
    public long estimatedSize() {
      return estimatedSize;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof CacheStats)) return false;
      CacheStats that = (CacheStats) o;
      return hitCount == that.hitCount
          && missCount == that.missCount
          && evictionCount == that.evictionCount
          && Double.compare(that.hitRate, hitRate) == 0
          && estimatedSize == that.estimatedSize;
    }

    @Override
    public int hashCode() {
      int result = Long.hashCode(hitCount);
      result = 31 * result + Long.hashCode(missCount);
      result = 31 * result + Long.hashCode(evictionCount);
      result = 31 * result + Double.hashCode(hitRate);
      result = 31 * result + Long.hashCode(estimatedSize);
      return result;
    }

    @Override
    public String toString() {
      return "CacheStats{"
          + "hitCount="
          + hitCount
          + ", missCount="
          + missCount
          + ", evictionCount="
          + evictionCount
          + ", hitRate="
          + hitRate
          + ", estimatedSize="
          + estimatedSize
          + '}';
    }
  }
}
