package org.fluxgate.spring.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.fluxgate.core.exception.RedisConnectionException;
import org.fluxgate.core.exception.RedisUnavailableException;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LazyRedisRateLimiter}.
 *
 * <p>Two behaviours are pinned here. First the original fix for "Redis down at boot crashes the
 * application": creating the limiter must succeed even when the store cannot be built, and calls
 * must fail in a way the configured failure behaviour can act on. Second the fix for the thread
 * pool exhaustion that replaced it: a request thread must never connect, so eight concurrent calls
 * against an unreachable Redis return immediately instead of serialising behind one blocking
 * connect each.
 */
class LazyRedisRateLimiterTest {

  private static final Duration RETRY = Duration.ofHours(1);

  /** How long a request is allowed to take while the limiter is disconnected. */
  private static final long MAX_REQUEST_MILLIS = 50L;

  /** How long the failing connect blocks, standing in for a TCP connect timeout. */
  private static final long CONNECT_BLOCK_MILLIS = 300L;

  @Test
  void shouldConnectEagerlyWhenTheStoreIsAvailable() {
    RedisTokenBucketStore store = mock(RedisTokenBucketStore.class);
    AtomicInteger supplierCalls = new AtomicInteger();

    try (LazyRedisRateLimiter limiter =
        new LazyRedisRateLimiter(
            () -> {
              supplierCalls.incrementAndGet();
              return store;
            },
            RETRY,
            RETRY)) {

      assertThat(limiter.isReady()).isTrue();
      assertThat(limiter.getFailedAttempts()).isZero();
      assertThat(limiter.getLastErrorMessage()).isNull();
      assertThat(supplierCalls).hasValue(1);
    }
  }

  @Test
  void shouldStillBeCreatableWhenRedisIsUnreachable() {
    try (LazyRedisRateLimiter limiter =
        new LazyRedisRateLimiter(
            () -> {
              throw new RedisConnectionException("connection refused");
            },
            RETRY,
            RETRY)) {

      assertThat(limiter.isReady()).isFalse();
      assertThat(limiter.getFailedAttempts()).isEqualTo(1);
      assertThat(limiter.getLastErrorMessage()).contains("connection refused");
    }
  }

  @Test
  void shouldFailCallsWithANonRetryableExceptionWhileDisconnected() {
    try (LazyRedisRateLimiter limiter =
        new LazyRedisRateLimiter(
            () -> {
              throw new IllegalStateException("script load failed");
            },
            RETRY,
            RETRY)) {

      assertThatThrownBy(() -> limiter.tryConsume(null, mock(RateLimitRuleSet.class), 1L))
          .isInstanceOf(RedisUnavailableException.class)
          .hasMessageContaining("not connected yet")
          // A retryable exception here made the retry executor multiply the outage.
          .matches(e -> !((RedisUnavailableException) e).isRetryable());
    }
  }

  @Test
  void shouldMaskCredentialsCarriedInTheFailureMessage() {
    try (LazyRedisRateLimiter limiter =
        new LazyRedisRateLimiter(
            () -> {
              throw new RedisConnectionException(
                  "Unable to connect to redis://admin:s3cret@10.0.0.9:6379 (password=s3cret)");
            },
            RETRY,
            RETRY)) {

      assertThat(limiter.getLastErrorMessage()).doesNotContain("s3cret");
      assertThat(limiter.getLastErrorMessage()).contains("redis://***@10.0.0.9:6379");
      assertThat(limiter.getLastErrorMessage()).contains("password=***");
    }
  }

