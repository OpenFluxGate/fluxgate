package org.fluxgate.redis.script;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads Lua scripts from classpath resources and uploads them to Redis.
 *
 * <p>Supports both Standalone and Cluster modes:
 *
 * <ul>
 *   <li>In Standalone mode, scripts are loaded to a single node
 *   <li>In Cluster mode, Lettuce automatically broadcasts SCRIPT LOAD to all master nodes
 * </ul>
 */
public final class LuaScriptLoader {

  private static final Logger log = LoggerFactory.getLogger(LuaScriptLoader.class);

  private static final String TOKEN_BUCKET_SCRIPT_PATH = "/lua/token_bucket_consume.lua";

  private LuaScriptLoader() {
    // Utility class
  }

  /**
   * Load all Lua scripts from resources and upload them to Redis.
   *
   * <p>In cluster mode, the script is automatically distributed to all master nodes.
   *
   * @param connectionProvider Redis connection provider (standalone or cluster)
   * @throws IOException if script files cannot be read
   */
  public static void loadScripts(RedisConnectionProvider connectionProvider) throws IOException {
    log.info("Loading Lua scripts into Redis ({} mode)...", connectionProvider.getMode().name());

    // Load token bucket consume script
    String tokenBucketScript = loadScriptFromClasspath(TOKEN_BUCKET_SCRIPT_PATH);
    String sha = connectionProvider.scriptLoad(tokenBucketScript);

    LuaScripts.setTokenBucketConsumeScript(tokenBucketScript);
    LuaScripts.setTokenBucketConsumeSha(sha);

    log.info("Loaded token_bucket_consume.lua with SHA: {}", sha);

    if (connectionProvider.getMode() == RedisConnectionProvider.RedisMode.CLUSTER) {
      log.info("Script automatically distributed to all cluster master nodes");
    }
  }

  /**
   * Load a Lua script from the classpath.
   *
   * @param resourcePath Path to the script resource (e.g., "/lua/token_bucket_consume.lua")
   * @return Script content as a String
   * @throws IOException if the resource cannot be read
   */
  static String loadScriptFromClasspath(String resourcePath) throws IOException {
    try (InputStream inputStream = LuaScriptLoader.class.getResourceAsStream(resourcePath)) {
      if (inputStream == null) {
        throw new IOException("Lua script not found: " + resourcePath);
      }

      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
        return reader.lines().collect(Collectors.joining("\n"));
      }
    }
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
    log.debug("Cleared loaded scripts");
  }
}
