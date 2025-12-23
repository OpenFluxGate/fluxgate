package org.fluxgate.redis.store;

import io.lettuce.core.RedisNoScriptException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
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
  private final AtomicBoolean reloadingLuaScript = new AtomicBoolean(false);
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

    // Execute Lua script with NOSCRIPT fallback
    List<Long> result = executeScriptWithFallback(keys, args);

    // Parse result: [consumed, remaining_tokens, nanos_to_wait, reset_time_millis, is_new_bucket]
    if (result == null || result.size() != 5) {
      log.error("Unexpected Lua script result: {}", result);
      throw new IllegalStateException("Lua script returned invalid result");
    }

    long consumed = result.get(0);
    long remainingTokens = result.get(1);
    long nanosToWait = result.get(2);
    long resetTimeMillis = result.get(3);
    boolean isNewBucket = result.get(4) == 1;

    if (consumed == 1) {
      if (isNewBucket) {
        log.debug(
            "Token bucket CREATED: key={}, capacity={}, consumed={}, remaining={}, ttl={}s",
            bucketKey,
            capacity,
            permits,
            remainingTokens,
            Math.min((long) Math.ceil(windowNanos / 1_000_000_000.0 * 1.1), 86400));
      } else {
        log.debug(
            "Token bucket UPDATED: key={}, consumed={}, remaining={}, reset={}",
            bucketKey,
            permits,
            remainingTokens,
            resetTimeMillis);
      }
      return BucketState.allowed(remainingTokens, resetTimeMillis);
    } else {
      log.debug(
          "Token bucket REJECTED: key={}, remaining={}, waitNanos={}, reset={}",
          bucketKey,
          remainingTokens,
          nanosToWait,
          resetTimeMillis);
      return BucketState.rejected(remainingTokens, nanosToWait, resetTimeMillis);
    }
  }

  /**
   * Executes the Lua script with NOSCRIPT error fallback.
   *
   * <p>This method handles the case where Redis has been restarted and the script cache is lost.
   * When EVALSHA fails with NOSCRIPT error, it falls back to:
   *
   * <ol>
   *   <li>Execute using EVAL (slower but works)
   *   <li>Reload the script into Redis cache for future calls
   * </ol>
   *
   * @param keys the keys for the script
   * @param args the arguments for the script
   * @return the script execution result
   */
  private List<Long> executeScriptWithFallback(String[] keys, String[] args) {
    String sha = LuaScripts.getTokenBucketConsumeSha();
    String script = LuaScripts.getTokenBucketConsumeScript();

    try {
      // Try EVALSHA first (efficient, uses cached script)
      return connectionProvider.evalsha(sha, keys, args);
    } catch (RedisNoScriptException e) {
      // Script not in Redis cache (e.g., Redis was restarted)
      log.warn(
          "Lua script not found in Redis cache (NOSCRIPT). "
              + "Falling back to EVAL and reloading script. SHA: {}",
          sha);

      // Fallback 1 - Execute using EVAL (slower but works immediately)
      List<Long> result = connectionProvider.eval(script, keys, args);

      // Fallback 2 - Reload script for future calls (thread-safe with AtomicBoolean)
      reloadScript();

      return result;
    }
  }

  /**
   * Reloads the Lua script into Redis cache.
   *
   * <p>This is called after a NOSCRIPT error to restore the script cache. Uses AtomicBoolean to
   * prevent multiple concurrent reload attempts.
   */
  private void reloadScript() {
    if (!reloadingLuaScript.compareAndSet(false, true)) {
      log.debug("Lua script is already being reloaded, skipping...");
      return;
    }

    try {
      String script = LuaScripts.getTokenBucketConsumeScript();
      String sha = connectionProvider.scriptLoad(script);
      LuaScripts.setTokenBucketConsumeSha(sha);
      log.info("Lua script reloaded into Redis. SHA: {}", sha);
    } catch (Exception e) {
      log.error("Failed to reload Lua script: {}", e.getMessage(), e);
    } finally {
      reloadingLuaScript.set(false);
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

  /**
   * Deletes all token buckets matching the given ruleSetId pattern.
   *
   * <p>This is used when rules are changed to reset rate limit state. The pattern matches keys
   * like: {@code fluxgate:{ruleSetId}:*}
   *
   * <p>Uses SCAN command which is production-safe (non-blocking, incremental scanning).
   *
   * @param ruleSetId the rule set ID to match
   * @return the number of buckets deleted
   */
  public long deleteBucketsByRuleSetId(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");

    String pattern = "fluxgate:" + ruleSetId + ":*";
    log.debug("Scanning token buckets matching pattern: {}", pattern);

    java.util.List<String> keys = connectionProvider.scan(pattern);
    if (keys.isEmpty()) {
      log.debug("No token buckets found for ruleSetId: {}", ruleSetId);
      return 0;
    }

    long deleted = connectionProvider.del(keys.toArray(new String[0]));
    log.info("Deleted {} token buckets for ruleSetId: {}", deleted, ruleSetId);
    return deleted;
  }

  /**
   * Deletes all token buckets (full reset).
   *
   * <p>This is used when a full reload is triggered to reset all rate limit state. The pattern
   * matches all FluxGate keys: {@code fluxgate:*}
   *
   * <p>Uses SCAN command which is production-safe (non-blocking, incremental scanning).
   *
   * @return the number of buckets deleted
   */
  public long deleteAllBuckets() {
    String pattern = "fluxgate:*";
    log.debug("Scanning all token buckets matching pattern: {}", pattern);

    java.util.List<String> keys = connectionProvider.scan(pattern);
    if (keys.isEmpty()) {
      log.debug("No token buckets found");
      return 0;
    }

    long deleted = connectionProvider.del(keys.toArray(new String[0]));
    log.info("Deleted {} token buckets (full reset)", deleted);
    return deleted;
  }

  /** Closes the store. Note: The connection provider is managed externally. */
  public void close() {
    // Connection provider is managed externally, so nothing to do here
    log.debug("RedisTokenBucketStore closed");
  }
}
