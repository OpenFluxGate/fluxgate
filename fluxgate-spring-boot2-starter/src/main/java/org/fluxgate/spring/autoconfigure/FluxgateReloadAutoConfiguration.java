package org.fluxgate.spring.autoconfigure;

import java.time.Duration;
import org.fluxgate.core.reload.BucketResetHandler;
import org.fluxgate.core.reload.CachingRuleSetProvider;
import org.fluxgate.core.reload.RuleCache;
import org.fluxgate.core.reload.RuleReloadStrategy;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.fluxgate.spring.properties.FluxgateProperties.ReloadProperties;
import org.fluxgate.spring.properties.FluxgateProperties.ReloadStrategy;
import org.fluxgate.spring.reload.cache.CaffeineRuleCache;
import org.fluxgate.spring.reload.handler.RedisBucketResetHandler;
import org.fluxgate.spring.reload.strategy.NoOpReloadStrategy;
import org.fluxgate.spring.reload.strategy.PollingReloadStrategy;
import org.fluxgate.spring.reload.strategy.RedisPubSubReloadStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Auto-configuration for FluxGate rule hot reload support.
 *
 * <p>This configuration provides:
 *
 * <ul>
 *   <li>{@link RuleCache} - Local cache for rule sets (Caffeine-based)
 *   <li>{@link RuleReloadStrategy} - Strategy for detecting and propagating rule changes
 *   <li>{@link CachingRuleSetProvider} - Caching decorator for the rule set provider
 * </ul>
 *
 * <p>Note: For publishing rule changes from Admin/Control Plane applications, use the {@code
 * fluxgate-control-support} module instead.
 *
 * <p>Strategy selection:
 *
 * <ul>
 *   <li>AUTO - Uses Pub/Sub if Redis is available, otherwise falls back to Polling
 *   <li>PUBSUB - Uses Redis Pub/Sub only (requires Redis)
 *   <li>POLLING - Uses periodic polling only
 *   <li>NONE - Disables caching and hot reload
 * </ul>
 *
 * <p>Configuration example:
 *
 * <pre>
 * fluxgate:
 *   reload:
 *     enabled: true
 *     strategy: AUTO
 *     cache:
 *       ttl: 5m
 *       max-size: 1000
 *     polling:
 *       interval: 30s
 *     pubsub:
 *       channel: fluxgate:rule-reload
 * </pre>
 */
