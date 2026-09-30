package org.fluxgate.spring.autoconfigure;

import java.util.Objects;
import java.util.Optional;
import org.fluxgate.adapter.mongo.rule.MongoRuleSetProvider;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A {@link RateLimitRuleSetProvider} wrapper that lazily resolves the {@link
 * RateLimitMetricsRecorder} at runtime.
 *
 * <p>This solves the bean initialization order problem where the CompositeMetricsRecorder may not
 * be available when MongoRuleSetProvider is created.
 *
 * <p>The metricsRecorder is resolved on the first call to {@link #findById(String)}, and the
 * delegate {@link MongoRuleSetProvider} is built with it at that point. Delegating rather than
 * reimplementing keeps the store error handling - a MongoDB failure is a FluxGate exception, not an
 * empty rule set - in one place.
 *
 * <p>Composes with {@code RuleCache.getOrLoad}: the caching provider calls {@link
 * #findById(String)} as the loader, so a miss triggers exactly one MongoDB query per rule set id.
 */
class LazyMetricsMongoRuleSetProvider implements RateLimitRuleSetProvider {

  private static final Logger log = LoggerFactory.getLogger(LazyMetricsMongoRuleSetProvider.class);

  private final RateLimitRuleRepository ruleRepository;
  private final KeyResolver keyResolver;
  private final ObjectProvider<RateLimitMetricsRecorder> metricsRecorderProvider;

  private volatile MongoRuleSetProvider delegate;

  LazyMetricsMongoRuleSetProvider(
      RateLimitRuleRepository ruleRepository,
      KeyResolver keyResolver,
      ObjectProvider<RateLimitMetricsRecorder> metricsRecorderProvider) {
    this.ruleRepository = Objects.requireNonNull(ruleRepository, "ruleRepository must not be null");
    this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver must not be null");
    this.metricsRecorderProvider = metricsRecorderProvider;
  }

  @Override
  public Optional<RateLimitRuleSet> findById(String ruleSetId) {
    return getDelegate().findById(ruleSetId);
  }

  /**
   * Lazily builds the delegate once the metrics recorder can be resolved. Uses double-checked
   * locking for thread safety.
   */
  private MongoRuleSetProvider getDelegate() {
    MongoRuleSetProvider resolved = delegate;
    if (resolved == null) {
      synchronized (this) {
        resolved = delegate;
        if (resolved == null) {
          RateLimitMetricsRecorder recorder =
              metricsRecorderProvider != null ? metricsRecorderProvider.getIfAvailable() : null;
          if (recorder != null) {
            log.info("Resolved metrics recorder: {}", recorder.getClass().getSimpleName());
          } else {
            log.info("No metrics recorder available");
          }
          resolved = new MongoRuleSetProvider(ruleRepository, keyResolver, recorder);
          delegate = resolved;
        }
      }
    }
    return resolved;
  }
}
