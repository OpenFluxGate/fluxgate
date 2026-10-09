package org.fluxgate.redis.config;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.fluxgate.redis.connection.RedisConnectionFactory;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;
import org.fluxgate.redis.connection.RedisUriUtils;
import org.fluxgate.redis.store.RedisRuleSetStore;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration entry point for the Redis-based rate limiter.
 *
 * <p>This class handles:
 *
 * <ul>
 *   <li>Redis connection setup (both Standalone and Cluster modes)
 *   <li>Loading the Lua scripts into that Redis
 *   <li>TokenBucketStore initialization
 * </ul>
 *
 * <p>Cluster Support:
 *
 * <ul>
 *   <li>Pass comma-separated URIs for cluster mode: "redis://node1:6379,redis://node2:6379"
 *   <li>Or use explicit mode with {@link #RedisRateLimiterConfig(RedisMode, List, Duration)}
 * </ul>
 *
 * <p><strong>Ownership.</strong> A connection this class created is closed by {@link #close()}. A
 * connection handed in through {@link #RedisRateLimiterConfig(RedisConnectionProvider)} belongs to
 * the caller and is left open, so closing this config never shuts down a Lettuce client other parts
 * of the application still use.
 */
@SuppressWarnings("deprecation") // still exposes the deprecated RedisRuleSetStore
public final class RedisRateLimiterConfig implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(RedisRateLimiterConfig.class);

  private final RedisConnectionProvider connectionProvider;
  private final RedisTokenBucketStore tokenBucketStore;
  private final RedisRuleSetStore ruleSetStore;
  private final boolean ownsConnectionProvider;

  /**
   * Create a new RedisRateLimiterConfig with the given Redis URI.
   *
   * <p>Automatically detects cluster mode if multiple URIs are provided (comma-separated).
   *
   * @param redisUri Redis connection URI (e.g., "redis://localhost:6379" for standalone, or
   *     "redis://node1:6379,redis://node2:6379" for cluster)
   * @throws org.fluxgate.core.exception.ScriptExecutionException if the Lua scripts cannot be read
   *     or uploaded
   */
  public RedisRateLimiterConfig(String redisUri) {
    this(redisUri, RedisUriUtils.DEFAULT_TIMEOUT);
  }

  /**
   * Create a new RedisRateLimiterConfig with the given Redis URI and timeout.
   *
   * @param redisUri Redis connection URI (standalone or comma-separated cluster nodes)
   * @param timeout connection timeout
   * @throws org.fluxgate.core.exception.ScriptExecutionException if the Lua scripts cannot be read
   *     or uploaded
   */
  public RedisRateLimiterConfig(String redisUri, Duration timeout) {
    this(redisUri, timeout, null);
  }

  /**
   * Create a new RedisRateLimiterConfig with the given Redis URI, timeout and bucket TTL cap.
   *
   * @param redisUri Redis connection URI (standalone or comma-separated cluster nodes)
   * @param timeout connection timeout
   * @param maxBucketTtl upper bound on every bucket TTL, or null for {@link
   *     RedisTokenBucketStore#DEFAULT_MAX_BUCKET_TTL}
   * @throws org.fluxgate.core.exception.ScriptExecutionException if the Lua scripts cannot be read
   *     or uploaded
   */
  public RedisRateLimiterConfig(String redisUri, Duration timeout, Duration maxBucketTtl) {
    Objects.requireNonNull(redisUri, "redisUri must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");

    this.connectionProvider = RedisConnectionFactory.create(redisUri, timeout);
    this.ownsConnectionProvider = true;
    this.tokenBucketStore = new RedisTokenBucketStore(connectionProvider, maxBucketTtl);
    this.ruleSetStore = new RedisRuleSetStore(connectionProvider);

    logInitialized(RedisUriUtils.mask(redisUri));
  }

  /**
   * Create a new RedisRateLimiterConfig with explicit mode.
   *
   * @param mode Redis mode (STANDALONE or CLUSTER)
   * @param uris list of Redis URIs
   * @param timeout connection timeout
   * @throws org.fluxgate.core.exception.ScriptExecutionException if the Lua scripts cannot be read
   *     or uploaded
   */
  public RedisRateLimiterConfig(RedisMode mode, List<String> uris, Duration timeout) {
    this(mode, uris, timeout, null);
  }

  /**
   * Create a new RedisRateLimiterConfig with explicit mode and a bucket TTL cap.
   *
   * @param mode Redis mode (STANDALONE or CLUSTER)
   * @param uris list of Redis URIs
   * @param timeout connection timeout
   * @param maxBucketTtl upper bound on every bucket TTL, or null for {@link
   *     RedisTokenBucketStore#DEFAULT_MAX_BUCKET_TTL}
   * @throws org.fluxgate.core.exception.ScriptExecutionException if the Lua scripts cannot be read
   *     or uploaded
   */
  public RedisRateLimiterConfig(
      RedisMode mode, List<String> uris, Duration timeout, Duration maxBucketTtl) {
    Objects.requireNonNull(mode, "mode must not be null");
    Objects.requireNonNull(uris, "uris must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");

    if (uris.isEmpty()) {
      throw new IllegalArgumentException("At least one Redis URI is required");
    }

    this.connectionProvider = RedisConnectionFactory.create(mode, uris, timeout);
    this.ownsConnectionProvider = true;
    this.tokenBucketStore = new RedisTokenBucketStore(connectionProvider, maxBucketTtl);
    this.ruleSetStore = new RedisRuleSetStore(connectionProvider);

    logInitialized(RedisUriUtils.mask(String.join(",", uris)));
  }

  /**
   * Create a new RedisRateLimiterConfig with an existing connection provider.
   *
   * <p>Useful for testing or when the connection is managed externally. The provider is
   * <em>not</em> closed by {@link #close()}.
   *
   * @param connectionProvider the Redis connection provider
   * @throws org.fluxgate.core.exception.ScriptExecutionException if the Lua scripts cannot be read
   *     or uploaded
   */
  public RedisRateLimiterConfig(RedisConnectionProvider connectionProvider) {
    this(connectionProvider, (Duration) null);
  }

  /**
   * Create a new RedisRateLimiterConfig with an existing connection provider and a TTL cap.
   *
   * <p>The provider is <em>not</em> closed by {@link #close()}.
   *
   * @param connectionProvider the Redis connection provider
   * @param maxBucketTtl upper bound on every bucket TTL, or null for {@link
   *     RedisTokenBucketStore#DEFAULT_MAX_BUCKET_TTL}
   * @throws org.fluxgate.core.exception.ScriptExecutionException if the Lua scripts cannot be read
   *     or uploaded
   */
  public RedisRateLimiterConfig(RedisConnectionProvider connectionProvider, Duration maxBucketTtl) {
    this.connectionProvider =
        Objects.requireNonNull(connectionProvider, "connectionProvider must not be null");
    this.ownsConnectionProvider = false;
    this.tokenBucketStore = new RedisTokenBucketStore(connectionProvider, maxBucketTtl);
    this.ruleSetStore = new RedisRuleSetStore(connectionProvider);

    logInitialized("externally managed connection");
  }

  private void logInitialized(String endpoint) {
    log.info(
        "FluxGate Redis rate limiter initialized ({} mode, {})",
        connectionProvider.getMode(),
        endpoint);
  }

  /**
   * Get the Redis connection mode.
   *
   * @return STANDALONE or CLUSTER
   */
  public RedisMode getMode() {
    return connectionProvider.getMode();
  }

  /**
   * Get the connection provider.
   *
   * @return the Redis connection provider
   */
  public RedisConnectionProvider getConnectionProvider() {
    return connectionProvider;
  }

  /**
   * Get the TokenBucketStore for use in RedisRateLimiter.
   *
   * @return the token bucket store
   */
  public RedisTokenBucketStore getTokenBucketStore() {
    return tokenBucketStore;
  }

  /**
   * Get the RuleSet store for storing RuleSet configurations in Redis.
   *
   * @return the rule set store
   * @deprecated the Redis rule set store cannot express the core rule model; see {@link
   *     RedisRuleSetStore}
   */
  @Deprecated(since = "0.4.0")
  public RedisRuleSetStore getRuleSetStore() {
    return ruleSetStore;
  }

  /**
   * Whether {@link #close()} closes the connection provider.
   *
   * @return true when this config created the connection itself
   */
  public boolean ownsConnectionProvider() {
    return ownsConnectionProvider;
  }

  /**
   * Check if the Redis connection is healthy.
   *
   * @return true if connected
   */
  public boolean isConnected() {
    return connectionProvider.isConnected();
  }

  /**
   * Closes the resources this config owns.
   *
   * <p>A connection supplied through {@link #RedisRateLimiterConfig(RedisConnectionProvider)} is
   * left open: it belongs to whoever created it.
   */
  @Override
  public void close() {
    if (!ownsConnectionProvider) {
      log.debug("Leaving the externally managed Redis connection open");
      return;
    }

    log.info("Closing Redis rate limiter resources");
    connectionProvider.close();
  }
}
