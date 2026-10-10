package org.fluxgate.spring.autoconfigure;

import java.util.ArrayList;
import java.util.List;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.engine.RateLimitEngine.OnMissingRuleSetStrategy;
import org.fluxgate.core.exception.MissingConfigurationException;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.key.MissingKeyBehavior;
import org.fluxgate.core.match.PathPatternMatcher;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.fluxgate.core.resilience.ResilientExecutor;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.filter.FluxgateWaitPermits;
import org.fluxgate.spring.filter.IdentitySource;
import org.fluxgate.spring.filter.RequestContextFactory;
import org.fluxgate.spring.handler.EngineBackedRateLimitHandler;
import org.fluxgate.spring.handler.MissingRuleSetProviderRateLimitHandler;
import org.fluxgate.spring.handler.ResilientRateLimiter;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.fluxgate.spring.properties.FluxgateProperties.FallbackMode;
import org.fluxgate.spring.properties.FluxgateProperties.IdentityProperties;
import org.fluxgate.spring.properties.FluxgateProperties.RateLimitProperties;
import org.fluxgate.spring.properties.FluxgateProperties.RateLimiterMode;
import org.fluxgate.spring.properties.FluxgateProperties.RuleProperties;
import org.fluxgate.spring.properties.FluxgateProperties.RuleSetProperties;
import org.fluxgate.spring.rule.PropertiesRuleSetProvider;
import org.fluxgate.spring.rule.SpringAntPathMatcherAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Auto-configuration for the FluxGate rate limiting runtime.
 *
 * <p>This is the configuration that makes {@code @EnableFluxgateFilter} work without any user code:
 * it selects a {@link RateLimiter}, wraps it for resilience, builds the canonical {@link
 * RateLimitEngine} and exposes it as the default {@link FluxgateRateLimitHandler}. Previously the
 * only adapter between the core SPI and the starter entry point lived commented out in a sample,
 * and a project that forgot to write one got a WARN line and unlimited traffic.
 *
 * <p>Limiter selection follows {@code fluxgate.ratelimit.mode}:
 *
 * <ul>
 *   <li>AUTO (default) - the Redis limiter when {@code fluxgate.redis.enabled=true}, otherwise the
 *       in-memory Bucket4j limiter
 *   <li>REDIS - the Redis limiter (falls back to in-memory with a WARN if Redis is not enabled)
 *   <li>IN_MEMORY - always the in-memory limiter, even when Redis is enabled for rule storage
 * </ul>
 *
 * <p>Whichever is selected is registered under the name {@code fluxgateDelegateRateLimiter} and,
 * when resilience or a fallback limiter is configured, wrapped by a {@link Primary} {@link
 * ResilientRateLimiter}. The in-memory limiter that wrapper degrades to is a bean of its own
 * ({@code fluxgateFallbackRateLimiter}) so a reload bucket reset handler can find it.
 *
 * <p>A deployment that has a limiter but no {@link RateLimitRuleSetProvider} cannot enforce
 * anything. Rather than leaving the filter to guess, {@link MissingRuleSetProviderRateLimitHandler}
 * is registered to report the real cause once and apply {@code failure-behavior} explicitly; set
 * {@code fluxgate.ratelimit.fail-on-missing-handler=true} to fail the boot instead.
 *
 * <p>Configuration example:
 *
 * <pre>
 * fluxgate:
 *   ratelimit:
 *     mode: AUTO
 *     missing-rule-behavior: DENY
 *     missing-key-behavior: FALLBACK_TO_IP
 *     failure-behavior: DENY
 *     fallback:
 *       mode: IN_MEMORY
 *       max-buckets: 100000
 *       expire-after-access: 1h
 * </pre>
 *
 * @see FluxgateRedisAutoConfiguration
 * @see FluxgateResilienceAutoConfiguration
 */
@AutoConfiguration(
    after = {
      FluxgateRedisAutoConfiguration.class,
      FluxgateMongoAutoConfiguration.class,
      FluxgateResilienceAutoConfiguration.class
    })
