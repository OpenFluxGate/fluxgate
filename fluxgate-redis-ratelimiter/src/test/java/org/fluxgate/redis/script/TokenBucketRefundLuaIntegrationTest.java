package org.fluxgate.redis.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.RedisCommandExecutionException;
import java.time.Duration;
import java.util.List;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.support.RedisContainerSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@code token_bucket_refund.lua}: each algorithm gets back what a
 * consumption took, never more, and nothing once the charged window or sub-bucket is gone.
 *
 * <p>The target comes from {@link RedisContainerSupport}: a supplied {@code FLUXGATE_REDIS_URI}, a
 * Testcontainers {@code redis:7-alpine}, or the test is skipped.
 */
class TokenBucketRefundLuaIntegrationTest {

  private static final String RUN_ID = RedisContainerSupport.newRunId();
  private static final long SECOND_MICROS = 1_000_000L;
  private static final long MINUTE_MICROS = 60 * SECOND_MICROS;
  private static final long HOUR_MICROS = 3600 * SECOND_MICROS;
  private static final long MAX_TTL_SECONDS = Duration.ofDays(7).getSeconds();

  private static final int ALG_TOKEN_BUCKET = 1;
  private static final int ALG_SLIDING_WINDOW = 2;
  private static final int ALG_FIXED_WINDOW = 3;

