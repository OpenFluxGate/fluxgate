package org.fluxgate.redis.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.lettuce.core.RedisNoScriptException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;
import org.fluxgate.redis.script.LuaScripts;
import org.junit.jupiter.api.AfterEach;
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

  @Mock private RedisConnectionProvider connectionProvider;

  private RedisTokenBucketStore store;

  @BeforeEach
  void setUp() {
    // Set up LuaScripts with test values
    LuaScripts.setTokenBucketConsumeSha("test-sha-123");
    LuaScripts.setTokenBucketConsumeScript("test-script");

    // Mock connection provider mode
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);

    store = new RedisTokenBucketStore(connectionProvider);
  }

  @AfterEach
  void tearDown() {
    LuaScripts.setTokenBucketConsumeSha(null);
    LuaScripts.setTokenBucketConsumeScript(null);
  }

  @Test
  void shouldThrowWhenConnectionProviderIsNull() {
    assertThatThrownBy(() -> new RedisTokenBucketStore(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("connectionProvider must not be null");
  }

  @Test
  void shouldThrowWhenScriptsNotLoaded() {
    LuaScripts.setTokenBucketConsumeSha(null);
    LuaScripts.setTokenBucketConsumeScript(null);

    assertThatThrownBy(() -> new RedisTokenBucketStore(connectionProvider))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Lua scripts not loaded");
  }

  @Test
  void shouldConsumeTokensSuccessfully() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    // Lua script returns: [consumed, remaining, nanosToWait, resetTimeMillis, isNewBucket]
    List<Long> scriptResult = Arrays.asList(1L, 95L, 0L, System.currentTimeMillis() + 60000, 0L);
    doReturn(scriptResult)
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    // when
    BucketState result = store.tryConsume("test-key", band, 5);

    // then
    assertThat(result.consumed()).isTrue();
    assertThat(result.remainingTokens()).isEqualTo(95);
    assertThat(result.nanosToWaitForRefill()).isZero();
  }

  @Test
  void shouldRejectWhenNotEnoughTokens() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 10).label("test").build();

    // Lua script returns rejection: [consumed, remaining, nanosToWait, resetTimeMillis,
    // isNewBucket]
    List<Long> scriptResult = Arrays.asList(0L, 0L, 5_000_000_000L, System.currentTimeMillis(), 0L);
    doReturn(scriptResult)
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    // when
    BucketState result = store.tryConsume("test-key", band, 5);

    // then
    assertThat(result.consumed()).isFalse();
    assertThat(result.remainingTokens()).isZero();
    assertThat(result.nanosToWaitForRefill()).isEqualTo(5_000_000_000L);
  }

  @Test
  void shouldThrowWhenBucketKeyIsNull() {
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    assertThatThrownBy(() -> store.tryConsume(null, band, 1))
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
  void shouldThrowWhenScriptReturnsInvalidResult() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    // Invalid result (wrong size)
    List<Long> invalidResult = Arrays.asList(1L, 2L);
    doReturn(invalidResult)
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    // when/then
    assertThatThrownBy(() -> store.tryConsume("key", band, 1))
        .isInstanceOf(IllegalStateException.class)
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
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Lua script returned invalid result");
  }

  @Test
  void shouldPassCorrectArgumentsToScript() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    // [consumed, remaining, nanosToWait, resetTimeMillis, isNewBucket]
    List<Long> scriptResult = Arrays.asList(1L, 99L, 0L, System.currentTimeMillis(), 1L);
    doReturn(scriptResult)
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    // when
    store.tryConsume("my-bucket-key", band, 1);

    // then
    verify(connectionProvider)
        .evalsha(
            eq("test-sha-123"),
            eq(new String[] {"my-bucket-key"}),
            eq(new String[] {"100", String.valueOf(Duration.ofSeconds(60).toNanos()), "1"}));
  }

  @Test
  void shouldCloseWithoutError() {
    // when/then - should not throw
    store.close();
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
  @DisplayName("NOSCRIPT error should fallback to EVAL and reload script")
  void shouldFallbackToEvalOnNoscriptError() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    // First call throws NOSCRIPT error
    doThrow(new RedisNoScriptException("NOSCRIPT No matching script"))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    // EVAL fallback should work
    // [consumed, remaining, nanosToWait, resetTimeMillis]
    List<Long> scriptResult = Arrays.asList(1L, 99L, 0L, System.currentTimeMillis());
    doReturn(scriptResult)
        .when(connectionProvider)
        .eval(anyString(), any(String[].class), any(String[].class));

    // Script reload should return new SHA
    doReturn("new-sha-456").when(connectionProvider).scriptLoad(anyString());

    // when
    BucketState result = store.tryConsume("test-key", band, 1);

    // then
    assertThat(result.consumed()).isTrue();
    assertThat(result.remainingTokens()).isEqualTo(99);

    // Verify EVAL was called as fallback
    verify(connectionProvider).eval(eq("test-script"), any(String[].class), any(String[].class));

    // Verify script was reloaded
    verify(connectionProvider).scriptLoad("test-script");

    // Verify SHA was updated
    assertThat(LuaScripts.getTokenBucketConsumeSha()).isEqualTo("new-sha-456");
  }

  @Test
  @DisplayName("NOSCRIPT recovery should work for subsequent calls")
  void shouldRecoverFromNoscriptError() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    // [consumed, remaining, nanosToWait, resetTimeMillis]
    List<Long> scriptResult = Arrays.asList(1L, 99L, 0L, System.currentTimeMillis());

    // First call: NOSCRIPT error → fallback to EVAL
    doThrow(new RedisNoScriptException("NOSCRIPT No matching script"))
        .doReturn(scriptResult) // Second call succeeds
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    doReturn(scriptResult)
        .when(connectionProvider)
        .eval(anyString(), any(String[].class), any(String[].class));

    doReturn("new-sha-456").when(connectionProvider).scriptLoad(anyString());

    // when - first call (triggers NOSCRIPT)
    BucketState result1 = store.tryConsume("test-key", band, 1);

    // Reset mock to simulate script being reloaded
    reset(connectionProvider);
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
    doReturn(scriptResult)
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    // when - second call (should use reloaded script)
    BucketState result2 = store.tryConsume("test-key", band, 1);

    // then
    assertThat(result1.consumed()).isTrue();
    assertThat(result2.consumed()).isTrue();

    // EVAL should not be called on second request (script was reloaded)
    verify(connectionProvider, never()).eval(anyString(), any(String[].class), any(String[].class));
  }

  @Test
  @DisplayName("Script reload failure should not break EVAL fallback")
  void shouldContinueWorkingEvenIfReloadFails() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    // EVALSHA throws NOSCRIPT
    doThrow(new RedisNoScriptException("NOSCRIPT No matching script"))
        .when(connectionProvider)
        .evalsha(anyString(), any(String[].class), any(String[].class));

    // EVAL works
    // [consumed, remaining, nanosToWait, resetTimeMillis]
    List<Long> scriptResult = Arrays.asList(1L, 99L, 0L, System.currentTimeMillis());
    doReturn(scriptResult)
        .when(connectionProvider)
        .eval(anyString(), any(String[].class), any(String[].class));

    // Script reload fails
    doThrow(new RuntimeException("Redis connection lost"))
        .when(connectionProvider)
        .scriptLoad(anyString());

    // when - should not throw even though reload failed
    BucketState result = store.tryConsume("test-key", band, 1);

    // then - EVAL fallback should still work
    assertThat(result.consumed()).isTrue();
    assertThat(result.remainingTokens()).isEqualTo(99);
  }
}
