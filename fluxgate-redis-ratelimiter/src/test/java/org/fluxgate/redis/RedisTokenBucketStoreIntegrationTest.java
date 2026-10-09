package org.fluxgate.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.exception.InvalidRuleConfigException;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.store.BucketState;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.fluxgate.redis.support.RedisContainerSupport;
import org.junit.jupiter.api.*;

/**
 * Integration tests for {@link RedisTokenBucketStore} against a real Redis.
 *
 * <p>The target comes from {@link RedisContainerSupport}: a supplied {@code FLUXGATE_REDIS_URI}, a
 * Testcontainers {@code redis:7-alpine}, or the test is skipped. Bucket keys carry a run id so no
 * test ever observes a bucket from an earlier run, and only those keys are deleted afterwards.
 */
class RedisTokenBucketStoreIntegrationTest {

  private static final String RUN_ID = RedisContainerSupport.newRunId();

  private static RedisRateLimiterConfig config;
  private static RedisTokenBucketStore store;
  private static RedisConnectionProvider connectionProvider;

  @BeforeAll
  static void setUp() {
    config = new RedisRateLimiterConfig(RedisContainerSupport.redisUri());
    store = config.getTokenBucketStore();
    connectionProvider = config.getConnectionProvider();
  }

  @AfterAll
  static void tearDown() {
    if (config != null) {
      RedisContainerSupport.deleteKeys(connectionProvider, keyPattern());
      config.close();
    }
  }

