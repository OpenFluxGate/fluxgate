package org.fluxgate.redis.script;

/** Container for Lua script content and SHA hashes. */
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
