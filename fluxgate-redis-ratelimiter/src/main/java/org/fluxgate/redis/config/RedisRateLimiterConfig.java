package org.fluxgate.redis.config;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.fluxgate.redis.connection.RedisConnectionFactory;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;
import org.fluxgate.redis.script.LuaScriptLoader;
import org.fluxgate.redis.store.RedisRuleSetStore;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production configuration for Redis-based rate limiter.
 *
 * <p>This class handles:
 *
 * <ul>
 *   <li>Redis connection setup (both Standalone and Cluster modes)
 *   <li>Loading production Lua scripts with all critical fixes
 *   <li>TokenBucketStore initialization
 * </ul>
 *
 * <p>Production features:
 *
 * <ul>
 *   <li>Uses Redis TIME (no clock drift across distributed nodes)
 *   <li>Integer arithmetic only (no precision loss)
 *   <li>Read-only on rejection (fair rate limiting)
 *   <li>TTL safety margin + max cap
 * </ul>
 *
 * <p>Cluster Support:
 *
 * <ul>
 *   <li>Pass comma-separated URIs for cluster mode: "redis://node1:6379,redis://node2:6379"
 *   <li>Or use explicit mode with {@link #RedisRateLimiterConfig(RedisMode, List, Duration)}
 * </ul>
 */
public final class RedisRateLimiterConfig implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(RedisRateLimiterConfig.class);
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

  private final RedisConnectionProvider connectionProvider;
  private final RedisTokenBucketStore tokenBucketStore;
  private final RedisRuleSetStore ruleSetStore;

  /**
   * Create a new RedisRateLimiterConfig with the given Redis URI.
   *
   * <p>Automatically detects cluster mode if multiple URIs are provided (comma-separated).
   *
   * @param redisUri Redis connection URI (e.g., "redis://localhost:6379" for standalone, or
   *     "redis://node1:6379,redis://node2:6379" for cluster)
   * @throws IOException if Lua scripts cannot be loaded
   */
  public RedisRateLimiterConfig(String redisUri) throws IOException {
    this(redisUri, DEFAULT_TIMEOUT);
  }

  /**
   * Create a new RedisRateLimiterConfig with the given Redis URI and timeout.
   *
   * @param redisUri Redis connection URI (standalone or comma-separated cluster nodes)
   * @param timeout connection timeout
   * @throws IOException if Lua scripts cannot be loaded
   */
  public RedisRateLimiterConfig(String redisUri, Duration timeout) throws IOException {
    Objects.requireNonNull(redisUri, "redisUri must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");

    log.info("Initializing Redis RateLimiter with URI: {}", maskPassword(redisUri));

    // Create connection using factory (auto-detects mode)
    this.connectionProvider = RedisConnectionFactory.create(redisUri, timeout);

    // Load production Lua scripts into Redis
    LuaScriptLoader.loadScripts(connectionProvider);

    // Create production token bucket store
    this.tokenBucketStore = new RedisTokenBucketStore(connectionProvider);

    // Create RuleSet store
    this.ruleSetStore = new RedisRuleSetStore(connectionProvider);

    logInitializationSuccess();
  }

  /**
   * Create a new RedisRateLimiterConfig with explicit mode.
   *
   * @param mode Redis mode (STANDALONE or CLUSTER)
   * @param uris list of Redis URIs
   * @param timeout connection timeout
   * @throws IOException if Lua scripts cannot be loaded
   */
  public RedisRateLimiterConfig(RedisMode mode, List<String> uris, Duration timeout)
      throws IOException {
    Objects.requireNonNull(mode, "mode must not be null");
    Objects.requireNonNull(uris, "uris must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");

    if (uris.isEmpty()) {
      throw new IllegalArgumentException("At least one Redis URI is required");
    }

    log.info("Initializing Redis RateLimiter in {} mode with {} node(s)", mode, uris.size());

    // Create connection with explicit mode
    this.connectionProvider = RedisConnectionFactory.create(mode, uris, timeout);

    // Load production Lua scripts into Redis
    LuaScriptLoader.loadScripts(connectionProvider);

    // Create production token bucket store
    this.tokenBucketStore = new RedisTokenBucketStore(connectionProvider);

    // Create RuleSet store
    this.ruleSetStore = new RedisRuleSetStore(connectionProvider);

    logInitializationSuccess();
  }

  /**
   * Create a new RedisRateLimiterConfig with an existing connection provider.
   *
   * <p>Useful for testing or when connection is managed externally.
   *
   * @param connectionProvider the Redis connection provider
   * @throws IOException if Lua scripts cannot be loaded
   */
  public RedisRateLimiterConfig(RedisConnectionProvider connectionProvider) throws IOException {
    this.connectionProvider =
        Objects.requireNonNull(connectionProvider, "connectionProvider must not be null");

    // Load production Lua scripts into Redis
    LuaScriptLoader.loadScripts(connectionProvider);

    // Create production token bucket store
    this.tokenBucketStore = new RedisTokenBucketStore(connectionProvider);

    // Create RuleSet store
    this.ruleSetStore = new RedisRuleSetStore(connectionProvider);

    log.info(
        "Redis RateLimiter initialized with existing connection provider ({})",
        connectionProvider.getMode());
  }

  private void logInitializationSuccess() {
    log.info("Redis RateLimiter initialized successfully");
    log.info("  Mode: {}", connectionProvider.getMode());
    log.info("Production features enabled:");
    log.info("  - Uses Redis TIME (no clock drift)");
    log.info("  - Integer arithmetic only (no precision loss)");
    log.info("  - Read-only on rejection (fair rate limiting)");
    log.info("  - TTL safety margin + max cap");

    if (connectionProvider.getMode() == RedisMode.CLUSTER) {
      List<String> nodes = connectionProvider.clusterNodes();
      log.info("  - Cluster nodes: {}", nodes.size());
    }
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
   * Get the production TokenBucketStore for use in RedisRateLimiter.
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
   */
  public RedisRuleSetStore getRuleSetStore() {
    return ruleSetStore;
  }

  /**
   * Check if the Redis connection is healthy.
   *
   * @return true if connected
   */
  public boolean isConnected() {
    return connectionProvider.isConnected();
  }

  /** Close all resources (connection, client). */
  @Override
  public void close() {
    log.info("Closing Redis RateLimiter resources");

    if (tokenBucketStore != null) {
      tokenBucketStore.close();
    }

    if (connectionProvider != null) {
      connectionProvider.close();
    }

    log.info("Redis RateLimiter resources closed");
  }

  private String maskPassword(String uri) {
    if (uri == null) {
      return null;
    }
    return uri.replaceAll("://[^:]+:[^@]+@", "://***:***@");
  }
}
