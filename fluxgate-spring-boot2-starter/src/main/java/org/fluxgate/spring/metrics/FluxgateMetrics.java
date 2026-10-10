package org.fluxgate.spring.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;
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
 * Prometheus scrape, without bound. Endpoints are normalized like {@link MicrometerMetricsRecorder}
 * does ({@code /api/users/123} becomes {@code /api/users/{id}}).
 *
 * <p>Created with {@link #FluxgateMetrics(MeterRegistry, MicrometerMetricsRecorder)} - as the
 * auto-configuration does - the endpoint tag comes from the recorder: it honours {@code
 * fluxgate.metrics.include-endpoint} and {@code endpoint-normalization}, and shares the recorder's
 * tag budget, so a limiter failure on an endpoint past the budget is counted under {@code other}
 * rather than dropped.
 */
public class FluxgateMetrics {

  private static final Logger log = LoggerFactory.getLogger(FluxgateMetrics.class);

  private static final String METRIC_PREFIX = "fluxgate";
  private static final String TAG_RULE_SET = "rule_set";
  private static final String TAG_ENDPOINT = "endpoint";
  private static final String TAG_RESULT = "result";
  private static final String TAG_ACTION = "action";
  private static final String TAG_EXCEPTION = "exception";

  /** Name of the limiter failure counter. */
  public static final String LIMITER_FAILURES = METRIC_PREFIX + ".limiter.failures";

  /** Endpoint tag value every endpoint past the cardinality cap collapses into. */
  static final String OTHER_ENDPOINT = EndpointTags.OTHER_ENDPOINT;

  /**
   * Default cap on distinct endpoint tag values, matching {@code
   * fluxgate.metrics.max-endpoint-tags}.
   */
  static final int DEFAULT_MAX_ENDPOINT_TAGS = 1000;

  private final MeterRegistry registry;

  /** Maps a request path to its endpoint tag value, or null to omit the tag. */
  private final UnaryOperator<String> endpointTag;

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
    this(registry, new EndpointTags(true, true, maxEndpointTags)::tag);
  }

  /**
   * Creates the metrics facade tagging endpoints exactly like the given recorder: same {@code
   * include-endpoint} and normalization settings, and the same budget of distinct values.
   *
   * @param registry the Micrometer registry
   * @param endpointSource the recorder whose endpoint tag policy and budget are shared
   * @since 0.4.0
   */
  public FluxgateMetrics(MeterRegistry registry, MicrometerMetricsRecorder endpointSource) {
    this(
        registry,
        Objects.requireNonNull(endpointSource, "endpointSource must not be null")::endpointTag);
  }

  private FluxgateMetrics(MeterRegistry registry, UnaryOperator<String> endpointTag) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.endpointTag = endpointTag;
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
    Counter.Builder builder =
        Counter.builder(LIMITER_FAILURES)
            .description("FluxGate rate limiter failures")
            .tag(TAG_RULE_SET, sanitize(ruleSetId));
    String endpointValue = endpointTag.apply(endpoint);
    if (endpointValue != null) {
      builder.tag(TAG_ENDPOINT, endpointValue);
    }
    builder
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

    tagEndpoint(builder::tag, endpoint);
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

    tagEndpoint(builder::tag, endpoint);
    return builder.register(registry);
  }

  /** Adds the endpoint tag of a request meter when there is an endpoint and it is tagged. */
  private void tagEndpoint(BiConsumer<String, String> tagger, String endpoint) {
    if (endpoint == null || endpoint.isEmpty()) {
      return;
    }
    String value = endpointTag.apply(endpoint);
    if (value != null) {
      tagger.accept(TAG_ENDPOINT, value);
    }
  }

  private String sanitize(String value) {
    if (value == null || value.isEmpty()) {
      return "unknown";
    }
    // Replace characters that might cause issues in metric names/tags
    return value.replaceAll("[^a-zA-Z0-9_/.-]", "_");
  }
}