@AutoConfiguration(after = {FluxgateMongoAutoConfiguration.class})
@ConditionalOnProperty(
    prefix = "fluxgate.reload",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(FluxgateProperties.class)
public class FluxgateReloadAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(FluxgateReloadAutoConfiguration.class);

  private final FluxgateProperties properties;

  public FluxgateReloadAutoConfiguration(FluxgateProperties properties) {
    this.properties = properties;
  }

  /**
   * Creates the Caffeine-based rule cache.
   *
   * <p>Only created when:
   *
   * <ul>
   *   <li>Cache is enabled ({@code fluxgate.reload.cache.enabled=true})
   *   <li>Strategy is not NONE
   *   <li>Caffeine is on the classpath
   * </ul>
   */
  @Bean
  @ConditionalOnMissingBean(RuleCache.class)
  @ConditionalOnClass(name = "com.github.benmanes.caffeine.cache.Caffeine")
  @ConditionalOnProperty(
      prefix = "fluxgate.reload.cache",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public RuleCache ruleCache() {
    ReloadProperties reloadProps = properties.getReload();

    if (reloadProps.getStrategy() == ReloadStrategy.NONE) {
      log.info("Rule cache disabled (strategy=NONE)");
      return null;
    }

    ReloadProperties.CacheProperties cacheProps = reloadProps.getCache();
    log.info(
        "Creating CaffeineRuleCache with ttl={}, maxSize={}",
        cacheProps.getTtl(),
        cacheProps.getMaxSize());

    return new CaffeineRuleCache(cacheProps.getTtl(), cacheProps.getMaxSize());
  }

  /**
   * Creates the rule reload strategy based on configuration.
   *
   * <p>Strategy selection:
   *
   * <ul>
   *   <li>NONE - Returns NoOpReloadStrategy
   *   <li>POLLING - Returns PollingReloadStrategy
   *   <li>PUBSUB - Returns RedisPubSubReloadStrategy (requires Redis URI)
   *   <li>AUTO - Uses Pub/Sub if Redis enabled, otherwise Polling
   * </ul>
   */
  @Bean
  @ConditionalOnMissingBean(RuleReloadStrategy.class)
  public RuleReloadStrategy ruleReloadStrategy(
      @Qualifier("delegateRuleSetProvider") RateLimitRuleSetProvider ruleSetProvider,
      ObjectProvider<RuleCache> ruleCacheProvider) {

    ReloadProperties reloadProps = properties.getReload();
    ReloadStrategy strategy = reloadProps.getStrategy();
    RuleCache ruleCache = ruleCacheProvider.getIfAvailable();
    boolean redisEnabled = properties.getRedis().isEnabled();

    log.info("Configuring rule reload strategy: {}", strategy);

    switch (strategy) {
      case NONE:
        log.info("Hot reload disabled (strategy=NONE)");
        return new NoOpReloadStrategy();
      case POLLING:
        return createPollingStrategy(ruleSetProvider, ruleCache);
      case PUBSUB:
        if (!redisEnabled) {
          log.warn("PUBSUB strategy requested but Redis is not enabled. Falling back to POLLING.");
          return createPollingStrategy(ruleSetProvider, ruleCache);
        }
        return createPubSubStrategy();
      case AUTO:
        if (redisEnabled) {
          log.info("AUTO strategy: Redis enabled, using Pub/Sub");
          return createPubSubStrategy();
        } else {
          log.info("AUTO strategy: Redis not enabled, using Polling");
          return createPollingStrategy(ruleSetProvider, ruleCache);
        }
      default:
        log.warn("Unknown reload strategy: {}, falling back to NONE", strategy);
        return new NoOpReloadStrategy();
    }
  }

  private RuleReloadStrategy createPollingStrategy(
      RateLimitRuleSetProvider provider, RuleCache cache) {
    if (cache == null) {
      log.warn(
          "RuleCache is not available. "
              + "Falling back to NoOpReloadStrategy. "
              + "Check if Caffeine is on the classpath or cache is enabled.");
      return new NoOpReloadStrategy();
    }

    ReloadProperties.PollingProperties pollingProps = properties.getReload().getPolling();
    log.info(
        "Creating PollingReloadStrategy with interval={}, initialDelay={}",
        pollingProps.getInterval(),
        pollingProps.getInitialDelay());
    return new PollingReloadStrategy(
        provider, cache, pollingProps.getInterval(), pollingProps.getInitialDelay());
  }

  private RedisPubSubReloadStrategy createPubSubStrategy() {
    ReloadProperties.PubSubProperties pubsubProps = properties.getReload().getPubsub();
    String redisUri = properties.getRedis().getEffectiveUri();
    Duration timeout = Duration.ofMillis(properties.getRedis().getTimeoutMs());

    log.info("Creating RedisPubSubReloadStrategy on channel={}", pubsubProps.getChannel());

    return new RedisPubSubReloadStrategy(
        redisUri,
        pubsubProps.getChannel(),
        pubsubProps.isRetryOnFailure(),
        pubsubProps.getRetryInterval(),
        timeout);
  }

  /**
   * Creates the Redis bucket reset handler.
   *
   * <p>This handler automatically resets token buckets when rules change, ensuring that the new
   * rate limits take effect immediately.
   *
   * <p>Only created when Redis token bucket store is available.
   */
  @Bean
  @ConditionalOnMissingBean(BucketResetHandler.class)
  @ConditionalOnBean(RedisTokenBucketStore.class)
  public BucketResetHandler bucketResetHandler(RedisTokenBucketStore tokenBucketStore) {
    log.info("Creating RedisBucketResetHandler for automatic bucket reset on rule changes");
    return new RedisBucketResetHandler(tokenBucketStore);
  }

  /**
   * Creates the caching rule set provider that wraps the delegate provider.
   *
   * <p>This is marked as @Primary so it takes precedence over the delegate provider when autowiring
   * RateLimitRuleSetProvider.
   */
  @Bean(name = "cachingRuleSetProvider")
  @Primary
  @ConditionalOnBean({RuleCache.class, RuleReloadStrategy.class})
  public CachingRuleSetProvider cachingRuleSetProvider(
      @Qualifier("delegateRuleSetProvider") RateLimitRuleSetProvider ruleSetProvider,
      RuleCache ruleCache,
      RuleReloadStrategy reloadStrategy,
      ObjectProvider<BucketResetHandler> bucketResetHandlerProvider) {

    // Avoid wrapping if already a caching provider
    if (ruleSetProvider instanceof CachingRuleSetProvider) {
      log.warn("RuleSetProvider is already a CachingRuleSetProvider, skipping wrap");
      return (CachingRuleSetProvider) ruleSetProvider;
    }

    log.info(
        "Creating CachingRuleSetProvider wrapping {}", ruleSetProvider.getClass().getSimpleName());

    CachingRuleSetProvider cachingProvider = new CachingRuleSetProvider(ruleSetProvider, ruleCache);

    // Register the caching provider as a reload listener
    reloadStrategy.addListener(cachingProvider);

    // Register bucket reset handler as a reload listener if available
    BucketResetHandler bucketResetHandler = bucketResetHandlerProvider.getIfAvailable();
    if (bucketResetHandler instanceof RedisBucketResetHandler) {
      RedisBucketResetHandler redisBucketResetHandler =
          (RedisBucketResetHandler) bucketResetHandler;
      log.info("Registering RedisBucketResetHandler as reload listener");
      reloadStrategy.addListener(redisBucketResetHandler);
    }

    return cachingProvider;
  }

  /** Lifecycle bean to start and stop the reload strategy. */
  @Bean
  @ConditionalOnBean(RuleReloadStrategy.class)
  public SmartLifecycle reloadStrategyLifecycle(RuleReloadStrategy reloadStrategy) {
    return new SmartLifecycle() {
      private volatile boolean running = false;

      @Override
      public void start() {
        log.info("Starting rule reload strategy: {}", reloadStrategy.getClass().getSimpleName());
        reloadStrategy.start();
        running = true;
      }

      @Override
      public void stop() {
        log.info("Stopping rule reload strategy: {}", reloadStrategy.getClass().getSimpleName());
        reloadStrategy.stop();
        running = false;
      }

      @Override
      public boolean isRunning() {
        return running;
      }

      @Override
      public int getPhase() {
        // Start after other FluxGate components
        return Integer.MAX_VALUE - 100;
      }

      @Override
      public boolean isAutoStartup() {
        return true;
      }
    };
  }
}
