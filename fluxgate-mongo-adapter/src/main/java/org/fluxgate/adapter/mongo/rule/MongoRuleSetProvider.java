package org.fluxgate.adapter.mongo.rule;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;

/**
 * MongoDB-backed RuleSet provider.
 *
 * <p>Uses {@link RateLimitRuleRepository} interface, allowing for different storage implementations
 * (MongoDB, JDBC, etc.).
 *
 * <p>Optionally supports {@link RateLimitMetricsRecorder} for metrics collection. If a
 * metricsRecorder is provided, it will be attached to each RuleSet and called after every rate
 * limit check. Supported implementations:
 *
 * <ul>
 *   <li>{@code MicrometerMetricsRecorder} - Prometheus/Grafana metrics (recommended)
 *   <li>{@code MongoRateLimitMetricsRecorder} - MongoDB event logging (for audit)
 * </ul>
 */
public class MongoRuleSetProvider implements RateLimitRuleSetProvider {

  private final RateLimitRuleRepository ruleRepository;
  private final KeyResolver keyResolver;

  /**
   * Optional metrics recorder for collecting rate limit metrics. If null, no metrics will be
   * recorded.
   */
  private final RateLimitMetricsRecorder metricsRecorder;

  /**
   * Creates a MongoRuleSetProvider without metrics recording.
   *
   * @param ruleRepository repository for fetching rate limit rules
   * @param keyResolver resolver for generating rate limit keys from request context
   */
  public MongoRuleSetProvider(RateLimitRuleRepository ruleRepository, KeyResolver keyResolver) {
    this(ruleRepository, keyResolver, null);
  }

  /**
   * Creates a MongoRuleSetProvider with optional metrics recording.
   *
   * @param ruleRepository repository for fetching rate limit rules
   * @param keyResolver resolver for generating rate limit keys from request context
   * @param metricsRecorder optional recorder for logging rate limit events (can be null)
   */
  public MongoRuleSetProvider(
      RateLimitRuleRepository ruleRepository,
      KeyResolver keyResolver,
      RateLimitMetricsRecorder metricsRecorder) {
    this.ruleRepository = Objects.requireNonNull(ruleRepository, "ruleRepository must not be null");
    this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver must not be null");
    this.metricsRecorder = metricsRecorder; // nullable - metrics are optional
  }

  @Override
  public Optional<RateLimitRuleSet> findById(String ruleSetId) {
    List<RateLimitRule> rules = ruleRepository.findByRuleSetId(ruleSetId);

    if (rules.isEmpty()) {
      return Optional.empty();
    }

    RateLimitRuleSet.Builder builder =
        RateLimitRuleSet.builder(ruleSetId).keyResolver(keyResolver).rules(rules);

    // Attach metrics recorder if available
    if (metricsRecorder != null) {
      builder.metricsRecorder(metricsRecorder);
    }

    return Optional.of(builder.build());
  }
}
