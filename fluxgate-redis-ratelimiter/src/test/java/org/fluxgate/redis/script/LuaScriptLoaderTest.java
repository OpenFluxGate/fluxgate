package org.fluxgate.redis.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.lettuce.core.api.sync.RedisCommands;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link LuaScriptLoader}. */
@ExtendWith(MockitoExtension.class)
class LuaScriptLoaderTest {

  @Mock private RedisCommands<String, String> redisCommands;

  @BeforeEach
  void setUp() {
    // Reset LuaScripts state
    LuaScripts.setTokenBucketConsumeSha(null);
    LuaScripts.setTokenBucketConsumeScript(null);
  }

  @AfterEach
  void tearDown() {
    LuaScripts.setTokenBucketConsumeSha(null);
    LuaScripts.setTokenBucketConsumeScript(null);
  }

  @Test
  void shouldLoadScriptsSuccessfully() throws IOException {
    // given
    when(redisCommands.scriptLoad(anyString())).thenReturn("sha256-hash");

    // when
    LuaScriptLoader.loadScripts(redisCommands);

    // then
    assertThat(LuaScripts.getTokenBucketConsumeSha()).isEqualTo("sha256-hash");
    assertThat(LuaScripts.getTokenBucketConsumeScript()).isNotNull();
    assertThat(LuaScripts.getTokenBucketConsumeScript()).contains("KEYS[1]");
    verify(redisCommands).scriptLoad(anyString());
  }

  @Test
  void shouldReturnFalseWhenNotLoaded() {
    assertThat(LuaScriptLoader.isLoaded()).isFalse();
  }

  @Test
  void shouldReturnTrueWhenLoaded() throws IOException {
    // given
    when(redisCommands.scriptLoad(anyString())).thenReturn("test-sha");

    // when
    LuaScriptLoader.loadScripts(redisCommands);

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
}