  @Test
  @DisplayName("Should consume tokens successfully when available")
  void shouldConsumeWhenAvailable() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 10).label("test").build();

    String bucketKey = bucketKey("consume");

    // when
    BucketState state = store.tryConsume(bucketKey, band, 3);

    // then
    assertThat(state.consumed()).isTrue();
    assertThat(state.remainingTokens()).isEqualTo(7); // 10 - 3 = 7
    assertThat(state.nanosToWaitForRefill()).isEqualTo(0);
    assertThat(state.limit()).isEqualTo(10);
    assertThat(state.bandIndex()).isZero();
    assertThat(state.resetTimeMillis()).isGreaterThan(System.currentTimeMillis());
  }

  @Test
  @DisplayName("Should reject when not enough tokens")
  void shouldRejectWhenNotEnoughTokens() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 5).label("test").build();

    String bucketKey = bucketKey("reject");

    // when: consume all tokens
    store.tryConsume(bucketKey, band, 5);

    // then: next request should be rejected, and report the rejecting band
    BucketState rejectedState = store.tryConsume(bucketKey, band, 1);
    assertThat(rejectedState.consumed()).isFalse();
    assertThat(rejectedState.remainingTokens()).isEqualTo(0);
    assertThat(rejectedState.nanosToWaitForRefill()).isGreaterThan(0);
    assertThat(rejectedState.limit()).isEqualTo(5);
    assertThat(rejectedState.bandIndex()).isZero();
  }

  @Test
  @DisplayName("Should refill tokens over time")
  void shouldRefillOverTime() throws InterruptedException {
    // given: 10 tokens per 100ms = refills quickly
    RateLimitBand band = RateLimitBand.builder(Duration.ofMillis(100), 10).label("test").build();

    String bucketKey = bucketKey("refill");

    // when: consume all tokens
    BucketState state1 = store.tryConsume(bucketKey, band, 10);
    assertThat(state1.consumed()).isTrue();
    assertThat(state1.remainingTokens()).isEqualTo(0);

    // wait for refill
    Thread.sleep(150); // Wait longer than window

    // then: tokens should be refilled
    BucketState state2 = store.tryConsume(bucketKey, band, 5);
    assertThat(state2.consumed()).isTrue();
    assertThat(state2.remainingTokens()).isGreaterThanOrEqualTo(0);
  }

  @Test
  @DisplayName("Should set TTL on bucket keys")
  void shouldSetTtl() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    String bucketKey = bucketKey("ttl");

    // when
    store.tryConsume(bucketKey, band, 1);

    // then: key should have TTL set
    // TTL includes 10% safety margin (60 * 1.1 = 66 seconds)
    Long ttl = connectionProvider.ttl(bucketKey);
    assertThat(ttl).isGreaterThan(0);
    assertThat(ttl).isLessThanOrEqualTo(66); // 60 seconds + 10% safety margin
  }

  @Test
  @DisplayName("All bands of a rule are charged together or not at all")
  void shouldChargeAllBandsAtomically() {
    // given: a fast band with plenty of tokens and a slow band that only allows two requests
    RateLimitBand fast = RateLimitBand.builder(Duration.ofSeconds(1), 10).label("fast").build();
    RateLimitBand slow = RateLimitBand.builder(Duration.ofMinutes(1), 2).label("slow").build();
    List<RateLimitBand> bands = List.of(fast, slow);
    List<String> keys = List.of(bucketKey("multi-fast"), bucketKey("multi-slow"));

    // when: the slow band is exhausted
    assertThat(store.tryConsume(keys, bands, 1).consumed()).isTrue();
    assertThat(store.tryConsume(keys, bands, 1).consumed()).isTrue();

    long fastTokensBefore = tokens(keys.get(0));

    // then: rejected requests leave the fast band's tokens exactly where they were
    for (int i = 0; i < 5; i++) {
      BucketState rejected = store.tryConsume(keys, bands, 1);
      assertThat(rejected.consumed()).isFalse();
      assertThat(rejected.bandIndex()).isEqualTo(1);
      assertThat(rejected.limit()).isEqualTo(2);
    }

    assertThat(tokens(keys.get(0))).isEqualTo(fastTokensBefore);
  }

  @Test
  @DisplayName("The binding band on allow is the one with the fewest tokens left")
  void shouldReportTheBindingBandOnAllow() {
    RateLimitBand fast = RateLimitBand.builder(Duration.ofSeconds(1), 10).label("fast").build();
    RateLimitBand slow = RateLimitBand.builder(Duration.ofMinutes(1), 5).label("slow").build();

    BucketState state =
        store.tryConsume(
            List.of(bucketKey("binding-fast"), bucketKey("binding-slow")), List.of(fast, slow), 1);

    assertThat(state.consumed()).isTrue();
    assertThat(state.bandIndex()).isEqualTo(1);
    assertThat(state.remainingTokens()).isEqualTo(4);
    assertThat(state.limit()).isEqualTo(5);
  }

  @Test
  @DisplayName("permits above the capacity fail before Redis is contacted")
  void shouldRejectPermitsAboveCapacity() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 5).label("small").build();
    String bucketKey = bucketKey("over-capacity");

    assertThatThrownBy(() -> store.tryConsume(bucketKey, band, 6))
        .isInstanceOf(InvalidRuleConfigException.class)
        .hasMessageContaining("exceed the capacity");

    assertThat(connectionProvider.exists(bucketKey)).isFalse();
  }

  @Test
  @DisplayName("deleteBucketsByRuleSetId only removes the buckets of that rule set")
  void shouldDeleteOnlyItsOwnBuckets() {
    // given: real bucket keys, plus a rule set definition that must survive
    String ruleSetId = "it-" + RUN_ID + "-delete";
    String otherRuleSetId = "it-" + RUN_ID + "-keep";
    RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 10).label("b").build();

    String mine =
        RedisRateLimiter.BUCKET_KEY_PREFIX
            + "{"
            + ruleSetId
            + ":r:ip:1.2.3.4}:"
            + band.getKeyLabel();
    String other =
        RedisRateLimiter.BUCKET_KEY_PREFIX
            + "{"
            + otherRuleSetId
            + ":r:ip:1.2.3.4}:"
            + band.getKeyLabel();
    String ruleSetKey = "fluxgate:ruleset:" + ruleSetId;

    store.tryConsume(mine, band, 1);
    store.tryConsume(other, band, 1);
    connectionProvider.hset(ruleSetKey, "ruleSetId", ruleSetId);

    // when
    long deleted = store.deleteBucketsByRuleSetId(ruleSetId);

    // then
    assertThat(deleted).isEqualTo(1);
    assertThat(connectionProvider.exists(mine)).isFalse();
    assertThat(connectionProvider.exists(other)).isTrue();
    assertThat(connectionProvider.exists(ruleSetKey))
        .as("a bucket reset must never destroy rule set definitions (C4 / C-3)")
        .isTrue();

    connectionProvider.del(other, ruleSetKey);
  }

  private static long tokens(String key) {
    return Long.parseLong(connectionProvider.hgetall(key).get("tokens"));
  }

  /** Builds a bucket key inside the FluxGate namespace that is unique to this JVM run. */
  private static String bucketKey(String name) {
    return RedisRateLimiter.BUCKET_KEY_PREFIX + "{it-" + RUN_ID + "}:" + name;
  }

  private static String keyPattern() {
    return RedisContainerSupport.KEY_PREFIX + "*" + RUN_ID + "*";
  }
}
