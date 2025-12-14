package org.fluxgate.core.reload;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CachingRuleSetProviderTest {

  private TestRuleSetProvider delegate;
  private TestRuleCache cache;
  private CachingRuleSetProvider cachingProvider;

  @BeforeEach
  void setUp() {
    delegate = new TestRuleSetProvider();
    cache = new TestRuleCache();
    cachingProvider = new CachingRuleSetProvider(delegate, cache);
  }

  @Test
  void shouldReturnCachedRuleSetOnHit() {
    RateLimitRuleSet ruleSet = createTestRuleSet("test-rule");
    cache.put("test-rule", ruleSet);

    Optional<RateLimitRuleSet> result = cachingProvider.findById("test-rule");

    assertThat(result).isPresent().contains(ruleSet);
    assertThat(delegate.getCallCount()).isZero();
  }

  @Test
  void shouldLoadFromDelegateOnCacheMiss() {
    RateLimitRuleSet ruleSet = createTestRuleSet("test-rule");
    delegate.addRuleSet("test-rule", ruleSet);

    Optional<RateLimitRuleSet> result = cachingProvider.findById("test-rule");

    assertThat(result).isPresent().contains(ruleSet);
    assertThat(delegate.getCallCount()).isEqualTo(1);
    assertThat(cache.get("test-rule")).isPresent();
  }

  @Test
  void shouldNotCacheWhenDelegateReturnsEmpty() {
    Optional<RateLimitRuleSet> result = cachingProvider.findById("missing-rule");

    assertThat(result).isEmpty();
    assertThat(cache.get("missing-rule")).isEmpty();
  }

  @Test
  void shouldInvalidateCacheOnReloadEvent() {
    RateLimitRuleSet ruleSet = createTestRuleSet("test-rule");
    cache.put("test-rule", ruleSet);

    RuleReloadEvent event = RuleReloadEvent.forRuleSet("test-rule", ReloadSource.PUBSUB);
    cachingProvider.onReload(event);

    assertThat(cache.get("test-rule")).isEmpty();
  }

  @Test
  void shouldInvalidateAllOnFullReloadEvent() {
    cache.put("rule-1", createTestRuleSet("rule-1"));
    cache.put("rule-2", createTestRuleSet("rule-2"));

    RuleReloadEvent event = RuleReloadEvent.fullReload(ReloadSource.MANUAL);
    cachingProvider.onReload(event);

    assertThat(cache.size()).isZero();
  }

  @Test
  void shouldExposeDelegate() {
    assertThat(cachingProvider.getDelegate()).isSameAs(delegate);
  }

  @Test
  void shouldExposeCache() {
    assertThat(cachingProvider.getCache()).isSameAs(cache);
  }

  private RateLimitRuleSet createTestRuleSet(String id) {
    RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100).build();
    RateLimitRule rule = RateLimitRule.builder("rule-1").addBand(band).build();
    return RateLimitRuleSet.builder(id)
        .rules(List.of(rule))
        .keyResolver(ctx -> new RateLimitKey(ctx.getClientIp()))
        .build();
  }

  /** Simple test implementation of RateLimitRuleSetProvider. */
  static class TestRuleSetProvider implements RateLimitRuleSetProvider {
    private final ConcurrentHashMap<String, RateLimitRuleSet> ruleSets = new ConcurrentHashMap<>();
    private final AtomicInteger callCount = new AtomicInteger(0);

    void addRuleSet(String id, RateLimitRuleSet ruleSet) {
      ruleSets.put(id, ruleSet);
    }

    int getCallCount() {
      return callCount.get();
    }

    @Override
    public Optional<RateLimitRuleSet> findById(String ruleSetId) {
      callCount.incrementAndGet();
      return Optional.ofNullable(ruleSets.get(ruleSetId));
    }
  }

  /** Simple test implementation of RuleCache. */
  static class TestRuleCache implements RuleCache {
    private final ConcurrentHashMap<String, RateLimitRuleSet> cache = new ConcurrentHashMap<>();

    @Override
    public Optional<RateLimitRuleSet> get(String ruleSetId) {
      return Optional.ofNullable(cache.get(ruleSetId));
    }

    @Override
    public void put(String ruleSetId, RateLimitRuleSet ruleSet) {
      cache.put(ruleSetId, ruleSet);
    }

    @Override
    public void invalidate(String ruleSetId) {
      cache.remove(ruleSetId);
    }

    @Override
    public void invalidateAll() {
      cache.clear();
    }

    @Override
    public Set<String> getCachedRuleSetIds() {
      return new HashSet<>(cache.keySet());
    }

    @Override
    public int size() {
      return cache.size();
    }
  }
}
