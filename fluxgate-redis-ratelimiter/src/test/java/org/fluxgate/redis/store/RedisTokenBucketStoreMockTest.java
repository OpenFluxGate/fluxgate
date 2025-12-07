package org.fluxgate.redis.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.redis.script.LuaScripts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

  @Mock private RedisCommands<String, String> redisCommands;

  private RedisTokenBucketStore store;

  @BeforeEach
  void setUp() {
    // Set up LuaScripts with test values
    LuaScripts.setTokenBucketConsumeSha("test-sha-123");
    LuaScripts.setTokenBucketConsumeScript("test-script");

    store = new RedisTokenBucketStore(redisCommands);
  }

  @AfterEach
  void tearDown() {
    LuaScripts.setTokenBucketConsumeSha(null);
    LuaScripts.setTokenBucketConsumeScript(null);
  }

  @Test
  void shouldThrowWhenRedisCommandsIsNull() {
    assertThatThrownBy(() -> new RedisTokenBucketStore(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("redisCommands must not be null");
  }

  @Test
  void shouldThrowWhenScriptsNotLoaded() {
    LuaScripts.setTokenBucketConsumeSha(null);
    LuaScripts.setTokenBucketConsumeScript(null);

    assertThatThrownBy(() -> new RedisTokenBucketStore(redisCommands))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Lua scripts not loaded");
  }

  @Test
  void shouldConsumeTokensSuccessfully() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    // Lua script returns: [consumed, remaining, nanosToWait, resetTimeMillis]
    List<Long> scriptResult = Arrays.asList(1L, 95L, 0L, System.currentTimeMillis() + 60000);
    doReturn(scriptResult)
        .when(redisCommands)
        .evalsha(anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class));

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

    // Lua script returns rejection
    List<Long> scriptResult = Arrays.asList(0L, 0L, 5_000_000_000L, System.currentTimeMillis());
    doReturn(scriptResult)
        .when(redisCommands)
        .evalsha(anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class));

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
        .when(redisCommands)
        .evalsha(anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class));

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
        .when(redisCommands)
        .evalsha(anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class));

    // when/then
    assertThatThrownBy(() -> store.tryConsume("key", band, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Lua script returned invalid result");
  }

  @Test
  void shouldPassCorrectArgumentsToScript() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100).label("test").build();

    List<Long> scriptResult = Arrays.asList(1L, 99L, 0L, System.currentTimeMillis());
    doReturn(scriptResult)
        .when(redisCommands)
        .evalsha(anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class));

    // when
    store.tryConsume("my-bucket-key", band, 1);

    // then
    verify(redisCommands)
        .evalsha(
            eq("test-sha-123"),
            eq(ScriptOutputType.MULTI),
            eq(new String[] {"my-bucket-key"}),
            eq(new String[] {"100", String.valueOf(Duration.ofSeconds(60).toNanos()), "1"}));
  }

  @Test
  void shouldCloseWithoutError() {
    // when/then - should not throw
    store.close();
  }
}
