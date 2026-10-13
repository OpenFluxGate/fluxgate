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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for the default {@link RuleCache#getOrLoad(String, java.util.function.Function)}. */
class RuleCacheTest {

  private TestRuleCache cache;
  private AtomicInteger loaderCalls;

  @BeforeEach
  void setUp() {
    cache = new TestRuleCache();
    loaderCalls = new AtomicInteger(0);
  }

  @Test
  void getOrLoadShouldReturnCachedValueWithoutCallingLoader() {
    RateLimitRuleSet ruleSet = createTestRuleSet("test-rule");
    cache.put("test-rule", ruleSet);

    Optional<RateLimitRuleSet> result = cache.getOrLoad("test-rule", id -> load(ruleSet));

    assertThat(result).contains(ruleSet);
    assertThat(loaderCalls.get()).isZero();
  }

  @Test
  void getOrLoadShouldLoadAndCacheOnMiss() {
    RateLimitRuleSet ruleSet = createTestRuleSet("test-rule");

    Optional<RateLimitRuleSet> result = cache.getOrLoad("test-rule", id -> load(ruleSet));

    assertThat(result).contains(ruleSet);
    assertThat(loaderCalls.get()).isEqualTo(1);
    assertThat(cache.get("test-rule")).contains(ruleSet);
  }

  @Test
  void getOrLoadShouldNotCacheEmptyResults() {
    Optional<RateLimitRuleSet> result = cache.getOrLoad("missing", id -> load(null));

    assertThat(result).isEmpty();
    assertThat(cache.get("missing")).isEmpty();
    assertThat(cache.size()).isZero();
  }

  @Test
  void getOrLoadShouldPassTheRuleSetIdToTheLoader() {
    cache.getOrLoad(
        "requested-id",
        id -> {
          assertThat(id).isEqualTo("requested-id");
          return Optional.empty();
        });
  }

  private Optional<RateLimitRuleSet> load(RateLimitRuleSet ruleSet) {
    loaderCalls.incrementAndGet();
    return Optional.ofNullable(ruleSet);
  }

  private RateLimitRuleSet createTestRuleSet(String id) {
    RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100).build();
    RateLimitRule rule = RateLimitRule.builder("rule-1").addBand(band).build();
    return RateLimitRuleSet.builder(id)
        .rules(List.of(rule))
        .keyResolver((ctx, r) -> new RateLimitKey(ctx.getClientIp()))
        .build();
  }

  /** Minimal RuleCache that keeps the default getOrLoad implementation. */
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
