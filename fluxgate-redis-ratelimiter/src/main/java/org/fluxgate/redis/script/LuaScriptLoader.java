package org.fluxgate.redis.script;

import java.io.IOException;
import org.fluxgate.redis.connection.RedisConnectionProvider;

/**
 * Loads Lua scripts from classpath resources and uploads them to Redis.
 *
 * <p>Supports both Standalone and Cluster modes:
 *
 * <ul>
 *   <li>In Standalone mode, scripts are loaded to a single node
 *   <li>In Cluster mode, Lettuce automatically broadcasts SCRIPT LOAD to all master nodes
 * </ul>
 *
 * @deprecated Use {@link LuaScriptRegistry} instead, or simply let {@code RedisTokenBucketStore}
 *     load its own scripts. Every method here writes to the process-wide {@link LuaScripts} slots,
 *     which no part of FluxGate reads any more; the class is kept only so that existing callers
 *     keep compiling and will be removed in a future release.
 */
@Deprecated(since = "0.4.0", forRemoval = true)
public final class LuaScriptLoader {

  private static final String TOKEN_BUCKET_SCRIPT_PATH = LuaScriptRegistry.TOKEN_BUCKET_SCRIPT_PATH;

  private LuaScriptLoader() {
    // Utility class
  }

  /**
   * Load all Lua scripts from resources and upload them to Redis.
   *
   * <p>In cluster mode, the script is automatically distributed to all master nodes.
   *
   * @param connectionProvider Redis connection provider (standalone or cluster)
   * @throws IOException never thrown; kept for binary and source compatibility
   */
  public static void loadScripts(RedisConnectionProvider connectionProvider) throws IOException {
    LuaScriptRegistry registry = new LuaScriptRegistry();
    String sha = connectionProvider.scriptLoad(registry.getTokenBucketConsumeScript());

    LuaScripts.setTokenBucketConsumeScript(registry.getTokenBucketConsumeScript());
    LuaScripts.setTokenBucketConsumeSha(sha);
  }

  /**
   * Load a Lua script from the classpath.
   *
   * @param resourcePath Path to the script resource (e.g., "/lua/token_bucket_consume.lua")
   * @return Script content as a String
   * @throws IOException never thrown; kept for source compatibility
   */
  static String loadScriptFromClasspath(String resourcePath) throws IOException {
    if (!TOKEN_BUCKET_SCRIPT_PATH.equals(resourcePath)) {
      throw new IOException("Lua script not found: " + resourcePath);
    }
    return new LuaScriptRegistry().getTokenBucketConsumeScript();
  }

  /**
   * Check if scripts are loaded.
   *
   * @return true if all scripts are loaded
   */
  public static boolean isLoaded() {
    return LuaScripts.getTokenBucketConsumeSha() != null
        && LuaScripts.getTokenBucketConsumeScript() != null;
  }

  /** Clear loaded scripts (useful for testing). */
  public static void clearScripts() {
    LuaScripts.setTokenBucketConsumeScript(null);
    LuaScripts.setTokenBucketConsumeSha(null);
  }
}
