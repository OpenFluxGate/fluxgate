package org.fluxgate.redis.store;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.List;
import java.util.Objects;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.redis.script.LuaScriptLoader;
import org.fluxgate.redis.script.LuaScripts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production-ready Redis-backed implementation of token bucket storage.
 *
 * <p>KEY IMPROVEMENTS: 1. Uses Redis TIME (not System.nanoTime()) - eliminates clock drift across
 * distributed nodes 2. Integer arithmetic only (no floating point) - eliminates precision loss 3.
 * Doesn't update state on rejection - prevents unfair rate limiting 4. Returns reset time - for
 * HTTP X-RateLimit-Reset header 5. TTL safety margin + max cap - prevents premature expiration and
 * runaway TTLs
 *
 * <p>Thread-safe and suitable for distributed environments with multiple API gateway nodes.
 */
public class RedisTokenBucketStore {

  private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketStore.class);

  private final RedisCommands<String, String> redisCommands;

  public RedisTokenBucketStore(RedisCommands<String, String> redisCommands) {
    this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands must not be null");

    // Ensure scripts are loaded
    if (!LuaScriptLoader.isLoaded()) {
      throw new IllegalStateException(
          "Lua scripts not loaded. Call LuaScriptLoader.loadScripts() first.");
    }
  }

  /**
   * Try to consume permits from a token bucket.
   *
   * <p>This method: 1. Executes production Lua script atomically in Redis 2. Lua script uses Redis
   * TIME (not Java time) - solves clock drift 3. Lua script uses integer arithmetic - no precision
   * loss 4. On rejection, state is NOT modified - fair rate limiting
   *
   * @param bucketKey Unique key for the bucket
   * @param band Rate limit band configuration (capacity, window)
   * @param permits Number of permits to consume
   * @return BucketState with consumption result, remaining tokens, wait time, and reset time
   */
  public BucketState tryConsume(String bucketKey, RateLimitBand band, long permits) {
    Objects.requireNonNull(bucketKey, "bucketKey must not be null");
    Objects.requireNonNull(band, "band must not be null");

    if (permits <= 0) {
      throw new IllegalArgumentException("permits must be > 0");
    }

    // Extract band parameters
    long capacity = band.getCapacity();
    long windowNanos = band.getWindow().toNanos();

    // CRITICAL: Do NOT pass System.nanoTime() - Lua script uses Redis TIME instead!
    // This solves clock drift across distributed nodes.

    // Build arguments for Lua script
    // KEYS[1] = bucketKey
    // ARGV[1] = capacity
    // ARGV[2] = window_nanos
    // ARGV[3] = permits
    // (No timestamp needed - Lua gets it from Redis TIME)
    String[] keys = {bucketKey};
    String[] args = {
      String.valueOf(capacity), String.valueOf(windowNanos), String.valueOf(permits)
    };

    // Get cached SHA from script loader
    String sha = LuaScripts.getTokenBucketConsumeSha();

    // Execute Lua script using EVALSHA (more efficient than EVAL)
    List<Long> result = redisCommands.evalsha(sha, ScriptOutputType.MULTI, keys, args);

    // Parse result: [consumed, remaining_tokens, nanos_to_wait, reset_time_millis]
    if (result == null || result.size() != 4) {
      log.error("Unexpected Lua script result: {}", result);
      throw new IllegalStateException("Lua script returned invalid result");
    }

    long consumed = result.get(0);
    long remainingTokens = result.get(1);
    long nanosToWait = result.get(2);
    long resetTimeMillis = result.get(3);

    if (consumed == 1) {
      log.debug(
          "Token bucket {}: consumed {} permits, {} remaining, reset at {}",
          bucketKey,
          permits,
          remainingTokens,
          resetTimeMillis);
      return BucketState.allowed(remainingTokens, resetTimeMillis);
    } else {
      log.debug(
          "Token bucket {}: rejected (not enough tokens), {} remaining, wait {} ns, reset at {}",
          bucketKey,
          remainingTokens,
          nanosToWait,
          resetTimeMillis);
      return BucketState.rejected(remainingTokens, nanosToWait, resetTimeMillis);
    }
  }

  public void close() {
    // Lettuce connection is managed externally, so nothing to do here
    log.debug("RedisTokenBucketStore closed");
  }
}
