package org.fluxgate.spring.reload.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.junit.jupiter.api.Test;

/** Tests for {@link CaffeineRuleCache}. */
class CaffeineRuleCacheTest {

  private static RateLimitRuleSet ruleSet(String id) {
    return RateLimitRuleSet.builder(id)
        .rules(
            List.of(
                RateLimitRule.builder(id + "-rule")
                    .name(id)
                    .enabled(true)
                    .ruleSetId(id)
                    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).build())
                    .build()))
        .keyResolver((ctx, rule) -> RateLimitKey.of("ip:10.0.0.1"))
        .build();
  }

  @Test
  void shouldRunTheLoaderOnceUnderConcurrentMisses() throws Exception {
    CaffeineRuleCache cache = new CaffeineRuleCache(Duration.ofMinutes(5), 100);
    int threads = 32;
    AtomicInteger loaderInvocations = new AtomicInteger();
    CountDownLatch startLine = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);

    try {
      for (int i = 0; i < threads; i++) {
        pool.execute(
            () -> {
              try {
                startLine.await();
                cache.getOrLoad(
                    "orders",
                    id -> {
                      loaderInvocations.incrementAndGet();
                      // Hold the key lock long enough that a non-atomic implementation would let
                      // every other thread through as well.
                      try {
                        Thread.sleep(50);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      return Optional.of(ruleSet(id));
                    });
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                finished.countDown();
              }
            });
      }

      startLine.countDown();
      assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(loaderInvocations).hasValue(1);
    assertThat(cache.get("orders")).isPresent();
  }

  @Test
  void shouldCacheNegativeResultsForTheNegativeTtl() {
    CaffeineRuleCache cache =
        new CaffeineRuleCache(Duration.ofMinutes(5), 100, Duration.ofMinutes(5));
    AtomicInteger loaderInvocations = new AtomicInteger();

    for (int i = 0; i < 10; i++) {
      Optional<RateLimitRuleSet> result =
          cache.getOrLoad(
              "typo",
              id -> {
                loaderInvocations.incrementAndGet();
                return Optional.empty();
              });
      assertThat(result).isEmpty();
    }

    assertThat(loaderInvocations).hasValue(1);
    assertThat(cache.negativeSize()).isEqualTo(1);
  }

  @Test
  void shouldQueryAgainForEveryMissWhenNegativeCachingIsDisabled() {
    CaffeineRuleCache cache = new CaffeineRuleCache(Duration.ofMinutes(5), 100, Duration.ZERO);
    AtomicInteger loaderInvocations = new AtomicInteger();

    for (int i = 0; i < 3; i++) {
      cache.getOrLoad(
          "typo",
          id -> {
            loaderInvocations.incrementAndGet();
            return Optional.empty();
          });
    }

    assertThat(loaderInvocations).hasValue(3);
    assertThat(cache.getNegativeTtl()).isEqualTo(Duration.ZERO);
  }

  @Test
  void shouldForgetARememberedMissWhenTheRuleSetIsCreated() {
    CaffeineRuleCache cache =
        new CaffeineRuleCache(Duration.ofMinutes(5), 100, Duration.ofMinutes(5));
    cache.getOrLoad("orders", id -> Optional.empty());
    assertThat(cache.negativeSize()).isEqualTo(1);

    cache.put("orders", ruleSet("orders"));

    assertThat(cache.negativeSize()).isZero();
    assertThat(cache.getOrLoad("orders", id -> Optional.empty())).isPresent();
  }

  @Test
  void shouldForgetARememberedMissOnInvalidation() {
    CaffeineRuleCache cache =
        new CaffeineRuleCache(Duration.ofMinutes(5), 100, Duration.ofMinutes(5));
    cache.getOrLoad("orders", id -> Optional.empty());

    cache.invalidate("orders");

    AtomicInteger loaderInvocations = new AtomicInteger();
    cache.getOrLoad(
        "orders",
        id -> {
          loaderInvocations.incrementAndGet();
          return Optional.of(ruleSet(id));
        });
    assertThat(loaderInvocations).hasValue(1);
  }

  @Test
  void shouldForgetAllRememberedMissesOnFullInvalidation() {
    CaffeineRuleCache cache =
        new CaffeineRuleCache(Duration.ofMinutes(5), 100, Duration.ofMinutes(5));
    cache.getOrLoad("a", id -> Optional.empty());
    cache.getOrLoad("b", id -> Optional.empty());

    cache.invalidateAll();

    assertThat(cache.negativeSize()).isZero();
  }

  @Test
  void shouldServeCachedEntriesWithoutCallingTheLoader() {
    CaffeineRuleCache cache = new CaffeineRuleCache(Duration.ofMinutes(5), 100);
    cache.put("orders", ruleSet("orders"));

    Optional<RateLimitRuleSet> result =
        cache.getOrLoad(
            "orders",
            id -> {
              throw new AssertionError("loader must not be called on a hit");
            });

    assertThat(result).isPresent();
  }
}