@EnableConfigurationProperties(FluxgateProperties.class)
public class FluxgateRateLimiterAutoConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(FluxgateRateLimiterAutoConfiguration.class);

  /** Name under which the unwrapped primary limiter is registered, whatever its implementation. */
  public static final String DELEGATE_RATE_LIMITER_BEAN_NAME = "fluxgateDelegateRateLimiter";

  /** Name under which the in-memory fallback limiter is registered. */
  public static final String FALLBACK_RATE_LIMITER_BEAN_NAME = "fluxgateFallbackRateLimiter";

  /** Name of the {@link FluxgateWaitPermits} shared by the filter and the aspect. */
  public static final String WAIT_PERMITS_BEAN_NAME = "fluxgateWaitPermits";

  private final FluxgateProperties properties;

  /**
   * Creates the auto-configuration.
   *
   * @param properties the FluxGate properties
   */
  public FluxgateRateLimiterAutoConfiguration(FluxgateProperties properties) {
    this.properties = properties;
  }

  /**
   * Creates the in-memory Bucket4j limiter, used when Redis is not the selected backend.
   *
   * <p>This is what makes a development or single-instance deployment work with no infrastructure
   * at all. It is explicitly not distributed: each instance enforces the configured limit on its
   * own.
   *
   * <p>Bucket cache sizing reuses {@code fluxgate.ratelimit.fallback.max-buckets} and {@code
   * fluxgate.ratelimit.fallback.expire-after-access}, which describe the same in-memory limiter.
   *
   * @return the in-memory limiter
   */
  @Bean(name = {DELEGATE_RATE_LIMITER_BEAN_NAME, "fluxgateInMemoryRateLimiter"})
  @ConditionalOnMissingBean(RateLimiter.class)
  public Bucket4jRateLimiter fluxgateInMemoryRateLimiter() {
    RateLimitProperties rateLimitProps = properties.getRatelimit();
    if (rateLimitProps.getMode() == RateLimiterMode.REDIS) {
      log.warn(
          "fluxgate.ratelimit.mode=REDIS but no Redis rate limiter is available "
              + "(fluxgate.redis.enabled={}). Falling back to the in-memory limiter.",
          properties.getRedis().isEnabled());
    }

    log.info(
        "Creating in-memory Bucket4jRateLimiter (mode={}). Rate limits are enforced PER INSTANCE "
            + "and are NOT distributed; enable fluxgate.redis for a shared limit.",
        rateLimitProps.getMode());
    return new Bucket4jRateLimiter(
        rateLimitProps.getFallback().getMaxBuckets(),
        rateLimitProps.getFallback().getExpireAfterAccess());
  }

  /**
   * Creates the in-memory limiter that {@link ResilientRateLimiter} degrades to while the primary
   * limiter is unavailable.
   *
   * <p>Exposed as its own bean rather than created inline, so the reload configuration's {@code
   * inMemoryBucketResetHandler} - conditional on a {@link Bucket4jRateLimiter} bean - can see it
   * and clear its buckets when a rule changes. An inline instance was invisible to every reset
   * handler and kept enforcing the superseded bands until its idle eviction.
   *
   * <p>Deliberately not {@link Primary}: {@link #fluxgateResilientRateLimiter} keeps that role, so
   * a {@code RateLimiter} injection still resolves to the wrapper. It is also skipped when a {@link
   * Bucket4jRateLimiter} already exists, which is exactly the case where the primary limiter is
   * itself in-memory and a fallback would be a duplicate of it.
   *
   * @return the in-memory fallback limiter
   */
  @Bean(name = FALLBACK_RATE_LIMITER_BEAN_NAME)
  @ConditionalOnBean(value = ResilientExecutor.class, name = DELEGATE_RATE_LIMITER_BEAN_NAME)
  @ConditionalOnMissingBean(Bucket4jRateLimiter.class)
  @Conditional(InMemoryFallbackCondition.class)
  public Bucket4jRateLimiter fluxgateFallbackRateLimiter() {
    RateLimitProperties rateLimitProps = properties.getRatelimit();
    log.info(
        "Creating in-memory fallback limiter: maxBuckets={}, expireAfterAccess={}",
        rateLimitProps.getFallback().getMaxBuckets(),
        rateLimitProps.getFallback().getExpireAfterAccess());
    return new Bucket4jRateLimiter(
        rateLimitProps.getFallback().getMaxBuckets(),
        rateLimitProps.getFallback().getExpireAfterAccess());
  }

  /**
   * Wraps the primary limiter so retries, the circuit breaker and the configured degradation
   * finally apply to rate limiting.
   *
   * <p>Registered only when resilience is enabled or a fallback limiter is configured, so a
   * deployment that turned all of it off keeps calling the limiter directly.
   *
   * @param delegate the unwrapped primary limiter
   * @param resilientExecutor the retry + circuit breaker executor
   * @param fallbackLimiterProvider provider of {@link #fluxgateFallbackRateLimiter()}, empty unless
   *     {@code fluxgate.ratelimit.fallback.mode=IN_MEMORY}
   * @param failureRecorderProvider optional sink for the failure metric
   * @return the resilient limiter
   */
  @Bean
  @Primary
  @ConditionalOnBean(value = ResilientExecutor.class, name = DELEGATE_RATE_LIMITER_BEAN_NAME)
  @Conditional(ResilienceWiringCondition.class)
  public ResilientRateLimiter fluxgateResilientRateLimiter(
      @Qualifier(DELEGATE_RATE_LIMITER_BEAN_NAME) RateLimiter delegate,
      ResilientExecutor resilientExecutor,
      ObjectProvider<Bucket4jRateLimiter> fallbackLimiterProvider,
      ObjectProvider<ResilientRateLimiter.FailureRecorder> failureRecorderProvider) {

    RateLimitProperties rateLimitProps = properties.getRatelimit();
    RateLimiter fallbackLimiter = null;
    if (rateLimitProps.getFallback().getMode() == FallbackMode.IN_MEMORY) {
      if (delegate instanceof Bucket4jRateLimiter) {
        log.info(
            "fluxgate.ratelimit.fallback.mode=IN_MEMORY is redundant: the primary limiter is "
                + "already in-memory");
      } else {
        fallbackLimiter = fallbackLimiterProvider.getIfAvailable();
      }
    }

    return new ResilientRateLimiter(
        delegate,
        resilientExecutor,
        fallbackLimiter,
        rateLimitProps.isAllowWhenLimiterFails(),
        failureRecorderProvider.getIfAvailable());
  }

  /**
   * Creates the permits that bound how many threads may be parked waiting for a refill.
   *
   * <p>Item 11: the filter and the aspect each used to create their own, so {@code
   * fluxgate.ratelimit.wait-for-refill.max-concurrent-waits} was enforced twice and up to twice as
   * many threads could be parked. Both now inject this one. It is a {@link FluxgateWaitPermits}
   * rather than a {@code Semaphore} bean, so it never takes part in the application's own
   * injections by type.
   *
   * @return the shared wait permits
   */
  @Bean(name = WAIT_PERMITS_BEAN_NAME)
  @ConditionalOnMissingBean(FluxgateWaitPermits.class)
  public FluxgateWaitPermits fluxgateWaitPermits() {
    return new FluxgateWaitPermits(
        properties.getRatelimit().getWaitForRefill().getMaxConcurrentWaits());
  }

  /**
   * Creates the default key resolver for deployments that do not use the MongoDB adapter.
   *
   * @return the key resolver
   */
  @Bean(name = "fluxgateLimitScopeKeyResolver")
  @ConditionalOnMissingBean(KeyResolver.class)
  public KeyResolver fluxgateLimitScopeKeyResolver() {
    MissingKeyBehavior missingKeyBehavior = properties.getRatelimit().getMissingKeyBehavior();
    log.info("Creating default LimitScopeKeyResolver (missingKeyBehavior={})", missingKeyBehavior);
    return new LimitScopeKeyResolver(missingKeyBehavior);
  }

  /**
   * Creates the {@link PathPatternMatcher} bean that is passed into the engine and limiters.
   *
   * <p>Uses Spring's {@link org.springframework.util.AntPathMatcher} as the implementation so users
   * get the same Ant matching semantics as the rest of the Spring ecosystem. Case-sensitivity is
   * controlled by {@code fluxgate.ratelimit.case-sensitive-patterns} (default {@code true}).
   *
   * @return the path pattern matcher
   */
  @Bean
  @ConditionalOnMissingBean(PathPatternMatcher.class)
  public PathPatternMatcher fluxgatePathPatternMatcher() {
    boolean caseSensitive = properties.getRatelimit().isCaseSensitivePatterns();
    log.info("Creating SpringAntPathMatcherAdapter (caseSensitive={})", caseSensitive);
    return new SpringAntPathMatcherAdapter(caseSensitive);
  }

  /**
   * Creates a {@link PropertiesRuleSetProvider} when the user has declared at least one rule set
   * under {@code fluxgate.ratelimit.rule-sets}.
   *
   * <p>All rule sets are built eagerly during startup, so configuration errors surface before the
   * first request arrives.
   *
   * <p>Not registered when a {@code delegateRuleSetProvider} exists (from {@link
   * FluxgateMongoAutoConfiguration} or the application): {@link #fluxgateYamlRuleSetComposer} then
   * composes the YAML rule sets into that delegate, and a second provider bean would make the
   * {@link RateLimitRuleSetProvider} injection ambiguous.
   *
   * @param keyResolver the key resolver to attach to every rule set
   * @param metricsRecorderProvider the metrics recorder, resolved on the first lookup
   * @return the properties-backed rule set provider
   */
  @Bean(name = "propertiesRuleSetProvider")
  @ConditionalOnMissingBean(
      name = {"propertiesRuleSetProvider", YamlRuleSetComposer.DELEGATE_BEAN_NAME})
  @Conditional(RuleSetsConfiguredCondition.class)
  public PropertiesRuleSetProvider propertiesRuleSetProvider(
      KeyResolver keyResolver, ObjectProvider<RateLimitMetricsRecorder> metricsRecorderProvider) {
    List<RuleSetProperties> ruleSets = properties.getRatelimit().getRuleSets();
    log.info("Creating PropertiesRuleSetProvider with {} rule set(s) from YAML", ruleSets.size());
    PropertiesRuleSetProvider provider =
        new PropertiesRuleSetProvider(ruleSets, keyResolver, metricsRecorderProvider::getIfUnique);
    logStartupSummary(provider);
    warnAboutApiKeyRulesWithoutHeaderIdentity(properties);
    return provider;
  }

  /**
   * Warns once at startup when YAML rules limit {@code PER_API_KEY} while the identity source never
   * reads the API key header: such rules then fall back per {@code missing-key-behavior} (by
   * default to the client IP) instead of limiting per key, which is easy to miss.
   *
   * @param properties the FluxGate properties
   */
  static void warnAboutApiKeyRulesWithoutHeaderIdentity(FluxgateProperties properties) {
    IdentityProperties identity = properties.getRatelimit().getIdentity();
    IdentitySource source = RequestContextFactory.resolveIdentitySource(identity.getSource());
    if (RequestContextFactory.readsIdentityHeaders(source)) {
      return;
    }
    List<String> apiKeyRules = new ArrayList<>();
    for (RuleSetProperties ruleSet : properties.getRatelimit().getRuleSets()) {
      for (RuleProperties rule : ruleSet.getRules()) {
        if (rule.isEnabled() && rule.getScope() == LimitScope.PER_API_KEY) {
          apiKeyRules.add(ruleSet.getId() + "/" + rule.getId());
        }
      }
    }
    if (!apiKeyRules.isEmpty()) {
      log.warn(
          "PER_API_KEY rule(s) {} are configured but fluxgate.ratelimit.identity.source={}{} never"
              + " reads the {} header, so these rules cannot see an API key and apply"
              + " missing-key-behavior={} instead. Set identity.source=HEADERS or"
              + " PRINCIPAL_THEN_HEADERS behind an authenticating gateway, or use another scope.",
          apiKeyRules,
          source,
          identity.getSource() == null ? " (default)" : "",
          identity.getApiKeyHeader(),
          properties.getRatelimit().getMissingKeyBehavior());
    }
  }

  /**
   * Composes the YAML rule sets with the {@code delegateRuleSetProvider} bean, whoever defines it
   * (the MongoDB auto-configuration or the application), so YAML rule sets are never dropped when a
   * delegate exists. The composite tries YAML first and keeps the delegate's bean name, so it
   * remains the single provider and hot reload wraps both sources.
   *
   * <p>{@code static} because it is a {@link BeanPostProcessor}; its dependencies are resolved
   * lazily, when the delegate is created.
   *
   * @param properties the FluxGate properties
   * @param keyResolver the key resolver to attach to every YAML rule set
   * @param metricsRecorder the metrics recorder, resolved on the first lookup
   * @return the post-processor
   */
  @Bean
  @Conditional(RuleSetsConfiguredCondition.class)
  public static BeanPostProcessor fluxgateYamlRuleSetComposer(
      ObjectProvider<FluxgateProperties> properties,
      ObjectProvider<KeyResolver> keyResolver,
      ObjectProvider<RateLimitMetricsRecorder> metricsRecorder) {
    return new YamlRuleSetComposer(properties, keyResolver, metricsRecorder);
  }

  /**
   * Creates the canonical {@link RateLimitEngine}.
   *
   * <p>{@code fluxgate.ratelimit.missing-rule-behavior} is wired onto {@link
   * OnMissingRuleSetStrategy} here, which is the only place that decision is now taken. The {@link
   * PathPatternMatcher} bean is passed in so the engine uses Spring's Ant matcher instead of the
   * built-in simple matcher.
   *
   * @param ruleSetProvider the rule set provider (the caching one when hot reload is enabled)
   * @param rateLimiter the primary limiter (the resilient wrapper when present)
   * @param pathMatcherProvider optional path matcher (always present because we register one above)
   * @return the engine
   */
  @Bean
  @ConditionalOnMissingBean(RateLimitEngine.class)
  @ConditionalOnBean({RateLimiter.class, RateLimitRuleSetProvider.class})
  public RateLimitEngine fluxgateRateLimitEngine(
      RateLimitRuleSetProvider ruleSetProvider,
      RateLimiter rateLimiter,
      ObjectProvider<PathPatternMatcher> pathMatcherProvider) {
    OnMissingRuleSetStrategy strategy =
        properties.getRatelimit().isDenyWhenRuleMissing()
            ? OnMissingRuleSetStrategy.DENY
            : OnMissingRuleSetStrategy.ALLOW;

    PathPatternMatcher pathMatcher = pathMatcherProvider.getIfAvailable();

    log.info(
        "Creating RateLimitEngine: limiter={}, ruleSetProvider={}, onMissingRuleSet={},"
            + " pathMatcher={}",
        rateLimiter.getClass().getSimpleName(),
        ruleSetProvider.getClass().getSimpleName(),
        strategy,
        pathMatcher != null ? pathMatcher.getClass().getSimpleName() : "default");

    RateLimitEngine.Builder builder =
        RateLimitEngine.builder()
            .ruleSetProvider(ruleSetProvider)
            .rateLimiter(rateLimiter)
            .onMissingRuleSetStrategy(strategy);

    if (pathMatcher != null) {
      builder.pathMatcher(pathMatcher);
    }

    return builder.build();
  }

  /** Logs a one-block INFO summary of rule sources, counts, algorithms and failure behaviour. */
  private void logStartupSummary(PropertiesRuleSetProvider provider) {
    RateLimitProperties rlp = properties.getRatelimit();
    StringBuilder sb = new StringBuilder();
    sb.append("\n=== FluxGate startup summary ===");
    sb.append("\n  Rule source    : YAML properties (fluxgate.ratelimit.rule-sets)");
    sb.append("\n  Rule sets      : ").append(provider.size());
    sb.append("\n  Rule set ids   : ").append(provider.ruleSetIds());
    sb.append("\n  Failure behav. : ").append(rlp.isAllowWhenLimiterFails() ? "ALLOW" : "DENY");
    sb.append("\n  Missing rule   : ").append(rlp.isDenyWhenRuleMissing() ? "DENY" : "ALLOW");
    sb.append("\n  Case-sensitive : ").append(rlp.isCaseSensitivePatterns());
    sb.append("\n================================");
    log.info(sb.toString());
  }

  /**
   * Creates the library-provided rate limit handler.
   *
   * <p>Define your own {@link FluxgateRateLimitHandler} bean to replace it, for example to call a
   * central rate limiting service over HTTP.
   *
   * @param engine the engine to delegate to
   * @return the default handler
   */
  @Bean
  @ConditionalOnMissingBean(FluxgateRateLimitHandler.class)
  @ConditionalOnBean(RateLimitEngine.class)
  public FluxgateRateLimitHandler fluxgateRateLimitHandler(RateLimitEngine engine) {
    log.info("Creating EngineBackedRateLimitHandler as the default FluxgateRateLimitHandler");
    return new EngineBackedRateLimitHandler(engine);
  }

  /**
   * Registers a clearly named stopgap handler when a limiter exists but no rule source does.
   *
   * <p>{@code fluxgate.redis.enabled=true} without the MongoDB adapter produces a {@link
   * RateLimiter} and no {@link RateLimitRuleSetProvider}, so {@link #fluxgateRateLimitEngine} and
   * the handler above are both skipped and the filter fell through to its own no-handler path. That
   * path blamed the missing {@code RateLimiter} - the one bean that did exist - and then silently
   * allowed or rejected everything. {@link MissingRuleSetProviderRateLimitHandler} logs the real
   * cause once and applies {@code failure-behavior} explicitly.
   *
   * <p>Only registered when the configuration says this deployment expects limiting, so a plain
   * dependency on the starter with no FluxGate properties at all still contributes nothing.
   *
   * @return the stopgap handler
   * @throws org.fluxgate.core.exception.MissingConfigurationException when {@code
   *     fluxgate.ratelimit.fail-on-missing-handler=true}
   */
  @Bean
  @ConditionalOnBean(RateLimiter.class)
  @ConditionalOnMissingBean({FluxgateRateLimitHandler.class, RateLimitRuleSetProvider.class})
  @Conditional(RateLimitingExpectedCondition.class)
  public FluxgateRateLimitHandler fluxgateMissingRuleSetProviderRateLimitHandler() {
    if (properties.getRatelimit().isFailOnMissingHandler()) {
      throw new MissingConfigurationException(
          "fluxgate.ratelimit.fail-on-missing-handler",
          "A RateLimiter bean exists but no RateLimitRuleSetProvider, so no rate limit can be "
              + "enforced. Enable fluxgate.mongo, define a RateLimitRuleSetProvider bean, or "
              + "supply a FluxgateRateLimitHandler.");
    }
    return new MissingRuleSetProviderRateLimitHandler(
        properties.getRatelimit().isAllowWhenLimiterFails());
  }

  /** Matches unless {@code fluxgate.ratelimit.mode} selects the in-memory limiter. */
  public static final class NotInMemoryModeCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      return resolveMode(context.getEnvironment()) != RateLimiterMode.IN_MEMORY;
    }
  }

  /** Matches when retry, the circuit breaker or a fallback limiter is configured. */
  public static final class ResilienceWiringCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      Environment environment = context.getEnvironment();
      boolean retryEnabled =
          Binder.get(environment)
              .bind("fluxgate.resilience.retry.enabled", Boolean.class)
              .orElse(Boolean.TRUE);
      boolean circuitBreakerEnabled =
          Binder.get(environment)
              .bind("fluxgate.resilience.circuit-breaker.enabled", Boolean.class)
              .orElse(Boolean.TRUE);
      return retryEnabled || circuitBreakerEnabled || isInMemoryFallback(environment);
    }
  }

  /** Matches when {@code fluxgate.ratelimit.fallback.mode} selects the in-memory fallback. */
  public static final class InMemoryFallbackCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      return isInMemoryFallback(context.getEnvironment());
    }
  }

  /**
   * Matches when the configuration says this deployment expects rate limiting to be enforced.
   *
   * <p>That is: Redis is enabled, or {@code fluxgate.ratelimit.mode} has been set explicitly. Bound
   * rather than read as a raw property so relaxed spellings such as {@code in-memory} count too.
   */
  public static final class RateLimitingExpectedCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      Environment environment = context.getEnvironment();
      boolean redisEnabled =
          Binder.get(environment)
              .bind("fluxgate.redis.enabled", Boolean.class)
              .orElse(Boolean.FALSE);
      return redisEnabled
          || Binder.get(environment)
              .bind("fluxgate.ratelimit.mode", RateLimiterMode.class)
              .isBound();
    }
  }

  /** Reads {@code fluxgate.ratelimit.fallback.mode} with relaxed binding. */
  private static boolean isInMemoryFallback(Environment environment) {
    return Binder.get(environment)
            .bind("fluxgate.ratelimit.fallback.mode", FallbackMode.class)
            .orElse(FallbackMode.NONE)
        == FallbackMode.IN_MEMORY;
  }

  /** Reads {@code fluxgate.ratelimit.mode} with relaxed binding. */
  private static RateLimiterMode resolveMode(Environment environment) {
    return Binder.get(environment)
        .bind("fluxgate.ratelimit.mode", RateLimiterMode.class)
        .orElse(RateLimiterMode.AUTO);
  }

  /**
   * Matches when at least one rule set is declared under {@code fluxgate.ratelimit.rule-sets}.
   *
   * <p>Uses {@link Binder} so that indexed-list properties ({@code rule-sets[0].id}, etc.) are
   * detected even when an explicit size property is absent.
   */
  public static final class RuleSetsConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      return Binder.get(context.getEnvironment())
          .bind(
              "fluxgate.ratelimit.rule-sets",
              org.springframework.boot.context.properties.bind.Bindable.listOf(
                  FluxgateProperties.RuleSetProperties.class))
          .map(list -> !list.isEmpty())
          .orElse(Boolean.FALSE);
    }
  }
}
