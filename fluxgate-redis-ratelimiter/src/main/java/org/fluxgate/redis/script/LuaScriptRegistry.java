package org.fluxgate.redis.script;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.stream.Collectors;
import org.fluxgate.core.exception.ScriptExecutionException;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the Lua scripts of one store together with the SHA each of them has on <em>its own</em>
 * Redis.
 *
 * <p>Script bodies are read from the classpath when the registry is created; the SHA is filled in
 * by {@link #loadInto(RedisConnectionProvider)}. Both live in instance state, so two stores
 * pointing at different Redis deployments in one JVM no longer overwrite each other's SHA the way
 * the former process-wide {@link LuaScripts} did.
 *
 * <p>Instances are thread-safe: the script body is immutable and the SHA is volatile.
 */
public final class LuaScriptRegistry {

  private static final Logger log = LoggerFactory.getLogger(LuaScriptRegistry.class);

  /** Classpath location of the multi-band token bucket script. */
  public static final String TOKEN_BUCKET_SCRIPT_PATH = "/lua/token_bucket_consume.lua";

  private final String tokenBucketConsumeScript;

  private volatile String tokenBucketConsumeSha;

  /**
   * Reads every FluxGate Lua script from the classpath.
   *
   * @throws ScriptExecutionException if a script resource is missing or cannot be read
   */
  public LuaScriptRegistry() {
    this.tokenBucketConsumeScript = readScript(TOKEN_BUCKET_SCRIPT_PATH);
  }

  /**
   * Returns the body of the token bucket consume script.
   *
   * @return the script content (never null)
   */
  public String getTokenBucketConsumeScript() {
    return tokenBucketConsumeScript;
  }

  /**
   * Returns the SHA1 the token bucket consume script has on the Redis it was last loaded into.
   *
   * @return the SHA1 hash, or null if {@link #loadInto(RedisConnectionProvider)} has not run yet
   */
  public String getTokenBucketConsumeSha() {
    return tokenBucketConsumeSha;
  }

  /**
   * Replaces the cached SHA1 of the token bucket consume script.
   *
   * <p>Called after a NOSCRIPT recovery has re-uploaded the script.
   *
   * @param sha the new SHA1 hash
   */
  public void setTokenBucketConsumeSha(String sha) {
    this.tokenBucketConsumeSha = sha;
  }

  /**
   * Uploads every script to the given Redis and remembers the SHAs it returns.
   *
   * <p>In cluster mode Lettuce broadcasts {@code SCRIPT LOAD} to all master nodes.
   *
   * @param connectionProvider the Redis connection provider (standalone or cluster)
   * @return the SHA1 hash of the token bucket consume script
   */
  public String loadInto(RedisConnectionProvider connectionProvider) {
    Objects.requireNonNull(connectionProvider, "connectionProvider must not be null");

    String sha = connectionProvider.scriptLoad(tokenBucketConsumeScript);
    this.tokenBucketConsumeSha = sha;

    log.info(
        "Loaded token_bucket_consume.lua into Redis ({} mode) with SHA: {}",
        connectionProvider.getMode(),
        sha);
    return sha;
  }

  /**
   * Checks whether the scripts have a known SHA.
   *
   * @return true if every script has been loaded into a Redis
   */
  public boolean isLoaded() {
    return tokenBucketConsumeSha != null;
  }

  private static String readScript(String resourcePath) {
    try (InputStream inputStream = LuaScriptRegistry.class.getResourceAsStream(resourcePath)) {
      if (inputStream == null) {
        throw new ScriptExecutionException(
            "Lua script not found on the classpath: " + resourcePath);
      }

      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
        return reader.lines().collect(Collectors.joining("\n"));
      }
    } catch (IOException e) {
      throw new ScriptExecutionException("Failed to read Lua script: " + resourcePath, e);
    }
  }
}
