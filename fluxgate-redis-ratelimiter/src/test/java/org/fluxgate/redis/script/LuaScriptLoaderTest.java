package org.fluxgate.redis.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.IOException;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link LuaScriptLoader}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LuaScriptLoaderTest {

  @Mock private RedisConnectionProvider connectionProvider;

  @BeforeEach
  void setUp() {
    // Reset LuaScripts state
    LuaScripts.setTokenBucketConsumeSha(null);
    LuaScripts.setTokenBucketConsumeScript(null);
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
  }

  @AfterEach
  void tearDown() {
    LuaScripts.setTokenBucketConsumeSha(null);
    LuaScripts.setTokenBucketConsumeScript(null);
  }

  @Test
  void shouldLoadScriptsSuccessfully() throws IOException {
    // given
    when(connectionProvider.scriptLoad(anyString())).thenReturn("sha256-hash");

    // when
    LuaScriptLoader.loadScripts(connectionProvider);

    // then
    assertThat(LuaScripts.getTokenBucketConsumeSha()).isEqualTo("sha256-hash");
    assertThat(LuaScripts.getTokenBucketConsumeScript()).isNotNull();
    assertThat(LuaScripts.getTokenBucketConsumeScript()).contains("KEYS[1]");
    verify(connectionProvider).scriptLoad(anyString());
  }

  @Test
  void shouldLoadScriptsInClusterMode() throws IOException {
    // given
    when(connectionProvider.getMode()).thenReturn(RedisMode.CLUSTER);
    when(connectionProvider.scriptLoad(anyString())).thenReturn("cluster-sha256");

    // when
    LuaScriptLoader.loadScripts(connectionProvider);

    // then
    assertThat(LuaScripts.getTokenBucketConsumeSha()).isEqualTo("cluster-sha256");
    assertThat(LuaScripts.getTokenBucketConsumeScript()).isNotNull();
    verify(connectionProvider).scriptLoad(anyString());
  }

  @Test
  void shouldReturnFalseWhenNotLoaded() {
    assertThat(LuaScriptLoader.isLoaded()).isFalse();
  }

  @Test
  void shouldReturnTrueWhenLoaded() throws IOException {
    // given
    when(connectionProvider.scriptLoad(anyString())).thenReturn("test-sha");

    // when
    LuaScriptLoader.loadScripts(connectionProvider);

    // then
    assertThat(LuaScriptLoader.isLoaded()).isTrue();
  }

  @Test
  void shouldReturnFalseWhenOnlyShaSet() {
    LuaScripts.setTokenBucketConsumeSha("test-sha");

    assertThat(LuaScriptLoader.isLoaded()).isFalse();
  }

  @Test
  void shouldReturnFalseWhenOnlyScriptSet() {
    LuaScripts.setTokenBucketConsumeScript("test-script");

    assertThat(LuaScriptLoader.isLoaded()).isFalse();
  }

  @Test
  void shouldClearScripts() throws IOException {
    // given - load scripts first
    when(connectionProvider.scriptLoad(anyString())).thenReturn("test-sha");
    LuaScriptLoader.loadScripts(connectionProvider);
    assertThat(LuaScriptLoader.isLoaded()).isTrue();

    // when
    LuaScriptLoader.clearScripts();

    // then
    assertThat(LuaScriptLoader.isLoaded()).isFalse();
    assertThat(LuaScripts.getTokenBucketConsumeSha()).isNull();
    assertThat(LuaScripts.getTokenBucketConsumeScript()).isNull();
  }

  @Test
  void shouldLoadScriptFromClasspath() throws IOException {
    // when
    String script = LuaScriptLoader.loadScriptFromClasspath("/lua/token_bucket_consume.lua");

    // then
    assertThat(script).isNotNull();
    assertThat(script).contains("KEYS[1]");
    assertThat(script).contains("redis.call");
  }
}
