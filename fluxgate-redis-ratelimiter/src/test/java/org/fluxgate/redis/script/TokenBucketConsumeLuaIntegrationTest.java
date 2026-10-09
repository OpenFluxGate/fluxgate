package org.fluxgate.redis.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.RedisCommandExecutionException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.connection.StandaloneRedisConnection;
import org.fluxgate.redis.support.RedisContainerSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Integration tests for {@code token_bucket_consume.lua} executed against a real Redis.
 *
 * <p>These drive the script directly rather than through {@link
 * org.fluxgate.redis.store.RedisTokenBucketStore}, so that the raw ARGV/return contract and the
 * error replies are covered - including the cases the Java layer normally prevents.
 *
 * <p>The target comes from {@link RedisContainerSupport}: a supplied {@code FLUXGATE_REDIS_URI}, a
 * Testcontainers {@code redis:7-alpine}, or the test is skipped.
 */
class TokenBucketConsumeLuaIntegrationTest {

  private static final String RUN_ID = RedisContainerSupport.newRunId();
  private static final long SECOND_MICROS = 1_000_000L;
  private static final long MINUTE_MICROS = 60 * SECOND_MICROS;

  /** Bucket TTL cap used unless a test passes its own: the production default of 7 days. */
  private static final long DEFAULT_MAX_TTL_SECONDS = Duration.ofDays(7).getSeconds();

  // Return slots of the script
  private static final int ALLOWED = 0;
  private static final int REJECTING_BAND = 1;
  private static final int MIN_REMAINING = 2;
  private static final int MICROS_TO_WAIT = 3;
  private static final int RESET_TIME_MILLIS = 4;
  private static final int LIMIT = 5;
  private static final int BINDING_BAND = 6;

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

  // ===== Single band =====

  @Test
  @DisplayName("A single band allows while it has tokens and then rejects")
  void shouldAllowThenRejectASingleBand() {
    String key = key("single");

    List<Long> first = consume(1, band(10, MINUTE_MICROS), key);
    assertThat(first.get(ALLOWED)).isEqualTo(1L);
    assertThat(first.get(REJECTING_BAND)).isZero();
    assertThat(first.get(MIN_REMAINING)).isEqualTo(9L);
    assertThat(first.get(MICROS_TO_WAIT)).isZero();
    assertThat(first.get(LIMIT)).isEqualTo(10L);
    assertThat(first.get(BINDING_BAND)).isEqualTo(1L);

    // Drain the remaining nine tokens
    for (int i = 0; i < 9; i++) {
      assertThat(consume(1, band(10, MINUTE_MICROS), key).get(ALLOWED)).isEqualTo(1L);
    }

    List<Long> rejected = consume(1, band(10, MINUTE_MICROS), key);
    assertThat(rejected.get(ALLOWED)).isZero();
    assertThat(rejected.get(REJECTING_BAND)).isEqualTo(1L);
    assertThat(rejected.get(BINDING_BAND)).isEqualTo(1L);
    assertThat(rejected.get(MIN_REMAINING)).isZero();
    assertThat(rejected.get(MICROS_TO_WAIT)).isPositive();
    assertThat(rejected.get(LIMIT)).isEqualTo(10L);
  }

  @ParameterizedTest
  @CsvSource({"1, 9", "3, 7", "10, 0"})
  @DisplayName("Remaining tokens reflect the permits actually taken")
  void shouldReportRemainingAfterConsumption(long permits, long expectedRemaining) {
    String key = key("permits-" + permits);

    List<Long> result = consume(permits, band(10, MINUTE_MICROS), key);

    assertThat(result.get(ALLOWED)).isEqualTo(1L);
    assertThat(result.get(MIN_REMAINING)).isEqualTo(expectedRemaining);
    assertThat(tokensOf(key)).isEqualTo(expectedRemaining);
  }

  // ===== Multi band: the all-or-nothing regression =====

