package org.fluxgate.core.bucket;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Concurrency and lifecycle tests for {@link Bucket4jRateLimiter}.
 *
 * <p>Verifies that multi-band rules are evaluated atomically under concurrent load, that eviction
 * events are counted (but explicit resets are not), and that {@code reset()} / {@code resetAll()}
 * correctly scope their effects.
 */
class Bucket4jMultiBandAtomicTest {

  // =========================================================================
  // Helpers
  // =========================================================================

  private static RequestContext ctx(String ip) {
    return RequestContext.builder().clientIp(ip).endpoint("/api/test").method("GET").build();
  }

  private static RateLimitKey ipKey(String ip) {
    return RateLimitKey.of(ip);
  }

  /**
   * A rule with a fast burst band (capacity {@code burst}, window {@code burstWindow}) and a slow
   * cap band (capacity {@code cap}, window {@code capWindow}).
   */
  private static RateLimitRuleSet twoBandRuleSet(
      String ruleSetId, long burst, Duration burstWindow, long cap, Duration capWindow) {

    RateLimitBand fastBand =
        RateLimitBand.builder(burstWindow, burst).label("burst-" + burst).build();
    RateLimitBand slowBand = RateLimitBand.builder(capWindow, cap).label("cap-" + cap).build();

    RateLimitRule rule =
        RateLimitRule.builder("two-band-rule")
            .scope(LimitScope.PER_IP)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(fastBand)
            .addBand(slowBand)
            .ruleSetId(ruleSetId)
            .build();

    return RateLimitRuleSet.builder(ruleSetId)
        .rules(List.of(rule))
        .keyResolver((ctx, r) -> ipKey(ctx.getClientIp()))
        .build();
  }

  // =========================================================================
  // Multi-band atomicity under concurrent load
  // =========================================================================

