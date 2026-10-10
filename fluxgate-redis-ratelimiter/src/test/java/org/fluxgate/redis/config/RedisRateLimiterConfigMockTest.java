package org.fluxgate.redis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fluxgate.core.exception.ScriptExecutionException;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link RedisRateLimiterConfig}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisRateLimiterConfigMockTest {

  @Mock private RedisConnectionProvider connectionProvider;

  @Test
  void shouldThrowWhenConnectionProviderIsNull() {
    RedisConnectionProvider nullProvider = null;
    assertThatThrownBy(() -> new RedisRateLimiterConfig(nullProvider))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("connectionProvider must not be null");
  }

  @Test
  void shouldInitializeWithConnectionProvider() {
    // given
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
    when(connectionProvider.scriptLoad(anyString())).thenReturn("test-sha");
    when(connectionProvider.isConnected()).thenReturn(true);

    // when
    RedisRateLimiterConfig config = new RedisRateLimiterConfig(connectionProvider);

    // then
    assertThat(config.getConnectionProvider()).isEqualTo(connectionProvider);
    assertThat(config.getMode()).isEqualTo(RedisMode.STANDALONE);
    assertThat(config.getTokenBucketStore()).isNotNull();
    assertThat(config.isConnected()).isTrue();

    config.close();
  }

  @Test
  void shouldInitializeInClusterMode() {
    // given
    when(connectionProvider.getMode()).thenReturn(RedisMode.CLUSTER);
    when(connectionProvider.scriptLoad(anyString())).thenReturn("cluster-sha");

    // when
    RedisRateLimiterConfig config = new RedisRateLimiterConfig(connectionProvider);

    // then
    assertThat(config.getMode()).isEqualTo(RedisMode.CLUSTER);

    config.close();
  }

  @Test
  @DisplayName("Scripts are uploaded to the supplied connection on construction")
  void shouldLoadLuaScriptsOnConstruction() {
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
    when(connectionProvider.scriptLoad(anyString())).thenReturn("test-sha");

    new RedisRateLimiterConfig(connectionProvider).close();

    verify(connectionProvider, times(2)).scriptLoad(contains("token"));
  }

  @Test
  @DisplayName("An externally supplied connection is never closed by this config")
  void shouldNotCloseAnExternallySuppliedConnectionProvider() {
    // given
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
    when(connectionProvider.scriptLoad(anyString())).thenReturn("test-sha");

    RedisRateLimiterConfig config = new RedisRateLimiterConfig(connectionProvider);
    assertThat(config.ownsConnectionProvider()).isFalse();

    // when
    config.close();

    // then: the caller's Lettuce client keeps running
    verify(connectionProvider, never()).close();
  }

  @Test
  @DisplayName("An invalid max-bucket-ttl is rejected before any connection is opened")
  void shouldValidateTheBucketTtlBeforeConnecting() {
    // TEST-NET-1 drops packets: connecting first would surface as a connection failure instead
    assertThatThrownBy(
            () ->
                new RedisRateLimiterConfig(
                    "redis://192.0.2.1:6379", Duration.ofMillis(200), Duration.ofMillis(500)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxBucketTtl");
  }

  @Test
  @DisplayName("The connector is not even called when max-bucket-ttl is invalid")
  void shouldNotConnectWithAnInvalidBucketTtl() {
    AtomicBoolean connected = new AtomicBoolean();

    assertThatThrownBy(
            () ->
                new RedisRateLimiterConfig(
                    () -> {
                      connected.set(true);
                      return connectionProvider;
                    },
                    Duration.ZERO,
                    "test"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(connected).isFalse();
  }

  @Test
  @DisplayName("A connection this config opened is closed when the stores cannot be built")
  void shouldCloseItsOwnConnectionWhenStoreConstructionFails() {
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
    when(connectionProvider.scriptLoad(anyString()))
        .thenThrow(new ScriptExecutionException("SCRIPT LOAD failed"));

    assertThatThrownBy(() -> new RedisRateLimiterConfig(() -> connectionProvider, null, "test"))
        .isInstanceOf(ScriptExecutionException.class);

    verify(connectionProvider).close();
  }

  @Test
  @DisplayName("A failing close does not hide the construction failure")
  void shouldKeepTheConstructionFailureWhenCloseFails() {
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
    when(connectionProvider.scriptLoad(anyString()))
        .thenThrow(new ScriptExecutionException("SCRIPT LOAD failed"));
    doThrow(new IllegalStateException("close failed")).when(connectionProvider).close();

    assertThatThrownBy(() -> new RedisRateLimiterConfig(() -> connectionProvider, null, "test"))
        .isInstanceOf(ScriptExecutionException.class)
        .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));
  }
}
