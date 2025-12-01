package org.fluxgate.spring.autoconfigure;

import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.redis.RedisRateLimiter;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.IOException;

/**
 * Auto-configuration for FluxGate Redis rate limiting.
 * <p>
 * This configuration is enabled only when:
 * <ul>
 *   <li>{@code fluxgate.redis.enabled=true}</li>
 *   <li>Lettuce Redis client classes are on the classpath</li>
 * </ul>
 * <p>
 * Creates beans for:
 * <ul>
 *   <li>{@link RedisRateLimiterConfig} - Redis connection and Lua scripts</li>
 *   <li>{@link RedisTokenBucketStore} - Token bucket storage</li>
 *   <li>{@link RedisRateLimiter} - Rate limiter implementation</li>
 * </ul>
 * <p>
 * This configuration does NOT require MongoDB.
 * It can run independently for data-plane deployments.
 *
 * @see FluxgateMongoAutoConfiguration
 * @see FluxgateFilterAutoConfiguration
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "fluxgate.redis", name = "enabled", havingValue = "true")
@ConditionalOnClass(name = "io.lettuce.core.RedisClient")
@EnableConfigurationProperties(FluxgateProperties.class)
public class FluxgateRedisAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FluxgateRedisAutoConfiguration.class);

    private final FluxgateProperties properties;

    public FluxgateRedisAutoConfiguration(FluxgateProperties properties) {
        this.properties = properties;
    }

    /**
     * Creates the RedisRateLimiterConfig which manages:
     * <ul>
     *   <li>Redis client connection</li>
     *   <li>Lua script loading</li>
     *   <li>Token bucket store initialization</li>
     * </ul>
     * <p>
     * Production features:
     * <ul>
     *   <li>Uses Redis TIME (no clock drift)</li>
     *   <li>Integer arithmetic (no precision loss)</li>
     *   <li>Read-only on rejection (fair rate limiting)</li>
     *   <li>TTL with safety margin</li>
     * </ul>
     */
    @Bean(name = "fluxgateRedisConfig", destroyMethod = "close")
    @ConditionalOnMissingBean(RedisRateLimiterConfig.class)
    public RedisRateLimiterConfig fluxgateRedisConfig() throws IOException {
        String uri = properties.getRedis().getUri();
        log.info("Creating FluxGate RedisRateLimiterConfig with URI: {}", maskUri(uri));
        log.info("Production features enabled:");
        log.info("  - Uses Redis TIME (no clock drift)");
        log.info("  - Integer arithmetic only (no precision loss)");
        log.info("  - Read-only on rejection (fair rate limiting)");
        log.info("  - TTL safety margin + max cap");
        return new RedisRateLimiterConfig(uri);
    }

    /**
     * Creates the RedisTokenBucketStore for token bucket operations.
     * <p>
     * This bean is extracted from the config for potential custom injection.
     */
    @Bean(name = "fluxgateTokenBucketStore")
    @ConditionalOnMissingBean(RedisTokenBucketStore.class)
    public RedisTokenBucketStore fluxgateTokenBucketStore(RedisRateLimiterConfig fluxgateRedisConfig) {
        log.info("Creating FluxGate RedisTokenBucketStore");
        return fluxgateRedisConfig.getTokenBucketStore();
    }

    /**
     * Creates the RedisRateLimiter implementing the {@link RateLimiter} interface.
     * <p>
     * This is the main entry point for rate limiting operations.
     */
    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    public RateLimiter redisRateLimiter(RedisTokenBucketStore fluxgateTokenBucketStore) {
        log.info("Creating FluxGate RedisRateLimiter");
        return new RedisRateLimiter(fluxgateTokenBucketStore);
    }

    /**
     * Masks sensitive parts of the URI for logging.
     */
    private String maskUri(String uri) {
        if (uri == null) {
            return "null";
        }
        // Mask password in redis://:password@host format
        return uri.replaceAll("://(:)?([^@]+)@", "://****@");
    }
}