  @Test
  @DisplayName("A request rejected by the slow band does not drain the fast band (H8/H-17)")
  void rejectedRequestsShouldNotDrainTheOtherBand() {
    // Band A: 10 per second. Band B: 2 per minute.
    String fast = key("fast");
    String slow = key("slow");
    String[] bands = bands(band(10, SECOND_MICROS), band(2, MINUTE_MICROS));

    // Two requests get through; band B is now exhausted for the next minute.
    assertThat(consume(1, bands, fast, slow).get(ALLOWED)).isEqualTo(1L);
    assertThat(consume(1, bands, fast, slow).get(ALLOWED)).isEqualTo(1L);

    long fastTokensBefore = tokensOf(fast);
    assertThat(fastTokensBefore).isPositive();
    assertThat(tokensOf(slow)).isZero();

    // Five rejected requests in a row: the fast band must not lose a single token.
    for (int i = 0; i < 5; i++) {
      List<Long> rejected = consume(1, bands, fast, slow);
      assertThat(rejected.get(ALLOWED)).isZero();
      assertThat(rejected.get(REJECTING_BAND)).isEqualTo(2L);
      assertThat(rejected.get(BINDING_BAND)).isEqualTo(2L);
      assertThat(rejected.get(LIMIT)).isEqualTo(2L);
      assertThat(rejected.get(MIN_REMAINING)).isZero();
      assertThat(rejected.get(MICROS_TO_WAIT)).isPositive();
    }

    assertThat(tokensOf(fast))
        .as("the fast band keeps its tokens while the slow band rejects")
        .isEqualTo(fastTokensBefore);
  }

  @Test
  @DisplayName("The binding band on allow is the one with the fewest tokens left")
  void shouldReportTheMostRestrictiveBandOnAllow() {
    String fast = key("binding-fast");
    String slow = key("binding-slow");
    String[] bands = bands(band(10, SECOND_MICROS), band(5, MINUTE_MICROS));

    List<Long> result = consume(1, bands, fast, slow);

    assertThat(result.get(ALLOWED)).isEqualTo(1L);
    assertThat(result.get(BINDING_BAND)).isEqualTo(2L);
    assertThat(result.get(MIN_REMAINING)).isEqualTo(4L);
    assertThat(result.get(LIMIT)).isEqualTo(5L);
  }

  @Test
  @DisplayName("A band that was never written is not created by a rejected request")
  void shouldNotCreateTheOtherBucketOnRejection() {
    String fast = key("untouched-fast");
    String slow = key("untouched-slow");

    // Exhaust the slow band on its own first
    assertThat(consume(1, band(1, MINUTE_MICROS), slow).get(ALLOWED)).isEqualTo(1L);

    // Now ask for both bands: the slow one rejects, so the fast bucket is never created
    List<Long> rejected =
        consume(1, bands(band(10, SECOND_MICROS), band(1, MINUTE_MICROS)), fast, slow);

    assertThat(rejected.get(ALLOWED)).isZero();
    assertThat(redis.exists(fast)).isFalse();
  }

  // ===== Errors =====

  @Test
  @DisplayName("permits above the capacity is an error, not an unreachable wait time")
  void shouldFailWhenPermitsExceedCapacity() {
    String key = key("too-many");

    assertThatThrownBy(() -> consume(11, band(10, SECOND_MICROS), key))
        .isInstanceOf(RedisCommandExecutionException.class)
        .hasMessageContaining("permits exceed capacity");
  }

  @Test
  @DisplayName("Malformed arguments are rejected before anything is written")
  void shouldValidateArguments() {
    String key = key("bad-args");

    assertThatThrownBy(() -> eval(new String[] {key}, new String[] {"1", "10"}))
        .isInstanceOf(RedisCommandExecutionException.class)
        .hasMessageContaining("arguments");

    assertThatThrownBy(() -> consume(0, band(10, SECOND_MICROS), key))
        .isInstanceOf(RedisCommandExecutionException.class)
        .hasMessageContaining("permits must be positive");

    assertThatThrownBy(() -> consume(1, band(10, 0), key))
        .isInstanceOf(RedisCommandExecutionException.class)
        .hasMessageContaining("window must be positive");

    assertThat(redis.exists(key)).isFalse();
  }

  // ===== Reset time =====

  @Test
  @DisplayName("reset_time is computed after consumption, not before (H-5)")
  void shouldComputeResetTimeAfterConsumption() {
    String key = key("reset");
    long capacity = 10;
    long windowMicros = MINUTE_MICROS;

    long before = System.currentTimeMillis();
    List<Long> result = consume(4, band(capacity, windowMicros), key);
    long after = System.currentTimeMillis();

    // Six tokens left of ten, so four tokens (4/10 of the window = 24s) are still missing.
    long deficitMillis = 4L * windowMicros / capacity / 1000L;
    assertThat(result.get(MIN_REMAINING)).isEqualTo(6L);
    assertThat(result.get(RESET_TIME_MILLIS))
        .as("reset time must cover the permits this request took")
        .isBetween(before + deficitMillis - 1_000L, after + deficitMillis + 1_000L);
  }

