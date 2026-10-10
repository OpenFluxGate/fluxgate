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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Bucket4jRateLimiter reload, expiry and locking")
class Bucket4jReloadAndExpiryTest {

  private static final RequestContext CTX =
      RequestContext.builder().clientIp("10.0.0.1").endpoint("/api").method("GET").build();

  private static RateLimitRuleSet ruleSet(
      Duration window, long capacity, RateLimitMetricsRecorder recorder) {
    RateLimitRule rule =
        RateLimitRule.builder("rule-1")
            .scope(LimitScope.GLOBAL)
            .addBand(RateLimitBand.builder(window, capacity).label("band").build())
            .build();
    return RateLimitRuleSet.builder("rs-1")
        .rules(List.of(rule))
        .keyResolver((ctx, r) -> RateLimitKey.of("k"))
        .metricsRecorder(recorder)
        .build();
  }

  @Test
  @DisplayName("a request still holding the old rule set cannot pin the old bands after reset")
  void staleRuleSetAfterResetDoesNotPinOldBands() {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();
    RateLimitRuleSet v1 = ruleSet(Duration.ofHours(1), 1, null);
    RateLimitRuleSet v2 = ruleSet(Duration.ofHours(1), 3, null);

    assertThat(limiter.tryConsume(CTX, v1, 1).isAllowed()).isTrue();

    // reload: the provider resets the buckets, but an in-flight request still carries v1
    limiter.reset("rs-1");
    assertThat(limiter.tryConsume(CTX, v1, 1).isAllowed()).isTrue();

    assertThat(limiter.tryConsume(CTX, v2, 1).isAllowed()).as("new limit applies").isTrue();

    // another straggler must not replace the v2 bucket either
    limiter.tryConsume(CTX, v1, 1);

    assertThat(limiter.tryConsume(CTX, v2, 1).isAllowed()).isTrue();
    assertThat(limiter.tryConsume(CTX, v2, 1).isAllowed()).isTrue();
    assertThat(limiter.tryConsume(CTX, v2, 1).isAllowed())
        .as("v2 budget of 3 is shared by all v2 requests")
        .isFalse();
  }

  @Test
  @DisplayName("a quota longer than expireAfterAccess is not reset by an idle period")
  void longWindowSurvivesIdleBeyondExpireAfterAccess() throws Exception {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter(100, Duration.ofMillis(50));
    RateLimitRuleSet daily = ruleSet(Duration.ofDays(1), 1, null);

    assertThat(limiter.tryConsume(CTX, daily, 1).isAllowed()).isTrue();
    Thread.sleep(250L);
    limiter.size(); // run cache maintenance

    assertThat(limiter.tryConsume(CTX, daily, 1).isAllowed())
        .as("the daily quota must not refill after 50 ms of inactivity")
        .isFalse();
  }

  @Test
  @DisplayName("buckets of short windows still expire after the configured idle time")
  void shortWindowExpiresAfterConfiguredIdleTime() throws Exception {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter(100, Duration.ofMillis(50));
    RateLimitRuleSet shortWindow = ruleSet(Duration.ofMillis(10), 1, null);

    limiter.tryConsume(CTX, shortWindow, 1);
    long deadline = System.currentTimeMillis() + 5_000L;
    while (limiter.size() > 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(20L);
    }

    assertThat(limiter.size()).isZero();
  }

  @Test
  @DisplayName("the metrics recorder runs after the bucket locks are released")
  void metricsRecorderRunsWithoutBucketLocks() throws Exception {
    ExecutorService other = Executors.newSingleThreadExecutor();
    try {
      AtomicBoolean first = new AtomicBoolean(true);
      AtomicReference<Throwable> otherOutcome = new AtomicReference<>();
      AtomicReference<RateLimitRuleSet> holder = new AtomicReference<>();
      Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();

      RateLimitMetricsRecorder recorder =
          (ctx, result) -> {
            if (!first.compareAndSet(true, false)) {
              return;
            }
            // a concurrent request on the same bucket must not wait for this recorder
            Future<?> f = other.submit(() -> limiter.tryConsume(CTX, holder.get(), 1));
            try {
              f.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
              otherOutcome.set(e);
            } catch (Exception e) {
              otherOutcome.set(e);
            }
          };
      holder.set(ruleSet(Duration.ofHours(1), 5, recorder));

      limiter.tryConsume(CTX, holder.get(), 1);

      assertThat(otherOutcome.get()).as("concurrent request blocked by the recorder").isNull();
    } finally {
      other.shutdownNow();
    }
  }

  @Test
  @DisplayName("consumption racing resets neither deadlocks nor fails")
  void consumptionRacingResetsStaysConsistent() throws Exception {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();
    RateLimitRuleSet rs = ruleSet(Duration.ofHours(1), 1_000_000, null);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int t = 0; t < 3; t++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  for (int i = 0; i < 2_000; i++) {
                    assertThat(limiter.tryConsume(CTX, rs, 1).isAllowed()).isTrue();
                  }
                  return null;
                }));
      }
      futures.add(
          pool.submit(
              () -> {
                start.await();
                for (int i = 0; i < 500; i++) {
                  limiter.reset("rs-1");
                }
                return null;
              }));
      start.countDown();
      for (Future<?> f : futures) {
        f.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(limiter.size()).isLessThanOrEqualTo(1);
  }
}
