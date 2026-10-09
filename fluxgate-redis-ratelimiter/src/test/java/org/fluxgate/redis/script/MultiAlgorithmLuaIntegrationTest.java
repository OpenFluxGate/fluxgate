package org.fluxgate.redis.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.RedisCommandExecutionException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.support.RedisContainerSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the multi-algorithm {@code token_bucket_consume.lua} executed against a
 * real Redis.
 *
 * <p>These drive the script directly to cover algorithm-specific ARGV contracts, TTL/EXPIREAT
 * semantics, boundary conditions, mixed-algorithm all-or-nothing atomicity, and the legacy
 * TOKEN_BUCKET path.
 *
 * <p>The target comes from {@link RedisContainerSupport}: a supplied {@code FLUXGATE_REDIS_URI}, a
 * Testcontainers {@code redis:7-alpine}, or the test is skipped.
 */
class MultiAlgorithmLuaIntegrationTest {

  private static final String RUN_ID = RedisContainerSupport.newRunId();
  private static final long SECOND_MICROS = 1_000_000L;
  private static final long MINUTE_MICROS = 60 * SECOND_MICROS;
  private static final long HOUR_MICROS = 3600 * SECOND_MICROS;
  private static final long DEFAULT_MAX_TTL_SECONDS = Duration.ofDays(7).getSeconds();

  // Return-array slots
  private static final int ALLOWED = 0;
  private static final int REJECTING_BAND = 1;
  private static final int MIN_REMAINING = 2;
  private static final int MICROS_TO_WAIT = 3;
  private static final int RESET_TIME_MILLIS = 4;
  private static final int LIMIT = 5;
  private static final int BINDING_BAND = 6;

  // Algorithm codes matching the Lua constants
  private static final int ALG_TOKEN_BUCKET = 1;
  private static final int ALG_SLIDING_WINDOW = 2;
  private static final int ALG_FIXED_WINDOW = 3;

  private static RedisRateLimiterConfig config;
  private static RedisConnectionProvider redis;
  private static LuaScriptRegistry scripts;

  @BeforeAll
  static void setUp() {
    config = new RedisRateLimiterConfig(RedisContainerSupport.redisUri());
    redis = config.getConnectionProvider();
    scripts = new LuaScriptRegistry();
    scripts.loadInto(redis);
  }

  @AfterAll
  static void tearDown() {
    if (config != null) {
      RedisContainerSupport.deleteKeys(redis, keyPattern());
      config.close();
    }
  }

  // =========================================================================
  // TOKEN_BUCKET — legacy behaviour must be unchanged
  // =========================================================================

  @Nested
  @DisplayName("TOKEN_BUCKET")
  class TokenBucket {

    @Test
    @DisplayName("Admits requests while tokens remain and then rejects — legacy behaviour")
    void admitThenReject() {
      String key = key("tb-admit");
      String[] b = band(5, MINUTE_MICROS, ALG_TOKEN_BUCKET, 0, 0);

      List<Long> first = consume(1, b, key);
      assertThat(first.get(ALLOWED)).isEqualTo(1L);
      assertThat(first.get(LIMIT)).isEqualTo(5L);
      assertThat(first.get(MIN_REMAINING)).isEqualTo(4L);
      assertThat(first.get(MICROS_TO_WAIT)).isZero();
      assertThat(first.get(BINDING_BAND)).isEqualTo(1L);

      for (int i = 0; i < 4; i++) {
        assertThat(consume(1, b, key).get(ALLOWED)).isEqualTo(1L);
      }

      List<Long> rejected = consume(1, b, key);
      assertThat(rejected.get(ALLOWED)).isZero();
      assertThat(rejected.get(MIN_REMAINING)).isZero();
      assertThat(rejected.get(MICROS_TO_WAIT)).isPositive();
    }

    @Test
    @DisplayName("Bucket keys keep stable label format (100-per-60s) for TOKEN_BUCKET")
    void stableKeyLabel() {
      // Verify that the existing per-band key format is unaffected by the algorithm extension.
      String key = key("tb-label-100-per-60s");
      assertThat(key).endsWith("100-per-60s");
      String[] b = band(100, 60 * SECOND_MICROS, ALG_TOKEN_BUCKET, 0, 0);
      assertThat(consume(1, b, key).get(ALLOWED)).isEqualTo(1L);
      long tokens = Long.parseLong(redis.hgetall(key).get("tokens"));
      assertThat(tokens).isEqualTo(99L);
    }
  }

