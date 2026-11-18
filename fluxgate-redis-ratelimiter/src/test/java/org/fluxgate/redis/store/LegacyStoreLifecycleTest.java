package org.fluxgate.redis.store;

import static org.mockito.Mockito.*;

import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.script.LuaScriptRegistry;
import org.junit.jupiter.api.Test;

class LegacyStoreLifecycleTest {
  @Test
  void legacyCloseNeverClosesExternallyOwnedProvider() {
    RedisConnectionProvider provider = mock(RedisConnectionProvider.class);
    LuaScriptRegistry scripts = mock(LuaScriptRegistry.class);
    when(scripts.isLoaded()).thenReturn(true);
    RedisTokenBucketStore store = new RedisTokenBucketStore(provider, scripts);
    clearInvocations(provider, scripts);
    store.close();
    store.close();
    verifyNoInteractions(provider, scripts);
  }
}
