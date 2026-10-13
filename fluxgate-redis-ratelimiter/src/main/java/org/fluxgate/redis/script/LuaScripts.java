package org.fluxgate.redis.script;

/**
 * Container for Lua script content and SHA hashes.
 *
 * @deprecated Use {@link LuaScriptRegistry} instead. The static slots here are process-wide, so two
 *     {@code RedisTokenBucketStore} instances pointing at different Redis deployments overwrite
 *     each other's SHA. Nothing inside FluxGate reads this class any more; it is kept only so that
 *     existing callers keep compiling and will be removed in a future release.
 */
@Deprecated(since = "0.4.0", forRemoval = true)
public final class LuaScripts {

  /**
   * SHA-256 hash of the token bucket consume script. This is set after the script is loaded into
   * Redis.
   */
  private static volatile String tokenBucketConsumeSha;

  /**
   * The actual Lua script content for token bucket consume. Loaded from
   * resources/lua/token_bucket_consume.lua
   */
  private static volatile String tokenBucketConsumeScript;

  private LuaScripts() {
    // Utility class
  }

  public static String getTokenBucketConsumeSha() {
    return tokenBucketConsumeSha;
  }

  public static void setTokenBucketConsumeSha(String sha) {
    tokenBucketConsumeSha = sha;
  }

  public static String getTokenBucketConsumeScript() {
    return tokenBucketConsumeScript;
  }

  public static void setTokenBucketConsumeScript(String script) {
    tokenBucketConsumeScript = script;
  }
}