  @Test
  @DisplayName("A full bucket resets immediately after an allowed request refills it")
  void shouldReportAnImmediateResetWhenTheBucketIsFull() {
    String key = key("reset-full");

    // capacity 1 with a 1ms window: by the time we read it back the bucket has refilled
    List<Long> result = consume(1, band(1, 1_000L), key);

    assertThat(result.get(ALLOWED)).isEqualTo(1L);
    assertThat(result.get(RESET_TIME_MILLIS)).isLessThanOrEqualTo(System.currentTimeMillis() + 50);
  }

  @Test
  @DisplayName("On rejection reset_time is now plus the wait, matching Bucket4j")
  void shouldReportResetTimeAsNowPlusWaitOnRejection() {
    String key = key("reset-reject");

    consume(1, band(1, MINUTE_MICROS), key);
    long before = System.currentTimeMillis();
    List<Long> rejected = consume(1, band(1, MINUTE_MICROS), key);

    long waitMillis = rejected.get(MICROS_TO_WAIT) / 1000L;
    assertThat(rejected.get(ALLOWED)).isZero();
    assertThat(rejected.get(RESET_TIME_MILLIS))
        .isBetween(before + waitMillis - 1_000L, System.currentTimeMillis() + waitMillis + 1_000L);
  }

  // ===== TTL =====

  @Test
  @DisplayName("TTL is the window plus a 10% margin")
  void shouldSetTtlWithSafetyMargin() {
    String key = key("ttl-60s");

    consume(1, band(100, MINUTE_MICROS), key);

    assertThat(redis.ttl(key)).isBetween(60L, 66L);
  }

  @Test
  @DisplayName("A window longer than a day keeps its own TTL - no 24h cap (H9)")
  void shouldNotCapTheTtlAtOneDay() {
    String key = key("ttl-7d");
    long sevenDaysMicros = Duration.ofDays(7).getSeconds() * SECOND_MICROS;
    long thirtyDaysSeconds = Duration.ofDays(30).getSeconds();

    consume(1, thirtyDaysSeconds, band(1000, sevenDaysMicros), key);

    // ceil(604800 * 1.1) = 665280, well past the old 86400 cap
    assertThat(redis.ttl(key)).isBetween(665_270L, 665_280L);
  }

  @Test
  @DisplayName("The TTL never exceeds the cap passed as the last ARGV (N-14)")
  void shouldCapTheTtlAtTheConfiguredMaximum() {
    String key = key("ttl-capped");
    long thirtyDaysMicros = Duration.ofDays(30).getSeconds() * SECOND_MICROS;

    consume(1, 3600L, band(1000, thirtyDaysMicros), key);

    // Without the cap this bucket would live 33 days: one Redis hash per forged identity key
    assertThat(redis.ttl(key)).isBetween(3_595L, 3_600L);
  }

  @Test
  @DisplayName("The cap applies to the TTL refresh a rejected request performs as well")
  void shouldCapTheTtlOnRejectionToo() {
    String key = key("ttl-capped-reject");
    long thirtyDaysMicros = Duration.ofDays(30).getSeconds() * SECOND_MICROS;
    String[] band = band(1, thirtyDaysMicros);

    consume(1, 120L, band, key);
    List<Long> rejected = consume(1, 120L, band, key);

    assertThat(rejected.get(ALLOWED)).isZero();
    assertThat(redis.ttl(key)).isBetween(115L, 120L);
  }

  @Test
  @DisplayName("A missing or sub-second TTL cap is refused rather than defaulted silently")
  void shouldRejectAnInvalidTtlCap() {
    String key = key("ttl-bad-cap");

    assertThatThrownBy(() -> consume(1, 0L, band(10, MINUTE_MICROS), key))
        .isInstanceOf(RedisCommandExecutionException.class)
        .hasMessageContaining("max bucket ttl");

    assertThat(redis.exists(key)).isFalse();
  }

  @Test
  @DisplayName("A short window still gets at least one second of TTL")
  void shouldNeverSetATtlBelowOneSecond() {
    String key = key("ttl-1ms");

    consume(1, band(10, 1_000L), key);

    assertThat(redis.ttl(key)).isEqualTo(1L);
  }

  @Test
  @DisplayName("A rejected request refreshes the TTL of the buckets that exist")
  void shouldRefreshTtlOnRejection() {
    String key = key("ttl-reject");
    String[] band = band(1, MINUTE_MICROS);

    consume(1, band, key);
    // Age the key artificially: a rejected-only bucket must not keep the old, shrinking TTL
    redis.eval(
        "return redis.call('EXPIRE', KEYS[1], ARGV[1])", new String[] {key}, new String[] {"5"});
    assertThat(redis.ttl(key)).isLessThanOrEqualTo(5L);

    assertThat(consume(1, band, key).get(ALLOWED)).isZero();

    assertThat(redis.ttl(key)).isBetween(60L, 66L);
  }

