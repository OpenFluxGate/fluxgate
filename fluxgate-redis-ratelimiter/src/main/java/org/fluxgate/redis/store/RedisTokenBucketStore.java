package org.fluxgate.redis.store;

import java.util.List;
import java.util.Objects;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.script.LuaScriptLoader;
import org.fluxgate.redis.script.LuaScripts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production-ready Redis-backed implementation of token bucket storage.
 *
 * <p>KEY IMPROVEMENTS:
 *
 * <ol>
 *   <li>Uses Redis TIME (not System.nanoTime()) - eliminates clock drift across distributed nodes
 *   <li>Integer arithmetic only (no floating point) - eliminates precision loss
 *   <li>Doesn't update state on rejection - prevents unfair rate limiting
 *   <li>Returns reset time - for HTTP X-RateLimit-Reset header
 *   <li>TTL safety margin + max cap - prevents premature expiration and runaway TTLs
 * </ol>
 *
 * <p>Thread-safe and suitable for distributed environments with multiple API gateway nodes.
 *
 * <p>Supports both Standalone and Cluster Redis deployments via {@link RedisConnectionProvider}.
 */
public class RedisTokenBucketStore {

  private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketStore.class);

  private final RedisConnectionProvider connectionProvider;

  /**
   * Creates a new RedisTokenBucketStore with the given connection provider.
   *
   * @param connectionProvider the Redis connection provider (standalone or cluster)
   */
  public RedisTokenBucketStore(RedisConnectionProvider connectionProvider) {
    this.connectionProvider =
        Objects.requireNonNull(connectionProvider, "connectionProvider must not be null");

    // Ensure scripts are loaded
    if (!LuaScriptLoader.isLoaded()) {
      throw new IllegalStateException(
          "Lua scripts not loaded. Call LuaScriptLoader.loadScripts() first.");
    }
  }

  /**
   * Try to consume permits from a token bucket.
   *
   * <p>This method:
   *
   * <ol>
   *   <li>Executes production Lua script atomically in Redis
   *   <li>Lua script uses Redis TIME (not Java time) - solves clock drift
   *   <li>Lua script uses integer arithmetic - no precision loss
   *   <li>On rejection, state is NOT modified - fair rate limiting
   * </ol>
   *
   * <p>In cluster mode, the request is automatically routed to the correct node based on the bucket
   * key's hash slot.
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
    // In cluster mode, Lettuce automatically routes to the correct node based on KEYS[1]
    List<Long> result = connectionProvider.evalsha(sha, keys, args);

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

  /**
   * Gets the Redis connection mode.
   *
   * @return STANDALONE or CLUSTER
   */
  public RedisConnectionProvider.RedisMode getMode() {
    return connectionProvider.getMode();
  }

  /** Closes the store. Note: The connection provider is managed externally. */
  public void close() {
    // Connection provider is managed externally, so nothing to do here
    log.debug("RedisTokenBucketStore closed");
  }
}