  // =========================================================================
  // SLIDING_WINDOW
  // =========================================================================

  @Nested
  @DisplayName("SLIDING_WINDOW")
  class SlidingWindow {

    @Test
    @DisplayName("Admits up to capacity then rejects")
    void admitThenReject() {
      String key = key("sw-admit");
      String[] b = band(3, MINUTE_MICROS, ALG_SLIDING_WINDOW, 10, 0);

      for (int i = 0; i < 3; i++) {
        assertThat(consume(1, b, key).get(ALLOWED)).isEqualTo(1L);
      }
      List<Long> rejected = consume(1, b, key);
      assertThat(rejected.get(ALLOWED)).isZero();
      assertThat(rejected.get(MIN_REMAINING)).isZero();
      assertThat(rejected.get(MICROS_TO_WAIT)).isPositive();
      assertThat(rejected.get(LIMIT)).isEqualTo(3L);
    }

    @Test
    @DisplayName("Remaining reflects how much capacity is still free")
    void remainingReflectsCapacity() {
      String key = key("sw-remaining");
      String[] b = band(10, MINUTE_MICROS, ALG_SLIDING_WINDOW, 6, 0);

      List<Long> result = consume(3, b, key);
      assertThat(result.get(ALLOWED)).isEqualTo(1L);
      assertThat(result.get(MIN_REMAINING)).isEqualTo(7L); // capacity 10 - 3 permits consumed
    }

    @Test
    @DisplayName("Expired sub-buckets are dropped on allow — re-admission after one window")
    void expiredSubBucketsDropped() {
      // Use a very short window so we can manipulate sub-buckets via direct HSET.
      // 10 sub-buckets × 1 s window = 100 ms each. We cannot sleep, so we seed the hash
      // with a sub-bucket index that is guaranteed to be ancient (index 0).
      String key = key("sw-drop");
      // capacity=2, window=10 micros, 2 sub-buckets → sub_dur=5 micros
      // current_sub is around now_micros / 5 ≈ huge index; index 0 is always expired.
      redis.hset(key, "0", "2"); // seed an expired sub-bucket
      // Because index 0 is far in the past (< oldest_valid), the sum is 0, so 2 permits allowed.
      String[] b = band(2, 10L, ALG_SLIDING_WINDOW, 2, 0);
      List<Long> result = consume(2, b, key);
      assertThat(result.get(ALLOWED)).isEqualTo(1L);
      // After pass 2 the expired field should have been deleted.
      Map<String, String> hash = redis.hgetall(key);
      assertThat(hash).doesNotContainKey("0");
    }

    @Test
    @DisplayName("TTL is set to at least ceil(window_seconds × 1.1)")
    void ttlIsSet() {
      String key = key("sw-ttl");
      String[] b = band(10, MINUTE_MICROS, ALG_SLIDING_WINDOW, 10, 0);
      consume(1, b, key);
      // 60 s × 1.1 = 66 s
      assertThat(redis.ttl(key)).isBetween(64L, 66L);
    }

    @Test
    @DisplayName("Rejected request does not increment any sub-bucket")
    void rejectDoesNotMutateCounters() {
      String key = key("sw-reject-nomut");
      // capacity=1, 2 sub-buckets
      String[] b = band(1, MINUTE_MICROS, ALG_SLIDING_WINDOW, 2, 0);
      consume(1, b, key); // allowed; current sub-bucket gets count 1
      Map<String, String> before = redis.hgetall(key);

      consume(1, b, key); // rejected; must not change sub-bucket counts

      Map<String, String> after = redis.hgetall(key);
      assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("reset_time_millis is approximately now + window for SLIDING_WINDOW")
    void resetTimeMillisApproxNowPlusWindow() {
      String key = key("sw-reset");
      String[] b = band(5, MINUTE_MICROS, ALG_SLIDING_WINDOW, 10, 0);
      List<Long> result = consume(1, b, key);
      assertThat(result.get(ALLOWED)).isEqualTo(1L);

      long now = System.currentTimeMillis();
      long reset = result.get(RESET_TIME_MILLIS);
      // reset = end of the current sub-bucket cycle, so it lies in (now + window - sub-bucket,
      // now + window]: 54-60 s for a 60 s window of 10 sub-buckets, plus slack for test latency
      // and clock skew between the test JVM and Redis.
      assertThat(reset).isBetween(now + 53_000L, now + 62_000L);
    }

    @Test
    @DisplayName("permits > capacity is refused as an error")
    void permitsExceedCapacity() {
      String key = key("sw-overflow");
      String[] b = band(3, MINUTE_MICROS, ALG_SLIDING_WINDOW, 5, 0);
      assertThatThrownBy(() -> consume(4, b, key))
          .isInstanceOf(RedisCommandExecutionException.class)
          .hasMessageContaining("permits exceed capacity");
    }
  }

