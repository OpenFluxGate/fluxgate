package org.fluxgate.spring.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prometheus/Micrometer metrics for FluxGate rate limiting.
 *
 * <p>Provides the following metrics:
 *
 * <ul>
 *   <li>{@code fluxgate.requests} - Requests by result (counter, tagged {@code
 *       result=allowed|rejected})
 *   <li>{@code fluxgate.requests.duration} - Request processing duration (timer)
 *   <li>{@code fluxgate.tokens.remaining} - Remaining tokens (gauge)
 *   <li>{@code fluxgate.limiter.failures} - Rate limiter failures (counter)
 * </ul>
 *
 * <p>There is deliberately no separate {@code fluxgate.requests.total} counter: under Prometheus
 * naming it would export as {@code fluxgate_requests_total} with a different label set than {@code
 * fluxgate.requests}, which is a metric name collision. Sum {@code fluxgate.requests} over the
 * {@code result} tag instead.
 *
 * <p>All metrics are tagged with:
 *
 * <ul>
 *   <li>{@code rule_set} - The rule set ID applied
 *   <li>{@code endpoint} - The request endpoint (optional)
 * </ul>
 *
 * <p>N-13: the number of distinct {@code endpoint} tag values is capped. Endpoints past the cap are
 * reported as {@code other}, so a caller inventing paths cannot grow the registry, and the
 * Prometheus scrape, without bound.
 */
public class FluxgateMetrics {

  private static final Logger log = LoggerFactory.getLogger(FluxgateMetrics.class);

  private static final String METRIC_PREFIX = "fluxgate";
  private static final String TAG_RULE_SET = "rule_set";
  private static final String TAG_ENDPOINT = "endpoint";
  private static final String TAG_RESULT = "result";
  private static final String TAG_ACTION = "action";
  private static final String TAG_EXCEPTION = "exception";

  /** Endpoint tag value every endpoint past the cardinality cap collapses into. */
  static final String OTHER_ENDPOINT = "other";

  /**
   * Default cap on distinct endpoint tag values, matching {@code
   * fluxgate.metrics.max-endpoint-tags}.
   */
  static final int DEFAULT_MAX_ENDPOINT_TAGS = 1000;

  private final MeterRegistry registry;
  private final int maxEndpointTags;

  /** Endpoint tag values already handed to the registry, so their number stays bounded. */
  private final Set<String> knownEndpoints = ConcurrentHashMap.newKeySet();

  /**
   * Strong references to the gauge values.
   *
   * <p>The {@code registry.gauge(name, tags, number)} overload keeps only a weak reference to the
   * boxed value, so the gauge reports NaN after the first GC.
   */
  private final ConcurrentMap<Tags, AtomicLong> remainingTokenGauges = new ConcurrentHashMap<>();

  /**
   * Creates the metrics facade with the default endpoint tag cap.
   *
   * @param registry the Micrometer registry
   */
  public FluxgateMetrics(MeterRegistry registry) {
    this(registry, DEFAULT_MAX_ENDPOINT_TAGS);
  }

