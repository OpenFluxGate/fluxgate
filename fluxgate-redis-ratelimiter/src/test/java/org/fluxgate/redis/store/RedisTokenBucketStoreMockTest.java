package org.fluxgate.redis.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisNoScriptException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.exception.FluxgateTimeoutException;
import org.fluxgate.core.exception.InvalidRuleConfigException;
import org.fluxgate.core.exception.RedisConnectionException.Phase;
import org.fluxgate.core.exception.ScriptExecutionException;
import org.fluxgate.core.resilience.DefaultRetryExecutor;
import org.fluxgate.core.resilience.RetryConfig;
import org.fluxgate.redis.RedisRateLimiter;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;
import org.fluxgate.redis.script.LuaScriptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Mock-based unit tests for {@link RedisTokenBucketStore}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisTokenBucketStoreMockTest {

  private static final long MINUTE_MICROS = 60_000_000L;
  private static final long REDIS_TIME = 1_760_000_000_000_000L;
  private static final String DEFAULT_TTL_ARG =
      String.valueOf(RedisTokenBucketStore.DEFAULT_MAX_BUCKET_TTL.getSeconds());

  @Mock private RedisConnectionProvider connectionProvider;

  private RedisTokenBucketStore store;

  @BeforeEach
  void setUp() {
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
    when(connectionProvider.scriptLoad(anyString())).thenReturn("test-sha-123");

    store = new RedisTokenBucketStore(connectionProvider);
  }

  private static List<Long> allowedReply(long remaining, long reset, long limit, long bandIndex) {
    // [allowed, rejecting_band_index, min_remaining, micros_to_wait, reset, limit, binding_band,
    //  redis_time_micros]
    return Arrays.asList(1L, 0L, remaining, 0L, reset, limit, bandIndex, REDIS_TIME);
  }

  private static List<Long> rejectedReply(
      long remaining, long microsToWait, long reset, long limit, long bandIndex) {
    return Arrays.asList(
        0L, bandIndex, remaining, microsToWait, reset, limit, bandIndex, REDIS_TIME);
  }

  @Test
  void shouldThrowWhenConnectionProviderIsNull() {
    assertThatThrownBy(() -> new RedisTokenBucketStore(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("connectionProvider must not be null");
  }

  @Test
  @DisplayName("Constructor should upload the consume and refund scripts to its own Redis")
  void shouldLoadTheScriptOnConstruction() {
    LuaScriptRegistry bodies = new LuaScriptRegistry();
    verify(connectionProvider).scriptLoad(bodies.getTokenBucketConsumeScript());
    verify(connectionProvider).scriptLoad(bodies.getTokenBucketRefundScript());
  }

  @Test
  @DisplayName("The Redis time of the decision is carried into the BucketState")
  void shouldReportTheRedisTimeOfTheDecision() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();
    doReturn(allowedReply(99L, 0L, 100L, 1L))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    assertThat(store.tryConsume("test-key", band, 1).redisTimeMicros()).isEqualTo(REDIS_TIME);
  }

  @Test
  @DisplayName("refund sends the consume time and the band layout to the refund script")
  void shouldRefundThroughTheRefundScript() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();
    doReturn(List.of(1L))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    List<Long> refunded = store.refund(List.of("k1"), List.of(band), 1, REDIS_TIME);

    assertThat(refunded).containsExactly(1L);
    verify(connectionProvider)
        .evalsha(
            anyString(),
            eq(new String[] {"k1", RedisTokenBucketStore.metadataKey("k1")}),
            eq(
                new String[] {
                  "1", String.valueOf(REDIS_TIME), "100", "60000000", "1", "0", "0", "FENCED"
                }));
  }

  @Test
  @DisplayName("refund rejects an unknown consume time and mismatched arguments")
  void shouldValidateRefundArguments() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    assertThatThrownBy(() -> store.refund(List.of("k1"), List.of(band), 1, -1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.refund(List.of("k1", "k2"), List.of(band), 1, REDIS_TIME))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.refund(List.of("k1"), List.of(band), 0, REDIS_TIME))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("refund reports a malformed script reply")
  void shouldRejectAMalformedRefundReply() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();
    doReturn(List.of(1L, 2L))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    assertThatThrownBy(() -> store.refund(List.of("k1"), List.of(band), 1, REDIS_TIME))
        .isInstanceOf(ScriptExecutionException.class);
  }

  @Test
  @DisplayName("Standalone: any keys may share one script call")
  void standaloneCanAlwaysEvaluateTogether() {
    assertThat(store.canEvaluateAtomically(List.of("{a}:1", "{b}:2"))).isTrue();
  }

  @Test
  @DisplayName("Cluster: keys may share one script call only when they hash to one slot")
  void clusterCanEvaluateTogetherOnlyWithinOneSlot() {
    when(connectionProvider.getMode()).thenReturn(RedisMode.CLUSTER);

    assertThat(store.canEvaluateAtomically(List.of("{same}:1", "{same}:2"))).isTrue();
    // "{a}" and "{b}" hash to slots 15495 and 3300
    assertThat(store.canEvaluateAtomically(List.of("{a}:1", "{b}:2"))).isFalse();
  }

  @Test
  void shouldConsumeTokensSuccessfully() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    doReturn(allowedReply(95L, System.currentTimeMillis() + 60000, 100L, 1L))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    // when
    BucketState result = store.tryConsume("test-key", band, 5);

    // then
    assertThat(result.consumed()).isTrue();
    assertThat(result.remainingTokens()).isEqualTo(95);
    assertThat(result.nanosToWaitForRefill()).isZero();
    assertThat(result.limit()).isEqualTo(100L);
    assertThat(result.bandIndex()).isZero();
  }

  @Test
  void shouldRejectWhenNotEnoughTokens() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 10).label("test").build();

    doReturn(rejectedReply(0L, 5_000_000L, System.currentTimeMillis(), 10L, 1L))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    // when
    BucketState result = store.tryConsume("test-key", band, 5);

    // then
    assertThat(result.consumed()).isFalse();
    assertThat(result.remainingTokens()).isZero();
    // micros are converted to nanos
    assertThat(result.nanosToWaitForRefill()).isEqualTo(5_000_000_000L);
    assertThat(result.limit()).isEqualTo(10L);
    assertThat(result.bandIndex()).isZero();
  }

  @Test
  @DisplayName("Rejecting band index is reported zero-based, whichever band rejected")
  void shouldReportTheRejectingBandIndex() {
    // given: two bands, the second one rejects
    List<RateLimitBand> bands =
        List.of(
            RateLimitBand.builder(Duration.ofSeconds(1), 10).label("fast").build(),
            RateLimitBand.builder(Duration.ofMinutes(1), 2).label("slow").build());

    doReturn(rejectedReply(0L, 30_000_000L, System.currentTimeMillis(), 2L, 2L))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    // when
    BucketState result = store.tryConsume(List.of("k1", "k2"), bands, 1);

    // then
    assertThat(result.consumed()).isFalse();
    assertThat(result.bandIndex()).isEqualTo(1);
    assertThat(result.limit()).isEqualTo(2L);
  }

  @Test
  void shouldThrowWhenBucketKeyIsNull() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    assertThatThrownBy(() -> store.tryConsume((String) null, band, 1))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("bucketKey must not be null");
  }

  @Test
  void shouldThrowWhenBandIsNull() {
    assertThatThrownBy(() -> store.tryConsume("key", null, 1))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("band must not be null");
  }

  @Test
  void shouldThrowWhenPermitsIsZeroOrNegative() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    assertThatThrownBy(() -> store.tryConsume("key", band, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("permits must be > 0");

    assertThatThrownBy(() -> store.tryConsume("key", band, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("permits must be > 0");
  }

  @Test
  void shouldThrowWhenKeysAndBandsDisagree() {
    List<RateLimitBand> bands =
        List.of(RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build());

    assertThatThrownBy(() -> store.tryConsume(List.of("a", "b"), bands, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must have the same size");
  }

  @Test
  @DisplayName("permits above a band's capacity fail before Redis is contacted")
  void shouldRejectPermitsAboveCapacity() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 10).label("small").build();

    assertThatThrownBy(() -> store.tryConsume("key", band, 11))
        .isInstanceOf(InvalidRuleConfigException.class)
        .hasMessageContaining("exceed the capacity of band 'small'");

    verify(connectionProvider, never())
        .evalsha(anyString(), any(String[].class), any(String[].class));
  }

  @Test
  void shouldThrowWhenScriptReturnsInvalidResult() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    doReturn(Arrays.asList(1L, 2L))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    // when/then
    assertThatThrownBy(() -> store.tryConsume("key", band, 1))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("Lua script returned invalid result");
  }

  @Test
  void shouldThrowWhenScriptReturnsNull() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    doReturn(null)
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    // when/then
    assertThatThrownBy(() -> store.tryConsume("key", band, 1))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("Lua script returned invalid result");
  }

  @Test
  @DisplayName("A Lua error_reply becomes a ScriptExecutionException, not a Lettuce exception")
  void shouldWrapScriptErrors() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    doThrow(new RedisCommandExecutionException("permits exceed capacity"))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    assertThatThrownBy(() -> store.tryConsume("key", band, 1))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("permits exceed capacity")
        .hasMessageContaining("token_bucket_consume.lua")
        .hasCauseInstanceOf(RedisCommandExecutionException.class);
  }

  @Test
  void shouldPassCorrectArgumentsToScript() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    doReturn(allowedReply(99L, System.currentTimeMillis(), 100L, 1L))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    // when
    store.tryConsume("my-bucket-key", band, 1);

    // then: ARGV = [permits, maxTtl, capacity, window_micros, algCode, bucketsOrZero, windowEnd]
    verify(connectionProvider)
        .evalsha(
            eq("test-sha-123"),
            eq(
                new String[] {
                  "my-bucket-key",
                  RedisTokenBucketStore.metadataKey("my-bucket-key"),
                  RedisTokenBucketStore.revisionKey("my-bucket-key")
                }),
            eq(
                new String[] {
                  "1",
                  DEFAULT_TTL_ARG,
                  "100",
                  String.valueOf(MINUTE_MICROS),
                  "1",
                  "0",
                  "0",
                  "0",
                  "0",
                  "FENCED"
                }));
  }

  @Test
  @DisplayName("The bucket TTL cap is ARGV[2] and the band quintuples follow at ARGV[3+]")
  void shouldPassTheConfiguredBucketTtlCapAtArgv2() {
    RedisTokenBucketStore capped =
        new RedisTokenBucketStore(connectionProvider, Duration.ofHours(6));
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    doReturn(allowedReply(99L, System.currentTimeMillis(), 100L, 1L))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    capped.tryConsume("my-bucket-key", band, 1);

    assertThat(capped.getMaxBucketTtl()).isEqualTo(Duration.ofHours(6));
    verify(connectionProvider)
        .evalsha(
            eq("test-sha-123"),
            eq(
                new String[] {
                  "my-bucket-key",
                  RedisTokenBucketStore.metadataKey("my-bucket-key"),
                  RedisTokenBucketStore.revisionKey("my-bucket-key")
                }),
            eq(
                new String[] {
                  "1",
                  "21600",
                  "100",
                  String.valueOf(MINUTE_MICROS),
                  "1",
                  "0",
                  "0",
                  "0",
                  "0",
                  "FENCED"
                }));
  }

  @Test
  @DisplayName("A window below 1 ms is refused before Redis is called")
  void shouldRejectAWindowBelowOneMillisecond() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofNanos(999_999), 10).build();

    assertThatThrownBy(() -> store.tryConsume("key", band, 1))
        .isInstanceOf(InvalidRuleConfigException.class)
        .hasMessageContaining("at least 1 ms");
    verify(connectionProvider, never())
        .evalsha(anyString(), any(String[].class), any(String[].class));
  }

  @Test
  @DisplayName("A sliding sub-bucket below 1 ms is refused before Redis is called")
  void shouldRejectASlidingSubBucketBelowOneMillisecond() {
    // 10 ms over 20 sub-buckets = 500 µs each
    RateLimitBand band =
        RateLimitBand.builder(Duration.ofMillis(10), 10)
            .algorithm(org.fluxgate.core.config.RateLimitAlgorithm.SLIDING_WINDOW)
            .slidingWindowBuckets(20)
            .build();

    assertThatThrownBy(() -> store.tryConsume("key", band, 1))
        .isInstanceOf(InvalidRuleConfigException.class)
        .hasMessageContaining("sub-bucket");
    verify(connectionProvider, never())
        .evalsha(anyString(), any(String[].class), any(String[].class));
  }

  @Test
  void shouldDefaultTheBucketTtlCapToSevenDays() {
    assertThat(store.getMaxBucketTtl()).isEqualTo(Duration.ofDays(7));
  }

  @Test
  void shouldRejectABucketTtlCapBelowOneSecond() {
    assertThatThrownBy(() -> new RedisTokenBucketStore(connectionProvider, Duration.ofMillis(500)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxBucketTtl");
  }

  @Test
  @DisplayName("Multi-band call passes one key and five ARGV per band, with maxTtl at ARGV[2]")
  void shouldPassOneArgumentQuintetPerBand() {
    List<RateLimitBand> bands =
        List.of(
            RateLimitBand.builder(Duration.ofSeconds(1), 10).label("fast").build(),
            RateLimitBand.builder(Duration.ofMinutes(1), 2).label("slow").build());

    doReturn(allowedReply(1L, System.currentTimeMillis(), 2L, 2L))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    store.tryConsume(List.of("k-fast", "k-slow"), bands, 1);

    // New layout: [permits, maxTtl, cap1, window1, algCode1, buckets1, windowEnd1,
    //                              cap2, window2, algCode2, buckets2, windowEnd2]
    verify(connectionProvider)
        .evalsha(
            eq("test-sha-123"),
            eq(
                new String[] {
                  "k-fast",
                  "k-slow",
                  RedisTokenBucketStore.metadataKey("k-fast"),
                  RedisTokenBucketStore.metadataKey("k-slow"),
                  RedisTokenBucketStore.revisionKey("k-fast"),
                  RedisTokenBucketStore.revisionKey("k-slow")
                }),
            eq(
                new String[] {
                  "1",
                  DEFAULT_TTL_ARG,
                  "10",
                  "1000000",
                  "1",
                  "0",
                  "0",
                  "2",
                  String.valueOf(MINUTE_MICROS),
                  "1",
                  "0",
                  "0",
                  "0",
                  "0",
                  "0",
                  "FENCED"
                }));
  }

  @Test
  @DisplayName("check() runs the consume script in check-only mode (trailing ARGV flag)")
  void checkShouldPassTheCheckOnlyFlag() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();
    doReturn(rejectedReply(0L, 2_000_000L, System.currentTimeMillis(), 100L, 1L))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    BucketState state = store.check(List.of("my-bucket-key"), List.of(band), 1);

    assertThat(state.consumed()).isFalse();
    assertThat(state.nanosToWaitForRefill()).isEqualTo(2_000_000_000L);
    verify(connectionProvider)
        .evalsha(
            eq("test-sha-123"),
            eq(
                new String[] {
                  "my-bucket-key",
                  RedisTokenBucketStore.metadataKey("my-bucket-key"),
                  RedisTokenBucketStore.revisionKey("my-bucket-key")
                }),
            eq(
                new String[] {
                  "1",
                  DEFAULT_TTL_ARG,
                  "100",
                  String.valueOf(MINUTE_MICROS),
                  "1",
                  "0",
                  "0",
                  "0",
                  "1",
                  "FENCED"
                }));
  }

  @Test
  void shouldReturnCorrectMode() {
    // given
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);

    // then
    assertThat(store.getMode()).isEqualTo(RedisMode.STANDALONE);

    // given cluster mode
    when(connectionProvider.getMode()).thenReturn(RedisMode.CLUSTER);

    // then
    assertThat(store.getMode()).isEqualTo(RedisMode.CLUSTER);
  }

  @Test
  void shouldDeleteBucketsByRuleSetIdUsingScanAndUnlink() {
    // given
    String pattern = RedisRateLimiter.bucketKeyPattern("test-rule");
    List<String> keys =
        Arrays.asList(
            "fluxgate:bucket:{test-rule:per-ip:ip:127.0.0.1}:10-per-1s",
            "fluxgate:bucket:{test-rule:per-user:user:u1}:10-per-1s");
    scanPages(pattern, keys);
    when(connectionProvider.unlink(keys.toArray(new String[0]))).thenReturn(2L);

    // when
    long deleted = store.deleteBucketsByRuleSetId("test-rule");

    // then
    assertThat(deleted).isEqualTo(2L);
    assertThat(pattern).isEqualTo("fluxgate:bucket:{test-rule:*");
    verify(connectionProvider, never()).keys(anyString());
    verify(connectionProvider, never()).del(any(String[].class));
  }

  @Test
  @DisplayName("Full reset never looks outside the bucket namespace")
  void shouldDeleteAllBucketsUsingScan() {
    // given
    List<String> keys =
        Arrays.asList("fluxgate:bucket:{rule-a:r:ip:1}:b", "fluxgate:bucket:{rule-b:r:ip:2}:b");
    scanPages("fluxgate:bucket:*", keys);
    when(connectionProvider.unlink(keys.toArray(new String[0]))).thenReturn(2L);

    // when
    long deleted = store.deleteAllBuckets();

    // then
    assertThat(deleted).isEqualTo(2L);
    verify(connectionProvider).scanKeys(eq("fluxgate:bucket:*"), eq(1000L), any());
    verify(connectionProvider, never()).keys(anyString());
  }

  @Test
  @DisplayName("Reset unlinks page by page instead of collecting the whole keyspace first")
  void shouldUnlinkEachScanPageAsItArrives() {
    List<String> first = Arrays.asList("fluxgate:bucket:{a:r:k}:1", "fluxgate:bucket:{a:r:k}:2");
    List<String> second = Arrays.asList("fluxgate:bucket:{a:r:k}:3");
    List<String> unlinkedBeforeSecondPage = new java.util.ArrayList<>();
    doAnswer(
            invocation -> {
              java.util.function.Consumer<List<String>> consumer = invocation.getArgument(2);
              consumer.accept(first);
              // the first page must already be gone when the second one is read
              verify(connectionProvider).unlink(first.toArray(new String[0]));
              unlinkedBeforeSecondPage.addAll(first);
              consumer.accept(second);
              return null;
            })
        .when(connectionProvider)
        .scanKeys(eq("fluxgate:bucket:*"), eq(1000L), any());
    when(connectionProvider.unlink(any(String[].class)))
        .thenAnswer(invocation -> (long) invocation.getArguments().length);

    long deleted = store.deleteAllBuckets();

    assertThat(deleted).isEqualTo(3L);
    assertThat(unlinkedBeforeSecondPage).containsExactlyElementsOf(first);
    verify(connectionProvider).unlink(second.toArray(new String[0]));
    verify(connectionProvider, never()).scanKeys(anyString(), anyLong());
  }

  /** Stubs the paged SCAN to deliver {@code keys} as one page. */
  private void scanPages(String pattern, List<String> keys) {
    doAnswer(
            invocation -> {
              java.util.function.Consumer<List<String>> consumer = invocation.getArgument(2);
              consumer.accept(keys);
              return null;
            })
        .when(connectionProvider)
        .scanKeys(eq(pattern), eq(1000L), any());
  }

  @Test
  @DisplayName("No delete pattern can reach the rule set namespace (C4 / C-3)")
  void deletePatternsMustNotOverlapTheRuleSetNamespace() {
    // Both patterns are literal up to and including "fluxgate:bucket:", which is disjoint from
    // the prefixes RedisRuleSetStore uses. A full reset can no longer erase stored rule sets.
    String all = RedisRateLimiter.BUCKET_KEY_PREFIX + "*";
    String perRuleSet = RedisRateLimiter.bucketKeyPattern("*");

    for (String pattern : List.of(all, perRuleSet)) {
      assertThat(pattern).startsWith(RedisRateLimiter.BUCKET_KEY_PREFIX);
      assertThat("fluxgate:ruleset:anything").doesNotStartWith(RedisRateLimiter.BUCKET_KEY_PREFIX);
      assertThat("fluxgate:rulesets").doesNotStartWith(RedisRateLimiter.BUCKET_KEY_PREFIX);
      // and the wildcard only ever appears after that literal prefix
      assertThat(pattern.indexOf('*'))
          .isGreaterThanOrEqualTo(RedisRateLimiter.BUCKET_KEY_PREFIX.length());
    }
  }

  @Test
  @DisplayName("NOSCRIPT error should fallback to EVAL and reload script")
  void shouldFallbackToEvalOnNoscriptError() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    // First call throws NOSCRIPT error
    doThrow(new RedisNoScriptException("NOSCRIPT No matching script"))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    // EVAL fallback should work
    doReturn(allowedReply(99L, System.currentTimeMillis(), 100L, 1L))
        .when(connectionProvider)
        .eval(anyString(), any(String[].class), any(String[].class));

    // Script reload should return a new SHA
    doReturn("new-sha-456").when(connectionProvider).scriptLoad(anyString());

    // when
    BucketState result = store.tryConsume("test-key", band, 1);

    // then
    assertThat(result.consumed()).isTrue();
    assertThat(result.remainingTokens()).isEqualTo(99);

    // EVAL was used as the fallback and both scripts were reloaded for the next call
    verify(connectionProvider).eval(contains("token"), any(String[].class), any(String[].class));
    verify(connectionProvider, times(4)).scriptLoad(anyString());
  }

  @Test
  @DisplayName("NOSCRIPT recovery should use the new SHA on subsequent calls")
  void shouldRecoverFromNoscriptError() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();
    List<Long> reply = allowedReply(99L, System.currentTimeMillis(), 100L, 1L);

    doThrow(new RedisNoScriptException("NOSCRIPT No matching script"))
        .doReturn(reply)
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    doReturn(reply)
        .when(connectionProvider)
        .eval(anyString(), any(String[].class), any(String[].class));

    doReturn("new-sha-456").when(connectionProvider).scriptLoad(anyString());

    // when
    BucketState result1 = store.tryConsume("test-key", band, 1);
    BucketState result2 = store.tryConsume("test-key", band, 1);

    // then
    assertThat(result1.consumed()).isTrue();
    assertThat(result2.consumed()).isTrue();

    // EVAL was only needed for the first call
    verify(connectionProvider, times(1))
        .eval(anyString(), any(String[].class), any(String[].class));
    // and the second call went out with the reloaded SHA
    verify(connectionProvider).evalsha(eq("new-sha-456"), any(String[].class), any(String[].class));
  }

  @Test
  @DisplayName("Script reload failure should not break EVAL fallback")
  void shouldContinueWorkingEvenIfReloadFails() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    doThrow(new RedisNoScriptException("NOSCRIPT No matching script"))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    doReturn(allowedReply(99L, System.currentTimeMillis(), 100L, 1L))
        .when(connectionProvider)
        .eval(anyString(), any(String[].class), any(String[].class));

    // The constructor already loaded the script; the reload attempt now fails
    doThrow(new RuntimeException("Redis connection lost"))
        .when(connectionProvider)
        .scriptLoad(anyString());

    // when - should not throw even though the reload failed
    BucketState result = store.tryConsume("test-key", band, 1);

    // then - EVAL fallback still served the request
    assertThat(result.consumed()).isTrue();
    assertThat(result.remainingTokens()).isEqualTo(99);
  }

  // ===== Lettuce failures surface as FluxGate exceptions =====

  @Test
  @DisplayName("A Lettuce command timeout becomes a FluxgateTimeoutException")
  void shouldWrapACommandTimeout() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();
    RedisCommandTimeoutException timeout =
        new RedisCommandTimeoutException("Command timed out after 5 second(s)");
    doThrow(timeout)
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    assertThatThrownBy(() -> store.tryConsume("key", band, 1))
        .isInstanceOf(FluxgateTimeoutException.class)
        .hasMessageContaining("token_bucket_consume.lua")
        .hasCause(timeout);
  }

  @Test
  @DisplayName("A Lettuce connection failure becomes a FluxGate RedisConnectionException")
  void shouldWrapAConnectionFailure() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();
    io.lettuce.core.RedisConnectionException refused =
        new io.lettuce.core.RedisConnectionException("Unable to connect to localhost:6379");
    doThrow(refused)
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    assertThatThrownBy(() -> store.tryConsume("key", band, 1))
        .isInstanceOf(org.fluxgate.core.exception.RedisConnectionException.class)
        .hasCause(refused);
  }

  @Test
  @DisplayName("Any other Lettuce failure (e.g. a closed connection) is wrapped as well")
  void shouldWrapOtherLettuceFailures() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();
    RedisException closed = new RedisException("Connection closed");
    doThrow(closed)
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    assertThatThrownBy(() -> store.tryConsume("key", band, 1))
        .isInstanceOf(org.fluxgate.core.exception.RedisConnectionException.class)
        .hasCause(closed);
  }

  @Test
  @DisplayName("A timeout of the EVAL fallback is wrapped too")
  void shouldWrapATimeoutOfTheEvalFallback() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();
    doThrow(new RedisNoScriptException("NOSCRIPT No matching script"))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));
    doThrow(new RedisCommandTimeoutException("Command timed out"))
        .when(connectionProvider)
        .eval(anyString(), any(String[].class), any(String[].class));

    assertThatThrownBy(() -> store.tryConsume("key", band, 1))
        .isInstanceOf(FluxgateTimeoutException.class);
  }

  @Test
  @DisplayName("A refund timeout is wrapped too")
  void shouldWrapARefundTimeout() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();
    doThrow(new RedisCommandTimeoutException("Command timed out"))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    assertThatThrownBy(() -> store.refund(List.of("key"), List.of(band), 1, REDIS_TIME))
        .isInstanceOf(FluxgateTimeoutException.class)
        .hasMessageContaining("token_bucket_refund.lua");
  }

  // ===== Command-phase failures are never retried =====

  @Test
  @DisplayName("A failure while consuming is a COMMAND-phase failure and is not retried")
  void consumeFailureIsCommandPhaseAndNotRetried() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();
    doThrow(new io.lettuce.core.RedisConnectionException("Connection reset by peer"))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));
    DefaultRetryExecutor retry =
        new DefaultRetryExecutor(
            RetryConfig.builder().maxAttempts(3).initialBackoff(Duration.ofMillis(1)).build());

    assertThatThrownBy(() -> retry.execute(() -> store.tryConsume("key", band, 1)))
        .isInstanceOfSatisfying(
            org.fluxgate.core.exception.RedisConnectionException.class,
            e -> {
              assertThat(e.getPhase()).isEqualTo(Phase.COMMAND);
              assertThat(e.isRetryable()).isFalse();
            });
    verify(connectionProvider, times(1))
        .evalsha(anyString(), any(String[].class), any(String[].class));
  }

  @Test
  @DisplayName("Failures while checking or refunding are COMMAND-phase failures")
  void checkAndRefundFailuresAreCommandPhase() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();
    doThrow(new RedisException("Connection closed"))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    assertThatThrownBy(() -> store.check(List.of("key"), List.of(band), 1))
        .isInstanceOfSatisfying(
            org.fluxgate.core.exception.RedisConnectionException.class,
            e -> assertThat(e.getPhase()).isEqualTo(Phase.COMMAND));
    assertThatThrownBy(() -> store.refund(List.of("key"), List.of(band), 1, REDIS_TIME))
        .isInstanceOfSatisfying(
            org.fluxgate.core.exception.RedisConnectionException.class,
            e -> assertThat(e.getPhase()).isEqualTo(Phase.COMMAND));
  }

  @Test
  @DisplayName("A failure of the EVAL fallback is a COMMAND-phase failure")
  void evalFallbackFailureIsCommandPhase() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();
    doThrow(new RedisNoScriptException("NOSCRIPT No matching script"))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));
    doThrow(new RedisException("Connection closed"))
        .when(connectionProvider)
        .eval(anyString(), any(String[].class), any(String[].class));

    assertThatThrownBy(() -> store.tryConsume("key", band, 1))
        .isInstanceOfSatisfying(
            org.fluxgate.core.exception.RedisConnectionException.class,
            e -> assertThat(e.getPhase()).isEqualTo(Phase.COMMAND));
  }

  @Test
  @DisplayName("A Lettuce failure while scanning for a reset is wrapped as a COMMAND failure")
  void scanFailureIsWrappedAsCommandPhase() {
    RedisException closed = new RedisException("Connection closed");
    doThrow(closed).when(connectionProvider).scanKeys(anyString(), anyLong(), any());

    assertThatThrownBy(() -> store.deleteAllBuckets())
        .isInstanceOfSatisfying(
            org.fluxgate.core.exception.RedisConnectionException.class,
            e -> assertThat(e.getPhase()).isEqualTo(Phase.COMMAND))
        .hasCause(closed);
    assertThatThrownBy(() -> store.deleteBucketsByRuleSetId("rs"))
        .isInstanceOf(org.fluxgate.core.exception.RedisConnectionException.class);
  }

  @Test
  @DisplayName("A timeout while unlinking during a reset becomes a FluxgateTimeoutException")
  void unlinkTimeoutDuringResetIsWrapped() {
    scanPages("fluxgate:bucket:*", Arrays.asList("fluxgate:bucket:{a:r:k}:1"));
    doThrow(new RedisCommandTimeoutException("Command timed out"))
        .when(connectionProvider)
        .unlink(any(String[].class));

    assertThatThrownBy(() -> store.deleteAllBuckets()).isInstanceOf(FluxgateTimeoutException.class);
  }

  // ===== NOSCRIPT recovery does not hammer Redis =====

  @Test
  @DisplayName("A failing script reload is not retried on every NOSCRIPT (backoff)")
  void shouldBackOffAfterAFailedReload() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();
    doThrow(new RedisNoScriptException("NOSCRIPT No matching script"))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));
    doReturn(allowedReply(99L, System.currentTimeMillis(), 100L, 1L))
        .when(connectionProvider)
        .eval(anyString(), any(String[].class), any(String[].class));
    doThrow(new RuntimeException("SCRIPT LOAD failed"))
        .when(connectionProvider)
        .scriptLoad(anyString());

    for (int i = 0; i < 5; i++) {
      assertThat(store.tryConsume("key", band, 1).consumed()).isTrue();
    }

    // 2 uploads by the constructor, then a single reload attempt (failing on its first script)
    verify(connectionProvider, times(3)).scriptLoad(anyString());
    verify(connectionProvider, times(5))
        .eval(anyString(), any(String[].class), any(String[].class));
  }

  @Test
  @DisplayName("A burst of NOSCRIPT replies triggers a single reload")
  void shouldReloadOnceForABurstOfNoscript() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();
    doThrow(new RedisNoScriptException("NOSCRIPT No matching script"))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));
    doReturn(allowedReply(99L, System.currentTimeMillis(), 100L, 1L))
        .when(connectionProvider)
        .eval(anyString(), any(String[].class), any(String[].class));

    for (int i = 0; i < 5; i++) {
      store.tryConsume("key", band, 1);
    }

    // 2 uploads by the constructor, 2 by the one reload
    verify(connectionProvider, times(4)).scriptLoad(anyString());
  }
}
