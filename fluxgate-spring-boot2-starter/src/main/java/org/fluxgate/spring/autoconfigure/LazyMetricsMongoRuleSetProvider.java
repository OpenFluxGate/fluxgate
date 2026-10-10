package org.fluxgate.spring.autoconfigure;

import java.util.Objects;
import java.util.Optional;
import org.fluxgate.adapter.mongo.rule.MongoRuleSetProvider;
import org.fluxgate.adapter.mongo.spi.RuleSetAccessControlSource;
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
 * <p>The rule-set-level access control comes from a {@link RuleSetAccessControlSource}, resolved
 * together with the metrics recorder: the first source bean (in {@code @Order} order) that is not
 * the repository itself, or else the repository when it implements the SPI (as {@code
 * MongoRateLimitRuleRepository} does). A repository decorator that does not implement the SPI
 * therefore keeps access control as long as a source bean is defined; when there is neither, a
 * single WARN says that access control is not loaded.
 *
 * <p>Composes with {@code RuleCache.getOrLoad}: the caching provider calls {@link
 * #findById(String)} as the loader, so a miss triggers exactly one MongoDB query per rule set id.
 */
class LazyMetricsMongoRuleSetProvider implements RateLimitRuleSetProvider {

  private static final Logger log = LoggerFactory.getLogger(LazyMetricsMongoRuleSetProvider.class);

  private final RateLimitRuleRepository ruleRepository;
  private final KeyResolver keyResolver;
  private final ObjectProvider<RateLimitMetricsRecorder> metricsRecorderProvider;
  private final ObjectProvider<RuleSetAccessControlSource> accessControlSourceProvider;

  private volatile MongoRuleSetProvider delegate;

  LazyMetricsMongoRuleSetProvider(
      RateLimitRuleRepository ruleRepository,
      KeyResolver keyResolver,
      ObjectProvider<RateLimitMetricsRecorder> metricsRecorderProvider) {
    this(ruleRepository, keyResolver, metricsRecorderProvider, null);
  }

  LazyMetricsMongoRuleSetProvider(
      RateLimitRuleRepository ruleRepository,
      KeyResolver keyResolver,
      ObjectProvider<RateLimitMetricsRecorder> metricsRecorderProvider,
      ObjectProvider<RuleSetAccessControlSource> accessControlSourceProvider) {
    this.ruleRepository = Objects.requireNonNull(ruleRepository, "ruleRepository must not be null");
    this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver must not be null");
    this.metricsRecorderProvider = metricsRecorderProvider;
    this.accessControlSourceProvider = accessControlSourceProvider;
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
          resolved =
              new MongoRuleSetProvider(
                  ruleRepository, keyResolver, recorder, resolveAccessControlSource());
          delegate = resolved;
        }
      }
    }
    return resolved;
  }

  /**
   * Picks the access control source: an explicit bean first, then the repository itself. Runs once,
   * while the delegate is built, so the WARN below is logged at most once.
   */
  private RuleSetAccessControlSource resolveAccessControlSource() {
    RuleSetAccessControlSource explicit =
        accessControlSourceProvider == null
            ? null
            : accessControlSourceProvider
                .orderedStream()
                .filter(source -> source != ruleRepository)
                .findFirst()
                .orElse(null);
    if (explicit != null) {
      log.info("Using rule set access control source: {}", explicit.getClass().getName());
      return explicit;
    }
    if (ruleRepository instanceof RuleSetAccessControlSource) {
      return (RuleSetAccessControlSource) ruleRepository;
    }
    log.warn(
        "Rule set access control is not loaded: the RateLimitRuleRepository bean ({}) does not "
            + "implement RuleSetAccessControlSource and no RuleSetAccessControlSource bean is "
            + "defined, so allow/deny lists stored with the rules are ignored. Define a "
            + "RuleSetAccessControlSource bean (for example the wrapped "
            + "MongoRateLimitRuleRepository) to enforce them.",
        ruleRepository.getClass().getName());
    return null;
  }
}