  /**
   * Creates the metrics facade.
   *
   * @param registry the Micrometer registry
   * @param maxEndpointTags upper bound on the number of distinct endpoint tag values; endpoints
   *     beyond it are reported as {@code other}
   */
  public FluxgateMetrics(MeterRegistry registry, int maxEndpointTags) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.maxEndpointTags = maxEndpointTags > 0 ? maxEndpointTags : Integer.MAX_VALUE;
    log.info("FluxGate metrics initialized with registry: {}", registry.getClass().getSimpleName());
  }

  /**
   * Records a rate limit request.
   *
   * @param ruleSetId the rule set ID
   * @param endpoint the request endpoint
   * @param allowed whether the request was allowed
   * @param duration the processing duration
   */
  public void recordRequest(String ruleSetId, String endpoint, boolean allowed, Duration duration) {
    String result = allowed ? "allowed" : "rejected";

    counter(METRIC_PREFIX + ".requests", ruleSetId, endpoint, result).increment();
    timer(METRIC_PREFIX + ".requests.duration", ruleSetId, endpoint).record(duration);
  }

  /**
   * Records an allowed request.
   *
   * @param ruleSetId the rule set ID
   * @param endpoint the request endpoint
   */
  public void recordAllowed(String ruleSetId, String endpoint) {
    recordRequest(ruleSetId, endpoint, true, Duration.ZERO);
  }

  /**
   * Records a rejected (rate-limited) request.
   *
   * @param ruleSetId the rule set ID
   * @param endpoint the request endpoint
   */
  public void recordRejected(String ruleSetId, String endpoint) {
    recordRequest(ruleSetId, endpoint, false, Duration.ZERO);
  }

  /**
   * Records remaining tokens for a bucket.
   *
   * @param ruleSetId the rule set ID
   * @param remainingTokens the number of remaining tokens
   */
  public void recordRemainingTokens(String ruleSetId, long remainingTokens) {
    Tags tags = Tags.of(TAG_RULE_SET, sanitize(ruleSetId));
    AtomicLong holder = remainingTokenGauges.get(tags);
    if (holder == null) {
      AtomicLong created = new AtomicLong(remainingTokens);
      holder = remainingTokenGauges.putIfAbsent(tags, created);
      if (holder == null) {
        Gauge.builder(METRIC_PREFIX + ".tokens.remaining", created, AtomicLong::get)
            .description("Remaining tokens in the most restrictive FluxGate bucket")
            .tags(tags)
            .strongReference(true)
            .register(registry);
        return;
      }
    }
    holder.set(remainingTokens);
  }

  /**
   * Records a rate limiter failure and the action taken by the caller.
   *
   * @param ruleSetId the rule set ID
   * @param endpoint the request endpoint
   * @param action the action taken, such as fail_closed or fail_open
   * @param cause the limiter failure cause
   */
  public void recordLimiterFailure(
      String ruleSetId, String endpoint, String action, Throwable cause) {
    Counter.builder(METRIC_PREFIX + ".limiter.failures")
        .description("FluxGate rate limiter failures")
        .tag(TAG_RULE_SET, sanitize(ruleSetId))
        .tag(TAG_ENDPOINT, boundedEndpoint(endpoint))
        .tag(TAG_ACTION, sanitize(action))
        .tag(TAG_EXCEPTION, cause != null ? cause.getClass().getSimpleName() : "unknown")
        .register(registry)
        .increment();
  }

  private Counter counter(String name, String ruleSetId, String endpoint, String result) {
    // The registry already caches meters by name plus tags, so no local map is needed.
    Counter.Builder builder =
        Counter.builder(name)
            .description("FluxGate rate limit counter")
            .tag(TAG_RULE_SET, sanitize(ruleSetId));

    if (endpoint != null && !endpoint.isEmpty()) {
      builder.tag(TAG_ENDPOINT, boundedEndpoint(endpoint));
    }
    if (result != null && !result.isEmpty()) {
      builder.tag(TAG_RESULT, result);
    }
    return builder.register(registry);
  }

  private Timer timer(String name, String ruleSetId, String endpoint) {
    Timer.Builder builder =
        Timer.builder(name)
            .description("FluxGate rate limit processing time")
            .tag(TAG_RULE_SET, sanitize(ruleSetId));

    if (endpoint != null && !endpoint.isEmpty()) {
      builder.tag(TAG_ENDPOINT, boundedEndpoint(endpoint));
    }
    return builder.register(registry);
  }

  /**
   * Sanitizes an endpoint and keeps the set of distinct values bounded.
   *
   * <p>The cap is shared by every meter, so one invented path costs one series in total. Everything
   * past the cap becomes {@link #OTHER_ENDPOINT}, which keeps the metric usable instead of dropping
   * the measurement.
   */
  String boundedEndpoint(String endpoint) {
    String sanitized = sanitize(endpoint);
    if (knownEndpoints.contains(sanitized)) {
      return sanitized;
    }
    if (knownEndpoints.size() >= maxEndpointTags) {
      return OTHER_ENDPOINT;
    }
    knownEndpoints.add(sanitized);
    return sanitized;
  }

  private String sanitize(String value) {
    if (value == null || value.isEmpty()) {
      return "unknown";
    }
    // Replace characters that might cause issues in metric names/tags
    return value.replaceAll("[^a-zA-Z0-9_/.-]", "_");
  }
}