  @Test
  @DisplayName("Exactly capacity allows are counted across 16 threads with two-band rule")
  void exactlyCapacityAllowsUnder16Threads() throws Exception {
    // burst=20, cap=20 — same capacity on both bands, so neither is looser than the other.
    // With 16 threads firing simultaneously the total allowed count must never exceed 20.
    final int CAPACITY = 20;
    final int THREADS = 16;
    final int REQUESTS_PER_THREAD = 5;

    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();
    RateLimitRuleSet ruleSet =
        twoBandRuleSet("atomic-rs", CAPACITY, Duration.ofMinutes(1), CAPACITY, Duration.ofHours(1));

    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger allowed = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();

    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    List<Future<?>> futures = new ArrayList<>();

    for (int t = 0; t < THREADS; t++) {
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                  RateLimitResult r = limiter.tryConsume(ctx("10.0.0.1"), ruleSet, 1);
                  if (r.isAllowed()) {
                    allowed.incrementAndGet();
                  } else {
                    rejected.incrementAndGet();
                  }
                }
              }));
    }

    ready.await(5, TimeUnit.SECONDS);
    start.countDown();

    pool.shutdown();
    assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

    int total = allowed.get() + rejected.get();
    assertThat(total).isEqualTo(THREADS * REQUESTS_PER_THREAD);
    assertThat(allowed.get())
        .as("allowed count must not exceed bucket capacity (atomicity guard)")
        .isLessThanOrEqualTo(CAPACITY);
    assertThat(allowed.get())
        .as("should have allowed exactly CAPACITY (no token loss)")
        .isEqualTo(CAPACITY);
  }

  @Test
  @DisplayName("Rejected requests do not drain the other band under concurrent load")
  void concurrentRejectsDoNotDrainOtherBandUnderLoad() throws Exception {
    // burst=3 per 100ms, cap=100 per minute.
    // Fire enough concurrent requests to saturate burst. The cap must not be drained by rejections.
    final int THREADS = 16;
    final int REQUESTS_PER_THREAD = 4;

    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();
    RateLimitRuleSet ruleSet =
        twoBandRuleSet("no-drain-rs", 3, Duration.ofMillis(100), 100, Duration.ofMinutes(1));

    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger rejectedByBurst = new AtomicInteger();

    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    List<Future<?>> futures = new ArrayList<>();

    for (int t = 0; t < THREADS; t++) {
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                  RateLimitResult r = limiter.tryConsume(ctx("10.1.0.1"), ruleSet, 1);
                  if (!r.isAllowed() && "burst-3".equals(r.getBandLabel())) {
                    rejectedByBurst.incrementAndGet();
                  }
                }
              }));
    }

    ready.await(5, TimeUnit.SECONDS);
    start.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

    // After burst refills, the cap must still have capacity remaining.
    Thread.sleep(150);

    RateLimitResult capCheck = limiter.tryConsume(ctx("10.1.0.1"), ruleSet, 1);
    assertThat(capCheck.isAllowed())
        .as("cap band must not have been drained by rejected-burst requests")
        .isTrue();
  }

  // =========================================================================
  // Eviction counting
  // =========================================================================

  @Test
  @DisplayName("Evictions increment the eviction counter")
  void evictionCounterIncrements() throws Exception {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter(50L, Duration.ofMillis(50));
    RateLimitRuleSet ruleSet =
        twoBandRuleSet("evict-rs", 100, Duration.ofMinutes(1), 1000, Duration.ofHours(1));

    // Create 500 distinct keys — well above the cache size of 50.
    for (int i = 0; i < 500; i++) {
      String ip = "10.2." + (i / 250) + "." + (i % 250);
      limiter.tryConsume(ctx(ip), ruleSet, 1);
    }

    long deadline = System.currentTimeMillis() + 5_000L;
    while (limiter.getEvictionCount() == 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(20L);
    }

    assertThat(limiter.getEvictionCount()).isPositive();
  }

  @Test
  @DisplayName("Explicit reset does not count as eviction")
  void explicitResetDoesNotCountAsEviction() throws Exception {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();
    RateLimitRuleSet ruleSet =
        twoBandRuleSet("reset-count-rs", 10, Duration.ofMinutes(1), 100, Duration.ofHours(1));

    limiter.tryConsume(ctx("10.3.0.1"), ruleSet, 1);
    limiter.tryConsume(ctx("10.3.0.2"), ruleSet, 1);

    limiter.resetAll();
    Thread.sleep(100L);

    assertThat(limiter.getEvictionCount())
        .as("invalidateAll is not an eviction (cause.wasEvicted() returns false)")
        .isZero();
  }

  // =========================================================================
  // Reset / resetAll scoping
  // =========================================================================

  @Test
  @DisplayName("reset(ruleSetId) only removes buckets of that rule set")
  void resetScopedToOneRuleSet() {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();

    RateLimitRuleSet first =
        twoBandRuleSet("first-rs", 5, Duration.ofMinutes(1), 20, Duration.ofHours(1));
    RateLimitRuleSet second =
        twoBandRuleSet("second-rs", 5, Duration.ofMinutes(1), 20, Duration.ofHours(1));

    // Exhaust first rule set for two IPs, and touch second for one.
    for (int i = 0; i < 5; i++) {
      limiter.tryConsume(ctx("10.4.0.1"), first, 1);
      limiter.tryConsume(ctx("10.4.0.2"), first, 1);
    }
    limiter.tryConsume(ctx("10.4.0.1"), second, 1);

    // Both IPs are now at 0 for first.
    assertThat(limiter.tryConsume(ctx("10.4.0.1"), first, 1).isAllowed()).isFalse();
    assertThat(limiter.tryConsume(ctx("10.4.0.2"), first, 1).isAllowed()).isFalse();

    limiter.reset("first-rs");

    // first rule set buckets are gone — rebuilt full.
    assertThat(limiter.tryConsume(ctx("10.4.0.1"), first, 1).isAllowed()).isTrue();
    assertThat(limiter.tryConsume(ctx("10.4.0.2"), first, 1).isAllowed()).isTrue();

    // second rule set bucket is untouched — still has 4 remaining.
    RateLimitResult secondResult = limiter.tryConsume(ctx("10.4.0.1"), second, 1);
    assertThat(secondResult.isAllowed()).isTrue();
    assertThat(secondResult.getRemainingTokens()).isEqualTo(3L);
  }

  @Test
  @DisplayName("resetAll clears every bucket across all rule sets")
  void resetAllClearsEverything() {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();

    RateLimitRuleSet rs1 = twoBandRuleSet("rs1", 3, Duration.ofMinutes(1), 10, Duration.ofHours(1));
    RateLimitRuleSet rs2 = twoBandRuleSet("rs2", 3, Duration.ofMinutes(1), 10, Duration.ofHours(1));

    for (int i = 0; i < 3; i++) {
      limiter.tryConsume(ctx("10.5.0.1"), rs1, 1);
      limiter.tryConsume(ctx("10.5.0.1"), rs2, 1);
    }

    assertThat(limiter.tryConsume(ctx("10.5.0.1"), rs1, 1).isAllowed()).isFalse();
    assertThat(limiter.tryConsume(ctx("10.5.0.1"), rs2, 1).isAllowed()).isFalse();

    limiter.resetAll();

    assertThat(limiter.size()).isZero();
    assertThat(limiter.tryConsume(ctx("10.5.0.1"), rs1, 1).isAllowed()).isTrue();
    assertThat(limiter.tryConsume(ctx("10.5.0.1"), rs2, 1).isAllowed()).isTrue();
  }

  // =========================================================================
  // Cache size bound
  // =========================================================================

  @Test
  @DisplayName("Cache never exceeds configured maximumSize regardless of distinct key volume")
  void cacheSizeStaysWithinBound() {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter(100L);
    RateLimitRuleSet ruleSet =
        twoBandRuleSet("bound-rs", 10, Duration.ofMinutes(1), 100, Duration.ofHours(1));

    for (int i = 0; i < 5_000; i++) {
      String ip = "10.6." + (i / 250) + "." + (i % 250);
      limiter.tryConsume(ctx(ip), ruleSet, 1);
    }

    assertThat(limiter.size()).isLessThanOrEqualTo(limiter.getMaximumSize());
  }
}
