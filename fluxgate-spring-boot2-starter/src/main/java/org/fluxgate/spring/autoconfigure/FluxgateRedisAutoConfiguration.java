package org.fluxgate.spring.autoconfigure;

import java.io.IOException;
import java.time.Duration;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.redis.RedisRateLimiter;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.health.RedisHealthCheckerImpl;
import org.fluxgate.redis.store.RedisRuleSetStore;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.fluxgate.spring.actuator.FluxgateHealthIndicator.HealthStatus;
import org.fluxgate.spring.actuator.FluxgateHealthIndicator.RedisHealthChecker;
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
 * <p>Supports both Standalone and Cluster Redis deployments:
 *
 * <ul>
 *   <li>Standalone: Single URI (e.g., redis://localhost:6379)
 *   <li>Cluster: Comma-separated URIs or explicit mode setting
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
   *   <li>Redis client connection (Standalone or Cluster)
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
   *
   * <p>Cluster support:
   *
   * <ul>
   *   <li>Auto-detection from URI (comma-separated = cluster)
   *   <li>Explicit mode via fluxgate.redis.mode property
   *   <li>Automatic script distribution to all cluster nodes
   * </ul>
   */
  @Bean(name = "fluxgateRedisConfig", destroyMethod = "close")
  @ConditionalOnMissingBean(RedisRateLimiterConfig.class)
  public RedisRateLimiterConfig fluxgateRedisConfig() throws IOException {
    FluxgateProperties.RedisProperties redisProps = properties.getRedis();
    String uri = redisProps.getEffectiveUri();
    String effectiveMode = redisProps.getEffectiveMode();
    Duration timeout = Duration.ofMillis(redisProps.getTimeoutMs());

    log.info("Creating FluxGate RedisRateLimiterConfig");
    log.info("  URI: {}", maskUri(uri));
    log.info("  Mode: {} (configured: {})", effectiveMode, redisProps.getMode());
    log.info("  Timeout: {}ms", redisProps.getTimeoutMs());

    RedisRateLimiterConfig config = new RedisRateLimiterConfig(uri, timeout);

    log.info("Redis connection established successfully");
    log.info("  Effective mode: {}", config.getMode());
    log.info("Production features enabled:");
    log.info("  - Uses Redis TIME (no clock drift)");
    log.info("  - Integer arithmetic only (no precision loss)");
    log.info("  - Read-only on rejection (fair rate limiting)");
    log.info("  - TTL safety margin + max cap");

    if (config.getMode() == RedisConnectionProvider.RedisMode.CLUSTER) {
      log.info("  - Cluster mode: Automatic routing and script distribution");
    }

    return config;
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
    log.info("Creating FluxGate RedisTokenBucketStore (mode: {})", fluxgateRedisConfig.getMode());
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
    log.info("Creating FluxGate RedisRuleSetStore (mode: {})", fluxgateRedisConfig.getMode());
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

  /**
   * Creates the RedisHealthChecker for health endpoint integration.
   *
   * <p>Provides detailed health information including:
   *
   * <ul>
   *   <li>Connection status and latency
   *   <li>Redis mode (standalone/cluster)
   *   <li>Cluster state and node count (for cluster mode)
   * </ul>
   *
   * @param fluxgateRedisConfig the Redis configuration
   * @return RedisHealthChecker for actuator health endpoint
   */
  @Bean
  @ConditionalOnMissingBean(RedisHealthChecker.class)
  public RedisHealthChecker redisHealthChecker(RedisRateLimiterConfig fluxgateRedisConfig) {
    log.info("Creating FluxGate RedisHealthChecker");
    RedisHealthCheckerImpl impl =
        new RedisHealthCheckerImpl(fluxgateRedisConfig.getConnectionProvider());

    // Adapt RedisHealthCheckerImpl to RedisHealthChecker interface
    return () -> {
      RedisHealthCheckerImpl.HealthCheckResult result = impl.check();
      if (result.isHealthy()) {
        return HealthStatus.up(result.message(), result.details());
      } else {
        return HealthStatus.down(result.message(), result.details());
      }
    };
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