  /** Index of the Redis time in the consume script's result. */
  private static final int REDIS_TIME = 7;

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
      RedisContainerSupport.deleteKeys(
          redis, RedisContainerSupport.KEY_PREFIX + "bucket:{refund-" + RUN_ID + "}:*");
      config.close();
    }
  }

  @Test
  @DisplayName("The consume script reports the Redis time it decided at")
  void consumeReportsRedisTime() {
    String key = key("time");
    long before = redisNowMicros();

    List<Long> result = consume(1, band(5, MINUTE_MICROS, ALG_TOKEN_BUCKET, 0, 0), key);

    assertThat(result).hasSize(8);
    assertThat(result.get(REDIS_TIME)).isBetween(before, redisNowMicros());
  }

  @Test
  @DisplayName("TOKEN_BUCKET: the consumed token comes back")
  void tokenBucketRefund() {
    String key = key("tb");
    String[] b = band(5, HOUR_MICROS, ALG_TOKEN_BUCKET, 0, 0);
    List<Long> consumed = consume(2, b, key);
    assertThat(tokens(key)).isEqualTo(3L);

    List<Long> refunded = refund(2, consumed.get(REDIS_TIME), b, key);

    assertThat(refunded).containsExactly(2L);
    assertThat(tokens(key)).isEqualTo(5L);
  }

  @Test
  @DisplayName("TOKEN_BUCKET: a refund never lifts a bucket above its capacity")
  void tokenBucketRefundIsCappedAtCapacity() {
    String key = key("tb-cap");
    String[] b = band(5, HOUR_MICROS, ALG_TOKEN_BUCKET, 0, 0);
    List<Long> consumed = consume(1, b, key);
    // something else (e.g. a concurrent refund) already filled the bucket back up
    redis.hset(key, "tokens", "5");

    assertThat(refund(1, consumed.get(REDIS_TIME), b, key)).containsExactly(0L);
    assertThat(tokens(key)).isEqualTo(5L);
  }

  @Test
  @DisplayName("TOKEN_BUCKET: a missing bucket is not created")
  void tokenBucketRefundOfAMissingBucketIsANoop() {
    String key = key("tb-missing");

    assertThat(refund(1, redisNowMicros(), band(5, HOUR_MICROS, ALG_TOKEN_BUCKET, 0, 0), key))
        .containsExactly(0L);
    assertThat(redis.exists(key)).isFalse();
  }

  @Test
  @DisplayName("SLIDING_WINDOW: the charged sub-bucket is decremented")
  void slidingWindowRefund() {
    String key = key("sw");
    String[] b = band(3, HOUR_MICROS, ALG_SLIDING_WINDOW, 6, 0);
    consume(1, b, key);
    List<Long> consumed = consume(1, b, key);

    assertThat(refund(1, consumed.get(REDIS_TIME), b, key)).containsExactly(1L);

    // 1 left in the window: 2 more fit, the third does not
    assertThat(consume(1, b, key).get(0)).isEqualTo(1L);
    assertThat(consume(1, b, key).get(0)).isEqualTo(1L);
    assertThat(consume(1, b, key).get(0)).isZero();
  }

  @Test
  @DisplayName("SLIDING_WINDOW: a sub-bucket other than the charged one is not touched")
  void slidingWindowRefundTargetsTheChargedSubBucket() {
    String key = key("sw-other-sub");
    long subDuration = HOUR_MICROS / 6;
    String[] b = band(3, HOUR_MICROS, ALG_SLIDING_WINDOW, 6, 0);
    List<Long> consumed = consume(1, b, key);

    // a consumption one sub-bucket earlier has nothing in that sub-bucket to refund
    assertThat(refund(1, consumed.get(REDIS_TIME) - subDuration, b, key)).containsExactly(0L);
    assertThat(redis.hgetall(key).values()).containsExactly("1");
  }

  @Test
  @DisplayName("FIXED_WINDOW: the counter of the charged window is decremented")
  void fixedWindowRefund() {
    String key = key("fw");
    long windowEnd = redisNowMicros() + MINUTE_MICROS;
    String[] b = band(3, HOUR_MICROS, ALG_FIXED_WINDOW, 0, windowEnd);
    consume(1, b, key);
    List<Long> consumed = consume(1, b, key);

    assertThat(refund(1, consumed.get(REDIS_TIME), b, key)).containsExactly(1L);
    assertThat(redis.hgetall(key).get("count")).isEqualTo("1");
  }

  @Test
  @DisplayName("FIXED_WINDOW: after a rollover there is nothing of the old window to refund")
  void fixedWindowRefundAfterRolloverIsANoop() {
    String key = key("fw-rollover");
    long now = redisNowMicros();
    String[] oldWindow = band(3, HOUR_MICROS, ALG_FIXED_WINDOW, 0, now + MINUTE_MICROS);
    String[] newWindow = band(3, HOUR_MICROS, ALG_FIXED_WINDOW, 0, now + 2 * MINUTE_MICROS);
    List<Long> consumed = consume(1, oldWindow, key);
    consume(1, newWindow, key); // the counter now counts the next window

    assertThat(refund(1, consumed.get(REDIS_TIME), oldWindow, key)).containsExactly(0L);
    assertThat(redis.hgetall(key).get("count")).isEqualTo("1");
  }

  @Test
  @DisplayName("FIXED_WINDOW: a derived window is identified from the consume time")
  void fixedWindowRefundOfADerivedWindow() {
    String key = key("fw-derived");
    String[] b = band(3, HOUR_MICROS, ALG_FIXED_WINDOW, 0, 0);
    List<Long> consumed = consume(1, b, key);

    assertThat(refund(1, consumed.get(REDIS_TIME), b, key)).containsExactly(1L);
    assertThat(redis.hgetall(key).get("count")).isEqualTo("0");
    // a consume time from the previous hour addresses another window
    consume(1, b, key);
    assertThat(refund(1, consumed.get(REDIS_TIME) - HOUR_MICROS, b, key)).containsExactly(0L);
  }

  @Test
  @DisplayName("Multi-band: every band of the rule is refunded in one call")
  void multiBandRefund() {
    String keyTb = key("multi-tb");
    String keySw = key("multi-sw");
    String keyFw = key("multi-fw");
    String[] bands =
        bands(
            band(5, HOUR_MICROS, ALG_TOKEN_BUCKET, 0, 0),
            band(5, HOUR_MICROS, ALG_SLIDING_WINDOW, 4, 0),
            band(5, HOUR_MICROS, ALG_FIXED_WINDOW, 0, 0));
    List<Long> consumed = consume(1, bands, keyTb, keySw, keyFw);

    assertThat(refund(1, consumed.get(REDIS_TIME), bands, keyTb, keySw, keyFw))
        .containsExactly(1L, 1L, 1L);
    assertThat(tokens(keyTb)).isEqualTo(5L);
    assertThat(redis.hgetall(keySw)).isEmpty();
    assertThat(redis.hgetall(keyFw).get("count")).isEqualTo("0");
  }

  @Test
  @DisplayName("An invalid later band refunds nothing, not even the valid bands before it")
  void invalidBandRefundsNothing() {
    String keyTb = key("partial-tb");
    String keyBad = key("partial-bad");
    String[] tbBand = band(5, HOUR_MICROS, ALG_TOKEN_BUCKET, 0, 0);
    List<Long> consumed = consume(1, tbBand, keyTb);
    assertThat(tokens(keyTb)).isEqualTo(4L);

    String[] withBadBand = bands(tbBand, band(5, HOUR_MICROS, 9, 0, 0));
    assertThatThrownBy(() -> refund(1, consumed.get(REDIS_TIME), withBadBand, keyTb, keyBad))
        .isInstanceOf(RedisCommandExecutionException.class)
        .hasMessageContaining("unknown algorithm code");
    String[] withBadSlidingBand = bands(tbBand, band(5, HOUR_MICROS, ALG_SLIDING_WINDOW, 1, 0));
    assertThatThrownBy(() -> refund(1, consumed.get(REDIS_TIME), withBadSlidingBand, keyTb, keyBad))
        .isInstanceOf(RedisCommandExecutionException.class)
        .hasMessageContaining("buckets must be >= 2");

    assertThat(tokens(keyTb)).as("the valid first band was not refunded").isEqualTo(4L);
    assertThat(redis.exists(keyBad)).isFalse();
  }

  @Test
  @DisplayName("An unknown consume time is rejected")
  void unknownConsumeTimeIsAnError() {
    String key = key("err");
    assertThatThrownBy(() -> refund(1, 0L, band(5, HOUR_MICROS, ALG_TOKEN_BUCKET, 0, 0), key))
        .isInstanceOf(RedisCommandExecutionException.class)
        .hasMessageContaining("consumed_at_micros");
  }

  @Test
  @DisplayName("A window below 1 ms is refused with the consume script's message")
  void tinyWindowIsAnError() {
    String key = key("err-tiny-window");
    assertThatThrownBy(
            () -> refund(1, redisNowMicros(), band(5, 999L, ALG_TOKEN_BUCKET, 0, 0), key))
        .isInstanceOf(RedisCommandExecutionException.class)
        .hasMessageContaining("window must be at least 1 ms");
  }

  @Test
  @DisplayName("A sliding sub-bucket below 1 ms is refused with the consume script's message")
  void tinySubBucketIsAnError() {
    String key = key("err-tiny-sub");
    // 10 ms over 20 sub-buckets: 500 us each
    assertThatThrownBy(
            () -> refund(1, redisNowMicros(), band(5, 10_000L, ALG_SLIDING_WINDOW, 20, 0), key))
        .isInstanceOf(RedisCommandExecutionException.class)
        .hasMessageContaining("sliding window sub-bucket must be at least 1 ms");
  }

  // ===== Helpers =====

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
    return redis.eval(
        scripts.getTokenBucketConsumeScript(), keys, args(permits, MAX_TTL_SECONDS, bandArgs));
  }

  private static List<Long> refund(
      long permits, long consumedAtMicros, String[] bandArgs, String... keys) {
    return redis.eval(
        scripts.getTokenBucketRefundScript(), keys, args(permits, consumedAtMicros, bandArgs));
  }

  private static String[] args(long first, long second, String[] bandArgs) {
    String[] args = new String[2 + bandArgs.length];
    args[0] = String.valueOf(first);
    args[1] = String.valueOf(second);
    System.arraycopy(bandArgs, 0, args, 2, bandArgs.length);
    return args;
  }

  private static long tokens(String key) {
    return Long.parseLong(redis.hgetall(key).get("tokens"));
  }

  private static long redisNowMicros() {
    List<Long> time =
        redis.eval(
            "local t = redis.call('TIME') return {tonumber(t[1]), tonumber(t[2])}",
            new String[0],
            new String[0]);
    return time.get(0) * SECOND_MICROS + time.get(1);
  }

  private static String key(String name) {
    return RedisContainerSupport.KEY_PREFIX + "bucket:{refund-" + RUN_ID + "}:" + name;
  }
}
