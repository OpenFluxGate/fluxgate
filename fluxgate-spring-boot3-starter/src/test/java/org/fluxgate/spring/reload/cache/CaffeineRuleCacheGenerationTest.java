package org.fluxgate.spring.reload.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for N8 — CaffeineRuleCache generation counter prevents stale negative-cache
 * entries after a concurrent invalidation.
 */
class CaffeineRuleCacheGenerationTest {

  private CaffeineRuleCache cache;

  private static RateLimitRuleSet makeRuleSet(String id) {
    return RateLimitRuleSet.builder(id)
        .keyResolver(new LimitScopeKeyResolver())
        .rules(
            List.of(
                RateLimitRule.builder("r1")
                    .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10).build())
                    .build()))
        .build();
  }

  @BeforeEach
  void setup() {
    // 5 min TTL, 1000 size, 30 s negative TTL
    cache = new CaffeineRuleCache(Duration.ofMinutes(5), 1000, Duration.ofSeconds(30));
  }

  @Test
  @DisplayName("invalidate() increments generation so a racing miss-load is not cached")
  void invalidateRacingMissNotCached() throws InterruptedException {
    // Seed the cache
    cache.put("set1", makeRuleSet("set1"));

    CountDownLatch loaderStarted = new CountDownLatch(1);
    CountDownLatch allowPut = new CountDownLatch(1);

    Thread loader =
        new Thread(
            () ->
                cache.getOrLoad(
                    "set2",
                    id -> {
                      loaderStarted.countDown();
                      try {
                        allowPut.await();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      return Optional.empty();
                    }));
    loader.start();

    loaderStarted.await();
    // Invalidate while the loader is blocked — must increment generation
    cache.invalidateAll();

    allowPut.countDown();
    loader.join(5_000);

    // The loader returned empty, but because the generation changed the miss must not be cached
    assertThat(cache.negativeSize())
        .as("negative entry must not be recorded after a racing invalidation")
        .isZero();
  }

  @Test
  @DisplayName("miss with no concurrent invalidation IS recorded as a negative entry")
  void quietMissIsRecorded() {
    cache.getOrLoad("nonexistent", id -> Optional.empty());
    assertThat(cache.negativeSize())
        .as("negative entry should be recorded when there is no racing invalidation")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("invalidate(key) does not prevent a subsequent successful load")
  void invalidateSingleKeyAllowsReload() {
    AtomicInteger loaderCalls = new AtomicInteger();
    cache.put("set3", makeRuleSet("set3"));
    cache.invalidate("set3");

    Optional<RateLimitRuleSet> result =
        cache.getOrLoad(
            "set3",
            id -> {
              loaderCalls.incrementAndGet();
              return Optional.of(makeRuleSet(id));
            });

    assertThat(result).isPresent();
    assertThat(loaderCalls.get()).isEqualTo(1);
  }
}