  // =========================================================================
  // FIXED_WINDOW
  // =========================================================================

  @Nested
  @DisplayName("FIXED_WINDOW")
  class FixedWindow {

    @Test
    @DisplayName("Admits up to capacity within a window then rejects")
    void admitThenReject() {
      String key = key("fw-admit");
      // window_end = now + 10 s (in the future so the window does not expire mid-test)
      long windowEnd = (System.currentTimeMillis() + 10_000L) * 1000L;
      String[] b = band(3, MINUTE_MICROS, ALG_FIXED_WINDOW, 0, windowEnd);

      for (int i = 0; i < 3; i++) {
        assertThat(consume(1, b, key).get(ALLOWED)).isEqualTo(1L);
      }
      List<Long> rejected = consume(1, b, key);
      assertThat(rejected.get(ALLOWED)).isZero();
      assertThat(rejected.get(MIN_REMAINING)).isZero();
      assertThat(rejected.get(MICROS_TO_WAIT)).isPositive();
      assertThat(rejected.get(LIMIT)).isEqualTo(3L);
    }

    @Test
    @DisplayName("EXPIREAT is set to the supplied window_end (within 2 seconds of TTL)")
    void expireatIsSet() {
      String key = key("fw-expireat");
      long windowEndSeconds = (System.currentTimeMillis() / 1000L) + 30L;
      long windowEndMicros = windowEndSeconds * 1_000_000L;
      String[] b = band(10, 30 * SECOND_MICROS, ALG_FIXED_WINDOW, 0, windowEndMicros);

      consume(1, b, key);

      // TTL should be approximately 30 s (EXPIREAT at window_end)
      long ttl = redis.ttl(key);
      assertThat(ttl).isBetween(28L, 30L);
    }

    @Test
    @DisplayName("Sub-second window: the counter key gets a millisecond TTL on the first consume")
    void subSecondWindowHasTtl() throws InterruptedException {
      String key = key("fw-100ms-ttl");
      // Keep the whole window inside one second so a seconds-based EXPIREAT could not be set.
      while (redisNowMicros() % SECOND_MICROS > 800_000L) {
        Thread.sleep(20L);
      }
      long windowEndMicros = redisNowMicros() + 100_000L;
      String[] b = band(2, 100_000L, ALG_FIXED_WINDOW, 0, windowEndMicros);

      assertThat(consume(1, b, key).get(ALLOWED)).isEqualTo(1L);

      // -1 means "no TTL" (the bug); 0 or -2 just mean the window ended before PTTL ran.
      assertThat(pttl(key)).isNotEqualTo(-1L).isLessThanOrEqualTo(101L);
    }

    @Test
    @DisplayName("Sub-second window: an exhausted counter admits again once the window has passed")
    void subSecondWindowRollsOver() throws InterruptedException {
      String key = key("fw-100ms-rollover");
      // Derived (non-calendar) 100 ms windows: the counter records its window end, and the TTL
      // removes it once that window is over.
      String[] b = band(2, 100_000L, ALG_FIXED_WINDOW, 0, 0);

      boolean rejected = false;
      for (int i = 0; i < 10 && !rejected; i++) {
        rejected = consume(1, b, key).get(ALLOWED) == 0L;
      }
      assertThat(rejected).as("capacity 2 per 100 ms must reject within 10 calls").isTrue();
      // -1 means "no TTL" (the bug); 0 or -2 just mean the window ended before PTTL ran.
      assertThat(pttl(key)).isNotEqualTo(-1L).isLessThanOrEqualTo(101L);

      Thread.sleep(250L);

      assertThat(consume(1, b, key).get(ALLOWED)).isEqualTo(1L);
    }

