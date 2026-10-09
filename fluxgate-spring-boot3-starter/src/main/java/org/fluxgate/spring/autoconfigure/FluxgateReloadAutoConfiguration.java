package org.fluxgate.spring.autoconfigure;

import java.time.Duration;
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.fluxgate.core.reload.BucketResetHandler;
import org.fluxgate.core.reload.CachingRuleSetProvider;
import org.fluxgate.core.reload.RuleCache;
import org.fluxgate.core.reload.RuleReloadListener;
import org.fluxgate.core.reload.RuleReloadStrategy;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.fluxgate.spring.properties.FluxgateProperties.ReloadProperties;
import org.fluxgate.spring.properties.FluxgateProperties.ReloadStrategy;
import org.fluxgate.spring.reload.cache.CaffeineRuleCache;
import org.fluxgate.spring.reload.handler.InMemoryBucketResetHandler;
import org.fluxgate.spring.reload.handler.RedisBucketResetHandler;
import org.fluxgate.spring.reload.strategy.AbstractReloadStrategy;
import org.fluxgate.spring.reload.strategy.CompositeReloadStrategy;
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
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.AnnotatedTypeMetadata;

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
 *       secret: ${FLUXGATE_RELOAD_SECRET}
 * </pre>
 */
@AutoConfiguration(
    after = {
      FluxgateMongoAutoConfiguration.class,
      FluxgateRedisAutoConfiguration.class,
      FluxgateRateLimiterAutoConfiguration.class
    })
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
   *
   * <p>Expressed as conditions rather than by returning {@code null}: a {@code @Bean} method that
   * returns null still registers a definition, and injecting that into the caching provider failed
   * the whole context whenever {@code strategy=NONE}.
   */
  @Bean
  @ConditionalOnMissingBean(RuleCache.class)
  @ConditionalOnClass(name = "com.github.benmanes.caffeine.cache.Caffeine")
  @ConditionalOnProperty(
      prefix = "fluxgate.reload.cache",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  @Conditional(NotNoneStrategyCondition.class)
  public RuleCache ruleCache() {
    ReloadProperties reloadProps = properties.getReload();
    ReloadProperties.CacheProperties cacheProps = reloadProps.getCache();
    log.info(
        "Creating CaffeineRuleCache with ttl={}, maxSize={}, negativeTtl={}",
        cacheProps.getTtl(),
        cacheProps.getMaxSize(),
        cacheProps.getNegativeTtl());

    return new CaffeineRuleCache(
        cacheProps.getTtl(), cacheProps.getMaxSize(), cacheProps.getNegativeTtl());
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
   *
   * <p>Provider resolution: looks first for a bean named {@code delegateRuleSetProvider}, then for
   * the unique {@link RateLimitRuleSetProvider} bean; if neither is found, returns {@link
   * NoOpReloadStrategy} with an INFO log so the context starts without requiring MongoDB.
   */
  @Bean
  @ConditionalOnMissingBean(RuleReloadStrategy.class)
  public RuleReloadStrategy ruleReloadStrategy(
      @Qualifier("delegateRuleSetProvider")
          ObjectProvider<RateLimitRuleSetProvider> namedProviderOp,
      ObjectProvider<RateLimitRuleSetProvider> anyProviderOp,
      ObjectProvider<RuleCache> ruleCacheProvider) {

    RateLimitRuleSetProvider ruleSetProvider = resolveProvider(namedProviderOp, anyProviderOp);
    if (ruleSetProvider == null) {
      log.info(
          "No RateLimitRuleSetProvider found (no bean named 'delegateRuleSetProvider' and no "
              + "unique provider); hot reload disabled");
      return new NoOpReloadStrategy();
    }

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
        return createPubSubStrategyWithBackstop(ruleSetProvider, ruleCache);
      case AUTO:
        if (redisEnabled) {
          log.info("AUTO strategy: Redis enabled, using Pub/Sub with a polling backstop");
          return createPubSubStrategyWithBackstop(ruleSetProvider, ruleCache);
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

  /**
   * Creates the Pub/Sub strategy, paired with a low-frequency polling backstop when one is
   * configured.
   *
   * <p>Redis Pub/Sub is at-most-once and the publisher has no way of knowing that a message was
   * lost, so a dropped notification used to leave this instance on the old rules until the cache
   * TTL expired - and under the PUBSUB strategy there was no other path back to consistency at all.
   * The backstop polls at {@code fluxgate.reload.pubsub.backstop-polling-interval} (60s by default,
   * 0 disables) so the node converges regardless.
   */
  private RuleReloadStrategy createPubSubStrategyWithBackstop(
      RateLimitRuleSetProvider provider, RuleCache cache) {
    RedisPubSubReloadStrategy pubsub = createPubSubStrategy();
    ReloadProperties.PubSubProperties pubsubProps = properties.getReload().getPubsub();
    Duration backstopInterval = pubsubProps.getBackstopPollingInterval();

    if (backstopInterval == null || backstopInterval.isZero() || backstopInterval.isNegative()) {
      log.info("Pub/Sub polling backstop is disabled");
      return pubsub;
    }
    if (cache == null) {
      log.warn("Pub/Sub polling backstop needs a RuleCache; running without a backstop");
      return pubsub;
    }

    log.info("Adding a Pub/Sub polling backstop with interval={}", backstopInterval);
    PollingReloadStrategy backstop =
        new PollingReloadStrategy(provider, cache, backstopInterval, backstopInterval);
    return new CompositeReloadStrategy(pubsub, backstop);
  }

  private RedisPubSubReloadStrategy createPubSubStrategy() {
    ReloadProperties.PubSubProperties pubsubProps = properties.getReload().getPubsub();
    String redisUri = properties.getRedis().getUri();
    Duration timeout = Duration.ofMillis(properties.getRedis().getTimeoutMs());

    log.info(
        "Creating RedisPubSubReloadStrategy on channel={} (signed={})",
        pubsubProps.getChannel(),
        pubsubProps.getSecret() != null && !pubsubProps.getSecret().trim().isEmpty());

    return new RedisPubSubReloadStrategy(
        redisUri,
        pubsubProps.getChannel(),
        pubsubProps.isRetryOnFailure(),
        pubsubProps.getRetryInterval(),
        timeout,
        pubsubProps.getSecret(),
        pubsubProps.getMaxMessageAge());
  }

  /**
   * Creates the Redis bucket reset handler for explicit administrative resets.
   *
   * <p>Policy reload does not invoke this handler. Resetting usage is a separate, explicit
   * administrative operation.
   *
   * <p>Only created when Redis token bucket store is available. This handler and {@link
   * #inMemoryBucketResetHandler} coexist when Redis runs alongside an in-memory fallback.
   */
  @Bean
  @ConditionalOnBean(RedisTokenBucketStore.class)
  @Conditional(FluxgateRateLimiterAutoConfiguration.NotInMemoryModeCondition.class)
  public BucketResetHandler bucketResetHandler(
      ObjectProvider<RedisTokenBucketStore> tokenBucketStoreProvider) {
    log.info("Creating RedisBucketResetHandler for explicit administrative quota resets");
    // Resolved per reset so a Redis outage at startup does not prevent the handler from existing.
    return new RedisBucketResetHandler(tokenBucketStoreProvider::getObject);
  }

  /**
   * Creates the in-memory bucket reset handler.
   *
   * <p>Counterpart of {@link #bucketResetHandler(ObjectProvider)} for deployments running the
   * in-memory limiter, for explicit administrative resets. The {@code @ConditionalOnMissingBean}
   * gate was removed so both handlers coexist when a Redis primary runs with {@code
   * fallback.mode=IN_MEMORY}: one handler resets the Redis buckets, the other the fallback's local
   * buckets.
   *
   * @param rateLimiter the in-memory limiter owning the buckets
   * @return the reset handler
   */
  @Bean
  @ConditionalOnBean(Bucket4jRateLimiter.class)
  public BucketResetHandler inMemoryBucketResetHandler(Bucket4jRateLimiter rateLimiter) {
    log.info("Creating InMemoryBucketResetHandler for explicit administrative quota resets");
    return new InMemoryBucketResetHandler(rateLimiter);
  }

  /**
   * Creates the caching rule set provider that wraps the delegate provider.
   *
   * <p>This is marked as @Primary so it takes precedence over the delegate provider when autowiring
   * RateLimitRuleSetProvider.
   *
   * <p>Provider resolution follows the same rules as {@link #ruleReloadStrategy}: named {@code
   * delegateRuleSetProvider} first, then any unique provider. If neither is available this bean is
   * not created and no caching wrapper is installed.
   */
  @Bean(name = "cachingRuleSetProvider")
  @Primary
  @ConditionalOnBean({RuleCache.class, RuleReloadStrategy.class, RateLimitRuleSetProvider.class})
  public CachingRuleSetProvider cachingRuleSetProvider(
      @Qualifier("delegateRuleSetProvider")
          ObjectProvider<RateLimitRuleSetProvider> namedProviderOp,
      ObjectProvider<RateLimitRuleSetProvider> anyProviderOp,
      RuleCache ruleCache,
      // ObjectProvider breaks the potential circular dependency: ruleReloadStrategy injects
      // ObjectProvider<RateLimitRuleSetProvider> which would eagerly force creation of
      // cachingRuleSetProvider (a @Primary RateLimitRuleSetProvider), but cachingRuleSetProvider
      // itself used to require RuleReloadStrategy directly — creating a cycle. The ObjectProvider
      // wrapper defers bean resolution until getIfAvailable() is called at runtime.
      ObjectProvider<RuleReloadStrategy> reloadStrategyProvider) {

    RateLimitRuleSetProvider ruleSetProvider = resolveProvider(namedProviderOp, anyProviderOp);
    if (ruleSetProvider == null) {
      log.info("No RateLimitRuleSetProvider; skipping caching wrapper");
      return null;
    }

    // Avoid wrapping if already a caching provider
    if (ruleSetProvider instanceof CachingRuleSetProvider) {
      log.warn("RuleSetProvider is already a CachingRuleSetProvider, skipping wrap");
      return (CachingRuleSetProvider) ruleSetProvider;
    }

    log.info(
        "Creating CachingRuleSetProvider wrapping {}", ruleSetProvider.getClass().getSimpleName());

    CachingRuleSetProvider cachingProvider = new CachingRuleSetProvider(ruleSetProvider, ruleCache);

    RuleReloadStrategy reloadStrategy = reloadStrategyProvider.getIfAvailable();
    if (reloadStrategy == null) {
      log.warn("No RuleReloadStrategy found; cache invalidation listeners will not be registered");
      return cachingProvider;
    }

    // Reload changes policy freshness only. Deleting shared buckets from every subscribing Pod
    // would repeatedly restore full quota. Reset handlers remain available for explicit admin use.
    addListener(reloadStrategy, cachingProvider, AbstractReloadStrategy.ORDER_CACHE_INVALIDATION);

    return cachingProvider;
  }

  /**
   * Resolves the delegate rule set provider: named {@code delegateRuleSetProvider} first, then the
   * unique non-caching provider of that type, then null.
   *
   * <p>{@link CachingRuleSetProvider} is deliberately excluded: it is a {@code @Primary}
   * implementation of {@link RateLimitRuleSetProvider} that lives in the same context, so a plain
   * {@code getIfUnique()} call would resolve to it (the primary "wins") rather than to the
   * underlying user-supplied delegate. Filtering it out ensures the resolution logic always returns
   * the delegate that the user explicitly registered.
   */
  private static RateLimitRuleSetProvider resolveProvider(
      ObjectProvider<RateLimitRuleSetProvider> namedProvider,
      ObjectProvider<RateLimitRuleSetProvider> anyProvider) {
    RateLimitRuleSetProvider p = namedProvider.getIfAvailable();
    // Exclude the caching wrapper itself if it happened to be the resolved named bean
    if (p instanceof CachingRuleSetProvider) {
      p = null;
    }
    if (p == null) {
      // Filter out the caching wrapper and require exactly one remaining candidate.
      java.util.List<RateLimitRuleSetProvider> delegates =
          anyProvider
              .orderedStream()
              .filter(prov -> !(prov instanceof CachingRuleSetProvider))
              .collect(java.util.stream.Collectors.toList());
      p = delegates.size() == 1 ? delegates.get(0) : null;
    }
    return p;
  }

  /** Registers a listener with an explicit order when the strategy supports ordering. */
  private void addListener(RuleReloadStrategy strategy, RuleReloadListener listener, int order) {
    if (strategy instanceof AbstractReloadStrategy) {
      ((AbstractReloadStrategy) strategy).addListener(listener, order);
    } else {
      // A custom strategy cannot honour the ordering contract; registration order is all we have.
      strategy.addListener(listener);
    }
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

  /** Matches unless {@code fluxgate.reload.strategy} disables hot reload entirely. */
  public static final class NotNoneStrategyCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      ReloadStrategy strategy =
          Binder.get(context.getEnvironment())
              .bind("fluxgate.reload.strategy", ReloadStrategy.class)
              .orElse(ReloadStrategy.AUTO);
      return strategy != ReloadStrategy.NONE;
    }
  }
}
