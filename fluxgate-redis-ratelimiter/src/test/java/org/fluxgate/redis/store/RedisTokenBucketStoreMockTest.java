package org.fluxgate.redis.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisNoScriptException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.exception.InvalidRuleConfigException;
import org.fluxgate.core.exception.ScriptExecutionException;
import org.fluxgate.redis.RedisRateLimiter;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;
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
    // [allowed, rejecting_band_index, min_remaining, micros_to_wait, reset, limit, binding_band]
    return Arrays.asList(1L, 0L, remaining, 0L, reset, limit, bandIndex);
  }

  private static List<Long> rejectedReply(
      long remaining, long microsToWait, long reset, long limit, long bandIndex) {
    return Arrays.asList(0L, bandIndex, remaining, microsToWait, reset, limit, bandIndex);
  }

  @Test
  void shouldThrowWhenConnectionProviderIsNull() {
    assertThatThrownBy(() -> new RedisTokenBucketStore(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("connectionProvider must not be null");
  }

  @Test
  @DisplayName("Constructor should upload the script to its own Redis")
  void shouldLoadTheScriptOnConstruction() {
    verify(connectionProvider).scriptLoad(contains("token"));
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
            eq(new String[] {"my-bucket-key"}),
            eq(
                new String[] {
                  "1", DEFAULT_TTL_ARG, "100", String.valueOf(MINUTE_MICROS), "1", "0", "0"
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
            eq(new String[] {"my-bucket-key"}),
            eq(new String[] {"1", "21600", "100", String.valueOf(MINUTE_MICROS), "1", "0", "0"}));
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
            eq(new String[] {"k-fast", "k-slow"}),
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
                  "0"
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
    when(connectionProvider.scanKeys(pattern, 1000L)).thenReturn(keys);
    when(connectionProvider.unlink(keys.toArray(new String[0]))).thenReturn(2L);

    // when
    long deleted = store.deleteBucketsByRuleSetId("test-rule");

    // then
    assertThat(deleted).isEqualTo(2L);
    assertThat(pattern).isEqualTo("fluxgate:bucket:{test-rule:*");
    verify(connectionProvider).scanKeys(pattern, 1000L);
    verify(connectionProvider, never()).keys(anyString());
    verify(connectionProvider, never()).del(any(String[].class));
  }

  @Test
  @DisplayName("Full reset never looks outside the bucket namespace")
  void shouldDeleteAllBucketsUsingScan() {
    // given
    List<String> keys =
        Arrays.asList("fluxgate:bucket:{rule-a:r:ip:1}:b", "fluxgate:bucket:{rule-b:r:ip:2}:b");
    when(connectionProvider.scanKeys("fluxgate:bucket:*", 1000L)).thenReturn(keys);
    when(connectionProvider.unlink(keys.toArray(new String[0]))).thenReturn(2L);

    // when
    long deleted = store.deleteAllBuckets();

    // then
    assertThat(deleted).isEqualTo(2L);
    verify(connectionProvider).scanKeys("fluxgate:bucket:*", 1000L);
    verify(connectionProvider, never()).keys(anyString());
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

    // EVAL was used as the fallback and the script was reloaded for the next call
    verify(connectionProvider).eval(contains("token"), any(String[].class), any(String[].class));
    verify(connectionProvider, times(2)).scriptLoad(anyString());
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
}
