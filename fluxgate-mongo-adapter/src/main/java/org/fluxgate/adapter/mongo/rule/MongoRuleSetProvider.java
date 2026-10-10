package org.fluxgate.adapter.mongo.rule;

import com.mongodb.MongoException;
import com.mongodb.MongoNotPrimaryException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.fluxgate.adapter.mongo.spi.RuleSetAccessControlSource;
import org.fluxgate.core.config.AccessControl;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.exception.FluxgateOperationException;
import org.fluxgate.core.exception.MongoConnectionException;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB-backed RuleSet provider.
 *
 * <p>Uses {@link RateLimitRuleRepository} interface, allowing for different storage implementations
 * (MongoDB, JDBC, etc.).
 *
 * <p><b>A store failure is not an empty rule set.</b> A MongoDB error is wrapped in {@link
 * MongoConnectionException} (connection level) or {@link FluxgateOperationException} (everything
 * else) and propagated, so the configured {@code fluxgate.ratelimit.failure-behavior} decides what
 * happens to the request. Only a successful query that returns no documents yields {@link
 * Optional#empty()}, which {@code missing-rule-behavior} then governs. Letting a driver error look
 * like "no rules" used to lift rate limiting silently.
 *
 * <p>An empty result is logged at WARN once per rule set id: an operator who deletes the rules of a
 * live rule set by accident gets a signal instead of silence.
 *
 * <p>The rule-set-level access control comes from a {@link RuleSetAccessControlSource}: the one
 * passed to the constructor, or else the repository itself when it implements the interface (as
 * {@code MongoRateLimitRuleRepository} does). A stored access control that cannot be parsed - a bad
 * CIDR, say - is reported as a {@link FluxgateOperationException}, like any other store failure.
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

  private static final Logger log = LoggerFactory.getLogger(MongoRuleSetProvider.class);

  private final RateLimitRuleRepository ruleRepository;
  private final KeyResolver keyResolver;

  /** Rule set ids already reported as empty, so the WARN is logged once per id. */
  private final Set<String> warnedEmptyRuleSetIds = ConcurrentHashMap.newKeySet();

  /**
   * Optional metrics recorder for collecting rate limit metrics. If null, no metrics will be
   * recorded.
   */
  private final RateLimitMetricsRecorder metricsRecorder;

  /** Source of the rule-set-level access control, or null when there is none. */
  private final RuleSetAccessControlSource accessControlSource;

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
    this(
        ruleRepository,
        keyResolver,
        metricsRecorder,
        ruleRepository instanceof RuleSetAccessControlSource
            ? (RuleSetAccessControlSource) ruleRepository
            : null);
  }

  /**
   * Creates a MongoRuleSetProvider with an explicit access control source.
   *
   * @param ruleRepository repository for fetching rate limit rules
   * @param keyResolver resolver for generating rate limit keys from request context
   * @param metricsRecorder optional recorder for logging rate limit events (can be null)
   * @param accessControlSource source of the rule-set-level access control, or null for none
   * @since 0.4.0
   */
  public MongoRuleSetProvider(
      RateLimitRuleRepository ruleRepository,
      KeyResolver keyResolver,
      RateLimitMetricsRecorder metricsRecorder,
      RuleSetAccessControlSource accessControlSource) {
    this.ruleRepository = Objects.requireNonNull(ruleRepository, "ruleRepository must not be null");
    this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver must not be null");
    this.metricsRecorder = metricsRecorder; // nullable - metrics are optional
    this.accessControlSource = accessControlSource; // nullable - no access control
  }

  @Override
  public Optional<RateLimitRuleSet> findById(String ruleSetId) {
    List<RateLimitRule> rules = loadRules(ruleSetId);

    if (rules.isEmpty()) {
      if (warnedEmptyRuleSetIds.add(ruleSetId)) {
        log.warn(
            "Rule set '{}' has no rules in MongoDB. Rate limiting for it is governed by "
                + "fluxgate.ratelimit.missing-rule-behavior. If the rules were deleted by mistake, "
                + "restore them.",
            ruleSetId);
      } else {
        log.debug("Rule set '{}' still has no rules in MongoDB", ruleSetId);
      }
      return Optional.empty();
    }
    warnedEmptyRuleSetIds.remove(ruleSetId);

    RateLimitRuleSet.Builder builder =
        RateLimitRuleSet.builder(ruleSetId).keyResolver(keyResolver).rules(rules);

    // Attach metrics recorder if available
    if (metricsRecorder != null) {
      builder.metricsRecorder(metricsRecorder);
    }

    if (accessControlSource != null) {
      builder.accessControl(loadAccessControl(ruleSetId));
    }

    return Optional.of(builder.build());
  }

  /** Loads the access control of a rule set, turning store and parse failures into exceptions. */
  private AccessControl loadAccessControl(String ruleSetId) {
    try {
      AccessControl accessControl = accessControlSource.findAccessControlByRuleSetId(ruleSetId);
      return accessControl != null ? accessControl : AccessControl.EMPTY;
    } catch (MongoSocketException | MongoTimeoutException | MongoNotPrimaryException e) {
      throw new MongoConnectionException(
          "Failed to load the access control of rule set '" + ruleSetId + "'", e);
    } catch (MongoException e) {
      throw new FluxgateOperationException(
          "Failed to load the access control of rule set '" + ruleSetId + "'", e, true);
    } catch (IllegalArgumentException e) {
      // A bad CIDR or key list: retrying cannot fix stored data.
      throw new FluxgateOperationException(
          "Invalid access control stored for rule set '" + ruleSetId + "': " + e.getMessage(),
          e,
          false);
    }
  }

  /** Loads the rules of a rule set, turning store failures into FluxGate exceptions. */
  private List<RateLimitRule> loadRules(String ruleSetId) {
    try {
      return ruleRepository.findByRuleSetId(ruleSetId);
    } catch (MongoSocketException | MongoTimeoutException | MongoNotPrimaryException e) {
      throw new MongoConnectionException(
          "Failed to load rules for rule set '" + ruleSetId + "'", e);
    } catch (MongoException e) {
      throw new FluxgateOperationException(
          "Failed to load rules for rule set '" + ruleSetId + "'", e, true);
    }
  }
}
