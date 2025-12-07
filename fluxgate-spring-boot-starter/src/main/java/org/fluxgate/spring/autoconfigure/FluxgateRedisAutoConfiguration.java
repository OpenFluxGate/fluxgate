package org.fluxgate.spring.autoconfigure;

import java.io.IOException;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.redis.RedisRateLimiter;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.store.RedisRuleSetStore;
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

/**
 * Auto-configuration for FluxGate Redis rate limiting.
 *
 * <p>This configuration is enabled only when:
 *
 * <ul>
 *   <li>{@code fluxgate.redis.enabled=true}
 *   <li>Lettuce Redis client classes are on the classpath
 * </ul>
 *
 * <p>Creates beans for:
 *
 * <ul>
 *   <li>{@link RedisRateLimiterConfig} - Redis connection and Lua scripts
 *   <li>{@link RedisTokenBucketStore} - Token bucket storage
 *   <li>{@link RedisRateLimiter} - Rate limiter implementation
 * </ul>
 *
 * <p>This configuration does NOT require MongoDB. It can run independently for data-plane
 * deployments.
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
   *
   * <ul>
   *   <li>Redis client connection
   *   <li>Lua script loading
   *   <li>Token bucket store initialization
   * </ul>
   *
   * <p>Production features:
   *
   * <ul>
   *   <li>Uses Redis TIME (no clock drift)
   *   <li>Integer arithmetic (no precision loss)
   *   <li>Read-only on rejection (fair rate limiting)
   *   <li>TTL with safety margin
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
   *
   * <p>This bean is extracted from the config for potential custom injection.
   */
  @Bean(name = "fluxgateTokenBucketStore")
  @ConditionalOnMissingBean(RedisTokenBucketStore.class)
  public RedisTokenBucketStore fluxgateTokenBucketStore(
      RedisRateLimiterConfig fluxgateRedisConfig) {
    log.info("Creating FluxGate RedisTokenBucketStore");
    return fluxgateRedisConfig.getTokenBucketStore();
  }

  /**
   * Creates the RedisRuleSetStore for storing RuleSet configurations in Redis.
   *
   * <p>This allows applications to store and retrieve rate limiting rules from Redis, enabling
   * dynamic rule management across distributed nodes.
   */
  @Bean(name = "fluxgateRuleSetStore")
  @ConditionalOnMissingBean(RedisRuleSetStore.class)
  public RedisRuleSetStore fluxgateRuleSetStore(RedisRateLimiterConfig fluxgateRedisConfig) {
    log.info("Creating FluxGate RedisRuleSetStore");
    return fluxgateRedisConfig.getRuleSetStore();
  }

  /**
   * Creates the RedisRateLimiter implementing the {@link RateLimiter} interface.
   *
   * <p>This is the main entry point for rate limiting operations.
   */
  @Bean
  @ConditionalOnMissingBean(RateLimiter.class)
  public RateLimiter redisRateLimiter(RedisTokenBucketStore fluxgateTokenBucketStore) {
    log.info("Creating FluxGate RedisRateLimiter");
    return new RedisRateLimiter(fluxgateTokenBucketStore);
  }

  /** Masks sensitive parts of the URI for logging. */
  private String maskUri(String uri) {
    if (uri == null) {
      return "null";
    }
    // Mask password in redis://:password@host format
    return uri.replaceAll("://(:)?([^@]+)@", "://****@");
  }
}