    @Test
    @DisplayName("A window end in the past still leaves a live counter with a ~1 ms TTL")
    void pastWindowEndIsFlooredToNowPlusOneMilli() {
      String key = key("fw-past-window-end");
      // A calendar window end that has already passed (e.g. clock skew between Java and Redis).
      long windowEndMicros = redisNowMicros() - 5 * SECOND_MICROS;
      String[] b = band(5, SECOND_MICROS, ALG_FIXED_WINDOW, 0, windowEndMicros);

      // Read the counter in the same script invocation: Redis checks key expiry against the
      // script's start time, so the 1 ms TTL cannot elapse between the write and the reads.
      List<Long> result = consumeThenInspect(1, b, key);

      assertThat(result.get(0)).as("allowed").isEqualTo(1L);
      // without the floor, PEXPIREAT in the past deletes the key at once (GET nil -> -1)
      assertThat(result.get(1)).as("counter value").isEqualTo(1L);
      // the floor is now + 1 ms; PTTL is measured from the script's (millisecond) start time, which
      // can trail the TIME call by a rounding step, so allow a few ms rather than exactly 1
      assertThat(result.get(2)).as("PTTL").isNotEqualTo(-1L).isBetween(1L, 5L);
    }

    @Test
    @DisplayName("A legacy counter without TTL gets one on the reject path")
    void rejectRepairsMissingTtl() {
      String key = key("fw-legacy-no-ttl");
      String[] b = band(2, 100_000L, ALG_FIXED_WINDOW, 0, 0);
      // A full counter of the current derived window, but without any TTL.
      long windowEnd = (redisNowMicros() / 100_000L + 1) * 100_000L;
      writeCounter(key, 5, windowEnd, -1L);

      assertThat(consume(1, b, key).get(ALLOWED)).isZero();

      // -1 means "no TTL" (the bug); 0 or -2 just mean the window ended before PTTL ran.
      assertThat(pttl(key)).isNotEqualTo(-1L).isLessThanOrEqualTo(101L);
    }

