package org.fluxgate.core.reload;

import java.util.Optional;
import java.util.Set;
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

    public CacheStats(
        long hitCount, long missCount, long evictionCount, double hitRate, long estimatedSize) {
      this.hitCount = hitCount;
      this.missCount = missCount;
      this.evictionCount = evictionCount;
      this.hitRate = hitRate;
      this.estimatedSize = estimatedSize;
    }

    public static CacheStats of(
        long hitCount, long missCount, long evictionCount, long estimatedSize) {
      double hitRate = hitCount + missCount > 0 ? (double) hitCount / (hitCount + missCount) : 0.0;
      return new CacheStats(hitCount, missCount, evictionCount, hitRate, estimatedSize);
    }

    public long hitCount() {
      return hitCount;
    }

    public long missCount() {
      return missCount;
    }

    public long evictionCount() {
      return evictionCount;
    }

    public double hitRate() {
      return hitRate;
    }

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
