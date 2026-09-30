package org.fluxgate.redis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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

    verify(connectionProvider).scriptLoad(contains("token"));
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
}
