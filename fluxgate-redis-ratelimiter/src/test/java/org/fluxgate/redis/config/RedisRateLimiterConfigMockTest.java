package org.fluxgate.redis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.IOException;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;
import org.fluxgate.redis.script.LuaScripts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

  @BeforeEach
  void setUp() {
    // Reset LuaScripts state before each test
    LuaScripts.setTokenBucketConsumeSha(null);
    LuaScripts.setTokenBucketConsumeScript(null);
  }

  @AfterEach
  void tearDown() {
    LuaScripts.setTokenBucketConsumeSha(null);
    LuaScripts.setTokenBucketConsumeScript(null);
  }

  @Test
  void shouldThrowWhenConnectionProviderIsNull() {
    RedisConnectionProvider nullProvider = null;
    assertThatThrownBy(() -> new RedisRateLimiterConfig(nullProvider))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("connectionProvider must not be null");
  }

  @Test
  void shouldInitializeWithConnectionProvider() throws IOException {
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
    assertThat(config.getRuleSetStore()).isNotNull();
    assertThat(config.isConnected()).isTrue();

    config.close();
  }

  @Test
  void shouldInitializeInClusterMode() throws IOException {
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
  void shouldCloseConnectionProviderOnClose() throws IOException {
    // given
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
    when(connectionProvider.scriptLoad(anyString())).thenReturn("test-sha");

    RedisRateLimiterConfig config = new RedisRateLimiterConfig(connectionProvider);

    // when
    config.close();

    // then
    verify(connectionProvider).close();
  }
}
