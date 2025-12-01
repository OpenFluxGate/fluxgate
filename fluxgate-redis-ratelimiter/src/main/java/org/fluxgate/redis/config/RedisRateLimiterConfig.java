package org.fluxgate.redis.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.fluxgate.redis.script.LuaScriptLoader;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.fluxgate.redis.store.TokenBucketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for Redis-based rate limiter.
 * <p>
 * This class handles:
 * - Redis connection setup
 * - Lua script loading
 * - TokenBucketStore initialization
 */
public final class RedisRateLimiterConfig implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiterConfig.class);

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> syncCommands;
    private final TokenBucketStore tokenBucketStore;

    /**
     * Create a new RedisRateLimiterConfig with the given Redis URI.
     *
     * @param redisUri Redis connection URI (e.g., "redis://localhost:6379")
     * @throws IOException if Lua scripts cannot be loaded
     */
    public RedisRateLimiterConfig(String redisUri) throws IOException {
        this(RedisURI.create(Objects.requireNonNull(redisUri, "redisUri must not be null")));
    }

    /**
     * Create a new RedisRateLimiterConfig with the given RedisURI.
     *
     * @param redisUri Redis connection URI
     * @throws IOException if Lua scripts cannot be loaded
     */
    public RedisRateLimiterConfig(RedisURI redisUri) throws IOException {
        Objects.requireNonNull(redisUri, "redisUri must not be null");

        log.info("Initializing Redis RateLimiter with URI: {}", redisUri);

        // Create Redis client
        this.redisClient = RedisClient.create(redisUri);

        // Configure client options (optional, customize as needed)
        this.redisClient.setDefaultTimeout(Duration.ofSeconds(5));

        // Open connection
        this.connection = redisClient.connect();
        this.syncCommands = connection.sync();

        // Load Lua scripts into Redis
        LuaScriptLoader.loadScripts(syncCommands);

        // Create token bucket store
        this.tokenBucketStore = new RedisTokenBucketStore(syncCommands);

        log.info("Redis RateLimiter initialized successfully");
    }

    /**
     * Create a configuration with an existing RedisClient.
     * <p>
     * Useful if you already have a RedisClient configured elsewhere.
     *
     * @param redisClient Existing RedisClient instance
     * @throws IOException if Lua scripts cannot be loaded
     */
    public RedisRateLimiterConfig(RedisClient redisClient) throws IOException {
        this.redisClient = Objects.requireNonNull(redisClient, "redisClient must not be null");
        this.connection = redisClient.connect();
        this.syncCommands = connection.sync();

        // Load Lua scripts
        LuaScriptLoader.loadScripts(syncCommands);

        // Create token bucket store
        this.tokenBucketStore = new RedisTokenBucketStore(syncCommands);

        log.info("Redis RateLimiter initialized with existing RedisClient");
    }

    /**
     * Get the TokenBucketStore for use in RedisRateLimiter.
     */
    public TokenBucketStore getTokenBucketStore() {
        return tokenBucketStore;
    }

    /**
     * Get the synchronous Redis commands interface.
     * <p>
     * Exposed for advanced use cases.
     */
    public RedisCommands<String, String> getSyncCommands() {
        return syncCommands;
    }

    /**
     * Get the Redis connection.
     * <p>
     * Exposed for advanced use cases.
     */
    public StatefulRedisConnection<String, String> getConnection() {
        return connection;
    }

    /**
     * Close all resources (connection, client).
     */
    @Override
    public void close() {
        log.info("Closing Redis RateLimiter resources");

        if (tokenBucketStore != null) {
            tokenBucketStore.close();
        }

        if (connection != null) {
            connection.close();
        }

        if (redisClient != null) {
            redisClient.shutdown();
        }

        log.info("Redis RateLimiter resources closed");
    }
}