  @Test
  void shouldNotConnectOnTheRequestThread() throws Exception {
    AtomicInteger supplierCalls = new AtomicInteger();
    int threads = 8;

    try (LazyRedisRateLimiter limiter =
        new LazyRedisRateLimiter(
            () -> {
              supplierCalls.incrementAndGet();
              sleep(CONNECT_BLOCK_MILLIS);
              throw new RedisConnectionException("connect timed out");
            },
            RETRY,
            RETRY)) {

      // Only the constructor attempt has run so far.
      assertThat(supplierCalls).hasValue(1);

      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      try {
        List<Future<Long>> latencies = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
          latencies.add(
              pool.submit(
                  () -> {
                    start.await();
                    long begin = System.nanoTime();
                    assertThatThrownBy(
                            () -> limiter.tryConsume(null, mock(RateLimitRuleSet.class), 1L))
                        .isInstanceOf(RedisUnavailableException.class);
                    return (System.nanoTime() - begin) / 1_000_000L;
                  }));
        }
        start.countDown();

        for (Future<Long> latency : latencies) {
          assertThat(latency.get(10, TimeUnit.SECONDS)).isLessThan(MAX_REQUEST_MILLIS);
        }
      } finally {
        pool.shutdownNow();
      }

      // Request traffic neither connected nor advanced the backoff: the reconnect interval is an
      // hour, so the count must still be the single constructor attempt.
      assertThat(supplierCalls).hasValue(1);
      assertThat(limiter.getFailedAttempts()).isEqualTo(1);
      sleep(200L);
      assertThat(supplierCalls).hasValue(1);
    }
  }

  @Test
  void shouldKeepRetryingOnTheBackgroundSchedulerUntilClosed() {
    AtomicInteger supplierCalls = new AtomicInteger();

    try (LazyRedisRateLimiter limiter =
        new LazyRedisRateLimiter(
            () -> {
              supplierCalls.incrementAndGet();
              throw new RedisConnectionException("still down");
            },
            Duration.ofMillis(20),
            Duration.ofMillis(40))) {

      awaitUntil(() -> supplierCalls.get() >= 3);
      assertThat(limiter.isReady()).isFalse();
      assertThat(limiter.getFailedAttempts()).isGreaterThanOrEqualTo(3);
    }
  }

  @Test
  void shouldRecoverOnTheBackgroundSchedulerOnceRedisComesBack() {
    RedisTokenBucketStore store = mock(RedisTokenBucketStore.class);
    AtomicReference<RedisTokenBucketStore> available = new AtomicReference<>();

    try (LazyRedisRateLimiter limiter =
        new LazyRedisRateLimiter(
            () -> {
              RedisTokenBucketStore current = available.get();
              if (current == null) {
                throw new RedisConnectionException("still down");
              }
              return current;
            },
            Duration.ofMillis(20),
            Duration.ofMillis(40))) {

      assertThat(limiter.isReady()).isFalse();

      available.set(store);
      // The reconnect task picks it up; a request never blocks waiting for it.
      awaitUntil(limiter::isReady);

      assertThat(limiter.getFailedAttempts()).isZero();
      assertThat(limiter.getLastErrorMessage()).isNull();
    }
  }

  @Test
  void shouldStopTheReconnectSchedulerOnClose() {
    AtomicInteger supplierCalls = new AtomicInteger();

    LazyRedisRateLimiter limiter =
        new LazyRedisRateLimiter(
            () -> {
              supplierCalls.incrementAndGet();
              throw new RedisConnectionException("still down");
            },
            Duration.ofMillis(20),
            Duration.ofMillis(40));

    awaitUntil(() -> supplierCalls.get() >= 2);
    limiter.close();

    int afterClose = supplierCalls.get();
    sleep(200L);
    assertThat(supplierCalls).hasValue(afterClose);
  }

  @Test
  void shouldBeSafeToCloseTwice() {
    LazyRedisRateLimiter limiter =
        new LazyRedisRateLimiter(() -> mock(RedisTokenBucketStore.class), RETRY, RETRY);

    limiter.close();
    limiter.close();

    assertThat(limiter.isReady()).isTrue();
  }

  /** Waits up to five seconds for the condition to hold, failing the test otherwise. */
  private static void awaitUntil(java.util.function.BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      sleep(10L);
    }
    throw new AssertionError("Condition was not met within 5s");
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
