package org.fluxgate.core.reload;

import java.util.Objects;
import java.util.Optional;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A decorator that adds caching capabilities to any {@link RateLimitRuleSetProvider}.
 *
 * <p>This provider wraps a delegate provider and caches rule sets locally. It also implements
 * {@link RuleReloadListener} to invalidate cache entries when reload events are received.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * RateLimitRuleSetProvider mongoProvider = new MongoRuleSetProvider(...);
 * RuleCache cache = new CaffeineRuleCache(...);
 * RuleReloadStrategy reloadStrategy = new PollingReloadStrategy(...);
 *
 * CachingRuleSetProvider cachingProvider =
 *     new CachingRuleSetProvider(mongoProvider, cache);
 *
 * // Register as reload listener
 * reloadStrategy.addListener(cachingProvider);
 * }</pre>
 */
public class CachingRuleSetProvider implements RateLimitRuleSetProvider, RuleReloadListener {

  private static final Logger log = LoggerFactory.getLogger(CachingRuleSetProvider.class);

  private final RateLimitRuleSetProvider delegate;
  private final RuleCache cache;

  /**
   * Creates a new caching provider.
   *
   * @param delegate the underlying provider to delegate cache misses to
   * @param cache the cache to store rule sets
   */
  public CachingRuleSetProvider(RateLimitRuleSetProvider delegate, RuleCache cache) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    this.cache = Objects.requireNonNull(cache, "cache must not be null");
  }

  @Override
  public Optional<RateLimitRuleSet> findById(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");

    // Try cache first
    Optional<RateLimitRuleSet> cached = cache.get(ruleSetId);
    if (cached.isPresent()) {
      log.trace("Cache hit for ruleSetId: {}", ruleSetId);
      return cached;
    }

    // Cache miss - load from delegate
    log.debug("Cache miss for ruleSetId: {}, loading from delegate", ruleSetId);
    Optional<RateLimitRuleSet> loaded = delegate.findById(ruleSetId);

    // Cache if found
    loaded.ifPresent(
        ruleSet -> {
          cache.put(ruleSetId, ruleSet);
          log.debug("Cached ruleSetId: {}", ruleSetId);
        });

    return loaded;
  }

  @Override
  public void onReload(RuleReloadEvent event) {
    if (event.isFullReload()) {
      log.info("Full reload triggered from {}, invalidating all cached rules", event.getSource());
      cache.invalidateAll();
    } else {
      log.info(
          "Reload triggered for ruleSetId: {} from {}", event.getRuleSetId(), event.getSource());
      cache.invalidate(event.getRuleSetId());
    }
  }

  /**
   * Returns the underlying delegate provider.
   *
   * @return the delegate provider
   */
  public RateLimitRuleSetProvider getDelegate() {
    return delegate;
  }

  /**
   * Returns the cache being used.
   *
   * @return the rule cache
   */
  public RuleCache getCache() {
    return cache;
  }
}