    @Test
    @DisplayName("Calendar rollover: a new window_end starts a fresh counter")
    void calendarRollover() {
      // Simulate rolling over: the first call uses a window_end 1 second from now,
      // the second call uses a different window_end further in the future.
      // Since the first key expires (EXPIREAT), the second call sees count=0.
      long now = System.currentTimeMillis();
      long firstWindowEnd = (now + 1_000L) * 1000L; // 1 s from now
      long secondWindowEnd = (now + 60_000L) * 1000L; // 60 s from now

      String keyFirst = key("fw-rollover-w1");
      String keySecond = key("fw-rollover-w2");

      String[] bFirst = band(2, HOUR_MICROS, ALG_FIXED_WINDOW, 0, firstWindowEnd);
      String[] bSecond = band(2, HOUR_MICROS, ALG_FIXED_WINDOW, 0, secondWindowEnd);

      // Fill the first window.
      consume(1, bFirst, keyFirst);
      consume(1, bFirst, keyFirst);
      assertThat(consume(1, bFirst, keyFirst).get(ALLOWED)).isZero(); // full

      // A key for the second window is independent: count starts at 0.
      assertThat(consume(1, bSecond, keySecond).get(ALLOWED)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Rejected request does not increment the counter")
    void rejectDoesNotIncrementCounter() {
      String key = key("fw-reject-nomut");
      long windowEnd = (System.currentTimeMillis() + 10_000L) * 1000L;
      String[] b = band(2, HOUR_MICROS, ALG_FIXED_WINDOW, 0, windowEnd);

      consume(1, b, key);
      consume(1, b, key); // now at capacity=2

      // Rejected request must not change the counter.
      List<Long> rejected = consume(1, b, key);
      assertThat(rejected.get(ALLOWED)).isZero();
      assertThat(rejected.get(MIN_REMAINING)).isZero();

      // A second rejected request also sees remaining=0, proving the first rejection did not
      // increment the counter (which would have freed tokens and allowed this one).
      List<Long> rejected2 = consume(1, b, key);
      assertThat(rejected2.get(ALLOWED)).isZero();
      assertThat(rejected2.get(MIN_REMAINING)).isZero();
    }

    @Test
    @DisplayName("Boundary: a previous window's counter still visible to Redis is not carried over")
    void previousDerivedWindowCounterIsNotCarriedOver() {
      String key = key("fw-boundary-derived");
      String[] b = band(3, MINUTE_MICROS, ALG_FIXED_WINDOW, 0, 0);
      // The exact-expiry millisecond: Redis treats a key as expired only when now > when, so a
      // full counter of the window that just ended can still be read. Pin that state
      // deterministically with a long TTL instead of racing the clock.
      long previousWindowEnd = (redisNowMicros() / MINUTE_MICROS) * MINUTE_MICROS;
      writeCounter(key, 3, previousWindowEnd, 60_000L);

      List<Long> result = consume(1, b, key);

      assertThat(result.get(ALLOWED)).as("new window starts empty").isEqualTo(1L);
      assertThat(result.get(MIN_REMAINING)).isEqualTo(2L);
      assertThat(counter(key)).isEqualTo(1L);
      assertThat(storedWindowEnd(key)).isEqualTo(previousWindowEnd + MINUTE_MICROS);
      // the counter now expires with the new window, not one window later
      assertThat(pttl(key)).isBetween(1L, MINUTE_MICROS / 1000L);
    }

    @Test
    @DisplayName("Calendar skew: yesterday's count is not carried into today")
    void calendarCounterOfAnEndedPeriodIsNotCarriedOver() {
      String key = key("fw-calendar-ahead");
      long now = redisNowMicros();
      long yesterdayEnd = now + SECOND_MICROS; // Redis clock: the old period is still running
      long todayEnd = yesterdayEnd + 24 * HOUR_MICROS; // app clock already past midnight
      writeCounter(key, 3, yesterdayEnd, 60_000L);

      List<Long> result = consume(1, band(3, 24 * HOUR_MICROS, ALG_FIXED_WINDOW, 0, todayEnd), key);

      assertThat(result.get(ALLOWED)).isEqualTo(1L);
      assertThat(counter(key)).isEqualTo(1L);
      assertThat(storedWindowEnd(key)).isEqualTo(todayEnd);
      assertThat(result.get(RESET_TIME_MILLIS)).isEqualTo(todayEnd / 1000L);
    }

    @Test
    @DisplayName("Calendar skew: a node whose clock lags does not reset the newer period")
    void laggingNodeKeepsTheNewerPeriod() {
      String key = key("fw-calendar-behind");
      long now = redisNowMicros();
      long yesterdayEnd = now + SECOND_MICROS;
      long todayEnd = yesterdayEnd + 24 * HOUR_MICROS;
      writeCounter(key, 3, todayEnd, 60_000L);

      // a node still on "yesterday" must not wipe today's counter and admit again
      List<Long> result =
          consume(1, band(3, 24 * HOUR_MICROS, ALG_FIXED_WINDOW, 0, yesterdayEnd), key);

      assertThat(result.get(ALLOWED)).isZero();
      assertThat(result.get(RESET_TIME_MILLIS)).isEqualTo(todayEnd / 1000L);
      assertThat(counter(key)).isEqualTo(3L);
      assertThat(storedWindowEnd(key)).isEqualTo(todayEnd);
    }

    @Test
    @DisplayName("reset_time_millis matches the supplied window_end")
    void resetTimeMatchesWindowEnd() {
      long windowEndMs = System.currentTimeMillis() + 30_000L;
      long windowEndMicros = windowEndMs * 1000L;
      String key = key("fw-reset");
      String[] b = band(5, MINUTE_MICROS, ALG_FIXED_WINDOW, 0, windowEndMicros);

      List<Long> result = consume(1, b, key);
      assertThat(result.get(ALLOWED)).isEqualTo(1L);
      // reset_time_millis = floor(window_end_micros / 1000)
      assertThat(result.get(RESET_TIME_MILLIS)).isEqualTo(windowEndMs);
    }
  }

  // =========================================================================
  // Mixed-algorithm all-or-nothing atomicity
  // =========================================================================

  @Nested
  @DisplayName("Mixed-algorithm single rule")
  class MixedAlgorithm {

    @Test
    @DisplayName("Rejection by FIXED_WINDOW band does not mutate the TOKEN_BUCKET band")
    void fwRejectDoesNotDrainTb() {
      String keyTb = key("mix-tb");
      String keyFw = key("mix-fw");

      long windowEnd = (System.currentTimeMillis() + 10_000L) * 1000L;

      // TOKEN_BUCKET band: capacity=10, FIXED_WINDOW band: capacity=1
      String[] tbBand = band(10, MINUTE_MICROS, ALG_TOKEN_BUCKET, 0, 0);
      String[] fwBand = band(1, MINUTE_MICROS, ALG_FIXED_WINDOW, 0, windowEnd);
      String[] bothBands = bands(tbBand, fwBand);

      // First request: both bands allow.
      List<Long> first = consume(1, bothBands, keyTb, keyFw);
      assertThat(first.get(ALLOWED)).isEqualTo(1L);

      // Second request: FW band is full; TB band must NOT have lost a token.
      List<Long> rejected = consume(1, bothBands, keyTb, keyFw);
      assertThat(rejected.get(ALLOWED)).isZero();
      assertThat(rejected.get(REJECTING_BAND)).isEqualTo(2L); // FW is band 2

      // TB bucket should still have 9 tokens (only one was consumed by the first request).
      Map<String, String> tbHash = redis.hgetall(keyTb);
      assertThat(Long.parseLong(tbHash.get("tokens"))).isEqualTo(9L);
    }

    @Test
    @DisplayName("Rejection by SLIDING_WINDOW band does not mutate the TOKEN_BUCKET band")
    void swRejectDoesNotDrainTb() {
      String keyTb = key("mix-sw-tb");
      String keySw = key("mix-sw-sw");

      String[] tbBand = band(10, MINUTE_MICROS, ALG_TOKEN_BUCKET, 0, 0);
      String[] swBand = band(1, MINUTE_MICROS, ALG_SLIDING_WINDOW, 2, 0);
      String[] both = bands(tbBand, swBand);

      // First request: both allow.
      assertThat(consume(1, both, keyTb, keySw).get(ALLOWED)).isEqualTo(1L);

      // Second request: SW full.
      List<Long> rejected = consume(1, both, keyTb, keySw);
      assertThat(rejected.get(ALLOWED)).isZero();
      assertThat(rejected.get(REJECTING_BAND)).isEqualTo(2L);

      // TB must still have 9 tokens.
      assertThat(Long.parseLong(redis.hgetall(keyTb).get("tokens"))).isEqualTo(9L);
    }

    @Test
    @DisplayName("All-pass multi-algorithm: both bands are consumed together")
    void allPassMultiAlgorithm() {
      String keyTb = key("mix-pass-tb");
      String keySw = key("mix-pass-sw");

      String[] tbBand = band(5, MINUTE_MICROS, ALG_TOKEN_BUCKET, 0, 0);
      String[] swBand = band(5, MINUTE_MICROS, ALG_SLIDING_WINDOW, 5, 0);
      String[] both = bands(tbBand, swBand);

      List<Long> result = consume(2, both, keyTb, keySw);
      assertThat(result.get(ALLOWED)).isEqualTo(1L);

      // Both buckets should reflect the consumption.
      assertThat(Long.parseLong(redis.hgetall(keyTb).get("tokens"))).isEqualTo(3L);
      // SW: sum = 2, remaining = 5 - 2 = 3
      assertThat(result.get(MIN_REMAINING)).isEqualTo(3L);
    }
  }

  // =========================================================================
  // Error replies
  // =========================================================================

  @Nested
  @DisplayName("Error replies")
  class Errors {

    @Test
    @DisplayName("Unknown algorithm code returns error")
    void unknownAlgorithmCode() {
      String key = key("err-alg");
      String[] b = band(10, MINUTE_MICROS, 99, 0, 0); // code 99 is invalid
      assertThatThrownBy(() -> consume(1, b, key))
          .isInstanceOf(RedisCommandExecutionException.class)
          .hasMessageContaining("unknown algorithm code");
    }

    @Test
    @DisplayName("SLIDING_WINDOW with buckets < 2 returns error")
    void slidingWindowTooFewBuckets() {
      String key = key("err-sw-buckets");
      String[] b = band(10, MINUTE_MICROS, ALG_SLIDING_WINDOW, 1, 0); // < 2 buckets
      assertThatThrownBy(() -> consume(1, b, key))
          .isInstanceOf(RedisCommandExecutionException.class)
          .hasMessageContaining("buckets must be >= 2");
    }

    @Test
    @DisplayName("ARGV count mismatch returns error")
    void argvLengthMismatch() {
      String key = key("err-argv");
      // manually craft wrong arg count: 2 + 4 values (should be 2 + 5)
      String[] args = {
        "1", String.valueOf(DEFAULT_MAX_TTL_SECONDS), "10", String.valueOf(MINUTE_MICROS), "1", "0"
      }; // only 4 band args, not 5
      assertThatThrownBy(
              () -> redis.eval(scripts.getTokenBucketConsumeScript(), new String[] {key}, args))
          .isInstanceOf(RedisCommandExecutionException.class)
          .hasMessageContaining("expected");
    }
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  /** Builds a 5-element per-band arg array for the new ARGV layout. */
  private static String[] band(
      long capacity, long windowMicros, int algCode, int buckets, long windowEnd) {
    return new String[] {
      String.valueOf(capacity),
      String.valueOf(windowMicros),
      String.valueOf(algCode),
      String.valueOf(buckets),
      String.valueOf(windowEnd)
    };
  }

  private static String[] bands(String[]... bands) {
    String[] flat = new String[bands.length * 5];
    for (int i = 0; i < bands.length; i++) {
      System.arraycopy(bands[i], 0, flat, i * 5, 5);
    }
    return flat;
  }

  private static List<Long> consume(long permits, String[] bandArgs, String... keys) {
    return consume(permits, DEFAULT_MAX_TTL_SECONDS, bandArgs, keys);
  }

  private static List<Long> consume(
      long permits, long maxTtlSeconds, String[] bandArgs, String... keys) {
    // ARGV[1]=permits, ARGV[2]=max_bucket_ttl_seconds, then 5 values per band
    String[] args = new String[2 + bandArgs.length];
    args[0] = String.valueOf(permits);
    args[1] = String.valueOf(maxTtlSeconds);
    System.arraycopy(bandArgs, 0, args, 2, bandArgs.length);
    return redis.eval(scripts.getTokenBucketConsumeScript(), keys, args);
  }

  /**
   * Runs the consume script and, in the same script invocation, reads the first key's counter
   * ({@code HGET count}, -1 when missing) and {@code PTTL}. Returns {allowed, counter, pttl}.
   */
  private static List<Long> consumeThenInspect(long permits, String[] bandArgs, String key) {
    String[] args = new String[2 + bandArgs.length];
    args[0] = String.valueOf(permits);
    args[1] = String.valueOf(DEFAULT_MAX_TTL_SECONDS);
    System.arraycopy(bandArgs, 0, args, 2, bandArgs.length);
    String wrapper =
        "local consume = function()\n"
            + scripts.getTokenBucketConsumeScript()
            + "\nend\n"
            + "local r = consume()\n"
            + "return {r[1], tonumber(redis.call('HGET', KEYS[1], 'count') or -1),"
            + " redis.call('PTTL', KEYS[1])}";
    return redis.eval(wrapper, new String[] {key}, args);
  }

  /** Writes a FIXED_WINDOW counter hash; {@code pexpireMillis < 0} leaves it without a TTL. */
  private static void writeCounter(
      String key, long count, long windowEndMicros, long pexpireMillis) {
    redis.eval(
        "redis.call('HSET', KEYS[1], 'count', ARGV[1], 'window_end_micros', ARGV[2])\n"
            + "if tonumber(ARGV[3]) > 0 then redis.call('PEXPIRE', KEYS[1], ARGV[3]) end\n"
            + "return {1}",
        new String[] {key},
        new String[] {
          String.valueOf(count), String.valueOf(windowEndMicros), String.valueOf(pexpireMillis)
        });
  }

  private static long counter(String key) {
    return Long.parseLong(redis.hgetall(key).get("count"));
  }

  private static long storedWindowEnd(String key) {
    return Long.parseLong(redis.hgetall(key).get("window_end_micros"));
  }

  private static long redisNowMicros() {
    List<Long> time =
        redis.eval(
            "local t = redis.call('TIME') return {tonumber(t[1]), tonumber(t[2])}",
            new String[0],
            new String[0]);
    return time.get(0) * SECOND_MICROS + time.get(1);
  }

  private static long pttl(String key) {
    List<Long> result =
        redis.eval("return {redis.call('PTTL', KEYS[1])}", new String[] {key}, new String[0]);
    return result.get(0);
  }

  private static String key(String name) {
    return RedisContainerSupport.KEY_PREFIX + "bucket:{multi-alg-" + RUN_ID + "}:" + name;
  }

  private static String keyPattern() {
    return RedisContainerSupport.KEY_PREFIX + "bucket:{multi-alg-" + RUN_ID + "}:*";
  }
}
