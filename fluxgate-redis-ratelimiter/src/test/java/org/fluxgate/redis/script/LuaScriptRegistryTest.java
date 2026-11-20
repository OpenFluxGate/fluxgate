package org.fluxgate.redis.script;

import static org.assertj.core.api.Assertions.assertThat;
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

/** Unit tests for {@link LuaScriptRegistry}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LuaScriptRegistryTest {

  @Mock private RedisConnectionProvider connectionProvider;

  @Mock private RedisConnectionProvider otherConnectionProvider;

  @Test
  void shouldReadTheScriptFromTheClasspath() {
    LuaScriptRegistry registry = new LuaScriptRegistry();

    assertThat(registry.getTokenBucketConsumeScript())
        .contains("KEYS[1")
        .contains("redis.call")
        .contains("last_refill_micros");
    assertThat(registry.isLoaded()).isFalse();
    assertThat(registry.getTokenBucketConsumeSha()).isNull();
  }

  @Test
  void shouldRememberTheShaAfterLoading() {
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
    when(connectionProvider.scriptLoad(anyString())).thenReturn("sha-1");

    LuaScriptRegistry registry = new LuaScriptRegistry();
    String sha = registry.loadInto(connectionProvider);

    assertThat(sha).isEqualTo("sha-1");
    assertThat(registry.getTokenBucketConsumeSha()).isEqualTo("sha-1");
    assertThat(registry.isLoaded()).isTrue();
    verify(connectionProvider).scriptLoad(registry.getTokenBucketConsumeScript());
  }

  @Test
  @DisplayName("Two registries keep independent SHAs (M27 / H-8)")
  void twoRegistriesShouldNotShareState() {
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
    when(connectionProvider.scriptLoad(anyString())).thenReturn("sha-a");
    when(otherConnectionProvider.getMode()).thenReturn(RedisMode.CLUSTER);
    when(otherConnectionProvider.scriptLoad(anyString())).thenReturn("sha-b");

    LuaScriptRegistry first = new LuaScriptRegistry();
    LuaScriptRegistry second = new LuaScriptRegistry();

    first.loadInto(connectionProvider);
    second.loadInto(otherConnectionProvider);

    // The second load used to overwrite the first one's SHA through a static slot.
    assertThat(first.getTokenBucketConsumeSha()).isEqualTo("sha-a");
    assertThat(second.getTokenBucketConsumeSha()).isEqualTo("sha-b");
  }

  @Test
  void shouldAllowTheShaToBeReplacedAfterANoscriptRecovery() {
    LuaScriptRegistry registry = new LuaScriptRegistry();
    registry.setTokenBucketConsumeSha("new-sha");

    assertThat(registry.getTokenBucketConsumeSha()).isEqualTo("new-sha");
  }
}
