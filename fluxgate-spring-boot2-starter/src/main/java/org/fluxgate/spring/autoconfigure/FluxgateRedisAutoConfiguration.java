package org.fluxgate.spring.autoconfigure;

import java.time.Duration;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.redis.RedisRateLimiter;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.connection.RedisUriUtils;
import org.fluxgate.redis.health.RedisHealthCheckerImpl;
import org.fluxgate.redis.store.RedisRuleSetStore;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.fluxgate.spring.actuator.FluxgateHealthIndicator.HealthStatus;
import org.fluxgate.spring.actuator.FluxgateHealthIndicator.RedisHealthChecker;
import org.fluxgate.spring.handler.LazyRedisRateLimiter;
import org.fluxgate.spring.handler.RedisConnectionState;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Lazy;

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
 *   <li>{@link RedisRateLimiter} - Rate limiter implementation, behind {@link LazyRedisRateLimiter}
 * </ul>
 *
 * <p>Supports both Standalone and Cluster Redis deployments:
 *
 * <ul>
 *   <li>Standalone: Single URI (e.g., redis://localhost:6379)
 *   <li>Cluster: Comma-separated URIs or explicit mode setting
 * </ul>
 *
 * <p><b>A Redis outage does not stop the application from starting.</b> The connection and the Lua
 * script load are deferred to first use and retried in the background, because the runtime behavior
 * is already configurable ({@code fluxgate.ratelimit.failure-behavior}) and a crash loop during a
 * rollout is worse than a degraded limiter. The health endpoint reports the limiter as degraded
 * until the connection succeeds. Set {@code fluxgate.redis.fail-fast=true} to fail startup instead.
 *
 * <p>This configuration does NOT require MongoDB. It can run independently for data-plane
 * deployments.
 *
 * @see FluxgateMongoAutoConfiguration
 * @see FluxgateRateLimiterAutoConfiguration
 * @see FluxgateFilterAutoConfiguration
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "fluxgate.redis", name = "enabled", havingValue = "true")
@ConditionalOnClass(name = "io.lettuce.core.RedisClient")
@EnableConfigurationProperties(FluxgateProperties.class)
public class FluxgateRedisAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(FluxgateRedisAutoConfiguration.class);

  /** Cap for the background reconnect delay of {@link LazyRedisRateLimiter}. */
  private static final Duration MAX_RECONNECT_INTERVAL = Duration.ofSeconds(60);

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
   *   <li>Uses the Redis server clock, so instances cannot drift apart
   *   <li>All bands of a rule consume atomically in one Lua call
   *   <li>Read-only on rejection (fair rate limiting)
   *   <li>Bucket TTL of one window plus a safety margin
   * </ul>
   *
   * <p>Cluster support:
   *
   * <ul>
   *   <li>Auto-detection from URI (comma-separated = cluster)
   *   <li>Explicit mode via fluxgate.redis.mode property
   *   <li>Automatic script distribution to all cluster nodes
   * </ul>
   *
   * <p>The bean definition is lazy: it connects when something first asks for it. FluxGate's own
   * consumers all go through an {@code ObjectProvider}, so a Redis outage surfaces as a degraded
   * limiter rather than a failed context. {@code fluxgate.redis.fail-fast=true} adds an eager
   * initializer that restores the boot-time failure.
   */
  @Bean(name = "fluxgateRedisConfig", destroyMethod = "close")
  @ConditionalOnMissingBean(RedisRateLimiterConfig.class)
  @Lazy
  public RedisRateLimiterConfig fluxgateRedisConfig() {
    FluxgateProperties.RedisProperties redisProps = properties.getRedis();
    String uri = redisProps.getUri();
    String effectiveMode = redisProps.getEffectiveMode();
    Duration timeout = Duration.ofMillis(redisProps.getTimeoutMs());

    log.info("Creating FluxGate RedisRateLimiterConfig");
    log.info("  URI: {}", RedisUriUtils.mask(uri));
    log.info("  Mode: {} (configured: {})", effectiveMode, redisProps.getMode());
    log.info("  Timeout: {}ms", redisProps.getTimeoutMs());
    log.info("  Max bucket TTL: {}", redisProps.getMaxBucketTtl());

    RedisRateLimiterConfig config =
        new RedisRateLimiterConfig(uri, timeout, redisProps.getMaxBucketTtl());

    log.info(
        "Redis connection established (effective mode: {}). Multi-band token buckets are consumed "
            + "atomically in Lua against the Redis server clock, rejections leave every bucket "
            + "untouched, and each bucket expires one window after its last use.",
        config.getMode());

    if (config.getMode() == RedisConnectionProvider.RedisMode.CLUSTER) {
      log.info("Cluster mode: automatic routing and script distribution to every node");
    }

    return config;
  }

  /**
   * Forces the Redis connection during startup when {@code fluxgate.redis.fail-fast=true}.
   *
   * <p>Injecting the lazy configuration into an eager bean is what triggers its creation, so a
   * connection or script load failure aborts the boot exactly as it did before.
   *
   * @param fluxgateRedisConfig the Redis configuration, created by this injection
   * @return a marker bean; it carries no behavior
   */
  @Bean
  @ConditionalOnProperty(prefix = "fluxgate.redis", name = "fail-fast", havingValue = "true")
  public RedisFailFastInitializer fluxgateRedisFailFastInitializer(
      RedisRateLimiterConfig fluxgateRedisConfig) {
    log.info(
        "fluxgate.redis.fail-fast=true: Redis connection verified during startup (mode: {})",
        fluxgateRedisConfig.getMode());
    return new RedisFailFastInitializer();
  }

  /** Marker bean forcing eager creation of the lazy Redis configuration. */
  public static final class RedisFailFastInitializer {}

  /**
   * Creates the RedisTokenBucketStore for token bucket operations.
   *
   * <p>This bean is extracted from the config for potential custom injection. Lazy for the same
   * reason as {@link #fluxgateRedisConfig()}.
   */
  @Bean(name = "fluxgateTokenBucketStore")
  @ConditionalOnMissingBean(RedisTokenBucketStore.class)
  @Lazy
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
   *
   * @deprecated together with {@link RedisRuleSetStore} itself; store rules in MongoDB through the
   *     rule repository instead. The bean is still registered so existing applications keep
   *     working.
   */
  @Bean(name = "fluxgateRuleSetStore")
  @ConditionalOnMissingBean(RedisRuleSetStore.class)
  @Lazy
  @Deprecated
  @SuppressWarnings("deprecation")
  public RedisRuleSetStore fluxgateRuleSetStore(RedisRateLimiterConfig fluxgateRedisConfig) {
    log.info("Creating FluxGate RedisRuleSetStore (mode: {})", fluxgateRedisConfig.getMode());
    return fluxgateRedisConfig.getRuleSetStore();
  }

  /**
   * Creates the Redis-backed {@link RateLimiter}.
   *
   * <p>Registered under the shared name {@code fluxgateDelegateRateLimiter} so {@link
   * FluxgateRateLimiterAutoConfiguration} can wrap whichever primary limiter is active, and aliased
   * to the historical {@code redisRateLimiter} name. It also publishes {@link
   * RedisConnectionState}, which the health indicator reads to report the limiter as degraded while
   * it is reconnecting.
   *
   * <p>Skipped when {@code fluxgate.ratelimit.mode=IN_MEMORY} asks for the in-memory limiter even
   * though Redis is enabled (Redis stays available for the rule set store and hot reload).
   */
  @Bean(name = {"fluxgateDelegateRateLimiter", "redisRateLimiter"})
  @ConditionalOnMissingBean(RateLimiter.class)
  @Conditional(FluxgateRateLimiterAutoConfiguration.NotInMemoryModeCondition.class)
  public LazyRedisRateLimiter redisRateLimiter(
      ObjectProvider<RedisTokenBucketStore> tokenBucketStoreProvider) {
    log.info("Creating FluxGate RedisRateLimiter (connects on first use)");
    return new LazyRedisRateLimiter(
        tokenBucketStoreProvider::getObject,
        Duration.ofMillis(properties.getRedis().getTimeoutMs()),
        MAX_RECONNECT_INTERVAL);
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
   * @param configProvider lazy provider for the Redis configuration
   * @return RedisHealthChecker for actuator health endpoint
   */
  @Bean
  @ConditionalOnMissingBean(RedisHealthChecker.class)
  public RedisHealthChecker redisHealthChecker(
      ObjectProvider<RedisRateLimiterConfig> configProvider) {
    log.info("Creating FluxGate RedisHealthChecker");

    // Resolved per check: reporting DOWN while the connection is still being established is the
    // point of the lazy bootstrap.
    return () -> {
      RedisRateLimiterConfig config;
      try {
        config = configProvider.getObject();
      } catch (RuntimeException e) {
        return HealthStatus.down("Redis is not connected: " + e.getMessage());
      }

      RedisHealthCheckerImpl.HealthCheckResult result =
          new RedisHealthCheckerImpl(config.getConnectionProvider()).check();
      if (result.isHealthy()) {
        return HealthStatus.up(result.message(), result.details());
      }
      return HealthStatus.down(result.message(), result.details());
    };
  }
}