  // ===== Upgrade path =====

  @Test
  @DisplayName("A 0.3.x bucket keyed on last_refill_nanos is re-initialised once")
  void shouldReinitialiseALegacyBucket() {
    String key = key("legacy");

    // What the 0.3.x script wrote: note the nanosecond field name and the %.14g formatting
    redis.hset(key, "tokens", "3");
    redis.hset(key, "last_refill_nanos", "1.7592186044416e+18");

    List<Long> result = consume(1, band(10, MINUTE_MICROS), key);

    // The stale hash counts as missing, so the bucket starts full: one quota reset on upgrade
    assertThat(result.get(ALLOWED)).isEqualTo(1L);
    assertThat(result.get(MIN_REMAINING)).isEqualTo(9L);

    Map<String, String> hash = redis.hgetall(key);
    assertThat(hash).containsKey("tokens").containsKey("last_refill_micros");
    // Microseconds are stored as plain digits, never as "1.76e+15"
    assertThat(hash.get("last_refill_micros")).matches("\\d+");
    assertThat(Long.parseLong(hash.get("last_refill_micros"))).isPositive();
  }

  @Test
  @DisplayName("Timestamps round-trip exactly through the hash")
  void shouldStoreTimestampsThatSurviveARoundTrip() {
    String key = key("roundtrip");

    consume(1, band(10, MINUTE_MICROS), key);
    long stored = Long.parseLong(redis.hgetall(key).get("last_refill_micros"));

    // Microseconds since the epoch are about 1.76e15, inside Lua's exact double range of 2^53
    assertThat(stored).isLessThan(1L << 53);
    assertThat(stored / 1000L)
        .isCloseTo(System.currentTimeMillis(), org.assertj.core.data.Offset.offset(60_000L));
  }

  // ===== NOSCRIPT recovery =====

  @Test
  @DisplayName("The store recovers from SCRIPT FLUSH through EVAL and reloads the script")
  void shouldRecoverFromScriptFlush() {
    String key = key("noscript");
    org.fluxgate.redis.store.RedisTokenBucketStore store = config.getTokenBucketStore();
    org.fluxgate.core.config.RateLimitBand band =
        org.fluxgate.core.config.RateLimitBand.builder(Duration.ofSeconds(60), 10)
            .label("noscript")
            .build();

    assertThat(store.tryConsume(key, band, 1).consumed()).isTrue();

    // Redis forgets every cached script, exactly as a restart would. SCRIPT is not callable from
    // inside a script, so this one needs the concrete Lettuce commands.
    Assumptions.assumeTrue(
        redis instanceof StandaloneRedisConnection, "SCRIPT FLUSH needs a standalone target");
    ((StandaloneRedisConnection) redis).getCommands().scriptFlush();

    assertThat(store.tryConsume(key, band, 1).consumed()).isTrue();
    // And the script is back in the cache, so the next call needs no fallback
    assertThat(store.tryConsume(key, band, 1).consumed()).isTrue();
    assertThat(tokensOf(key)).isEqualTo(7L);
  }

  // ===== Helpers =====

  /** TOKEN_BUCKET band (algorithm code 1, buckets=0, window_end=0). */
  private static String[] band(long capacity, long windowMicros) {
    return new String[] {String.valueOf(capacity), String.valueOf(windowMicros), "1", "0", "0"};
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
      long permits, long maxBucketTtlSeconds, String[] bandArgs, String... keys) {
    // ARGV[1]=permits, ARGV[2]=max_bucket_ttl_seconds, then 5 values per band
    String[] args = new String[2 + bandArgs.length];
    args[0] = String.valueOf(permits);
    args[1] = String.valueOf(maxBucketTtlSeconds);
    System.arraycopy(bandArgs, 0, args, 2, bandArgs.length);
    return eval(keys, args);
  }

  private static List<Long> eval(String[] keys, String[] args) {
    return redis.eval(scripts.getTokenBucketConsumeScript(), keys, args);
  }

  private static long tokensOf(String key) {
    return Long.parseLong(redis.hgetall(key).get("tokens"));
  }

  private static String key(String name) {
    return RedisContainerSupport.KEY_PREFIX + "bucket:{lua-" + RUN_ID + "}:" + name;
  }

  private static String keyPattern() {
    return RedisContainerSupport.KEY_PREFIX + "bucket:{lua-" + RUN_ID + "}:*";
  }
}
