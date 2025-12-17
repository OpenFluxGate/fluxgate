package org.fluxgate.spring.reload.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * <p>Example usage:
 *
 * <pre>{@code
 * RuleCache cache = new CaffeineRuleCache(
 *     Duration.ofMinutes(5),  // TTL
 *     1000                    // max size
 * );
 * }</pre>
 */
public class CaffeineRuleCache implements RuleCache {

  private static final Logger log = LoggerFactory.getLogger(CaffeineRuleCache.class);

  private final Cache<String, RateLimitRuleSet> cache;
  private final Duration ttl;
  private final int maxSize;

  /**
   * Creates a new Caffeine-based rule cache.
   *
   * @param ttl time-to-live for cached entries
   * @param maxSize maximum number of entries to cache
   */
  public CaffeineRuleCache(Duration ttl, int maxSize) {
    this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
    this.maxSize = maxSize;

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

    log.info("CaffeineRuleCache initialized with ttl={}, maxSize={}", ttl, maxSize);
  }

  @Override
  public Optional<RateLimitRuleSet> get(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    return Optional.ofNullable(cache.getIfPresent(ruleSetId));
  }

  @Override
  public void put(String ruleSetId, RateLimitRuleSet ruleSet) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    Objects.requireNonNull(ruleSet, "ruleSet must not be null");
    cache.put(ruleSetId, ruleSet);
    log.trace("Cached rule set: {}", ruleSetId);
  }

  @Override
  public void invalidate(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    cache.invalidate(ruleSetId);
    log.debug("Invalidated rule set from cache: {}", ruleSetId);
  }

  @Override
  public void invalidateAll() {
    cache.invalidateAll();
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
   * Performs cache maintenance (cleanup expired entries).
   *
   * <p>This is typically called automatically by Caffeine, but can be invoked manually if needed.
   */
  public void cleanUp() {
    cache.cleanUp();
  }
}
