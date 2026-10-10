package org.fluxgate.spring.metrics;

import static org.fluxgate.core.constants.FluxgateConstants.Metrics;

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
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.spring.filter.RateLimitDurationRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Micrometer-based implementation of {@link RateLimitMetricsRecorder}.
 *
 * <p>Exposes rate limiting metrics to Prometheus/Grafana via Micrometer.
 *
 * <p>Metrics provided:
 *
 * <ul>
 *   <li>{@code fluxgate.requests} - Requests by result (counter, tagged with
 *       result=allowed/rejected)
 *   <li>{@code fluxgate.requests.duration} - Request processing duration (timer), fed by the filter
 *       and the aspect through {@link RateLimitDurationRecorder}
 *   <li>{@code fluxgate.tokens.remaining} - Remaining tokens (gauge)
 * </ul>
 *
 * <p>All metrics are tagged with:
 *
 * <ul>
 *   <li>{@code rule_set} - The rule set ID applied
 *   <li>{@code endpoint} - The request endpoint, normalized and omitted when {@code
 *       fluxgate.metrics.include-endpoint=false}
 *   <li>{@code method} - The HTTP method
 * </ul>
 *
 * <p>There is deliberately no separate {@code fluxgate.requests.total} counter: under Prometheus
 * naming it would export as {@code fluxgate_requests_total} with a different label set than {@code
 * fluxgate.requests}, which is a metric name collision. Sum {@code fluxgate.requests} over the
 * {@code result} tag instead.
 */
public class MicrometerMetricsRecorder
    implements RateLimitMetricsRecorder, RateLimitDurationRecorder {

  private static final Logger log = LoggerFactory.getLogger(MicrometerMetricsRecorder.class);

  /** Placeholder substituted for high cardinality path segments. */
  static final String ID_PLACEHOLDER = EndpointTags.ID_PLACEHOLDER;

  /** Endpoint tag value every endpoint past the cardinality cap collapses into. */
  static final String OTHER_ENDPOINT = EndpointTags.OTHER_ENDPOINT;

  private final MeterRegistry registry;
  private final int maxEndpointTags;

  /**
   * Strong references to the gauge values.
   *
   * <p>M23: {@code registry.gauge(name, tags, number)} keeps only a weak reference to the boxed
   * {@code Long}, so the gauge reported NaN after the first GC. One {@link AtomicLong} per tag set,
   * registered once and held here, is what actually keeps a gauge alive.
   */
  private final ConcurrentMap<Tags, AtomicLong> remainingTokenGauges = new ConcurrentHashMap<>();

  /**
   * The endpoint tag policy and its budget of distinct values.
   *
   * <p>N-13: {@code maxEndpointTags} used to bound the gauges only, so counters and timers grew one
   * series per distinct path. Endpoint normalization collapses ids but not invented names, so
   * {@code /api/aaa}, {@code /api/aab} and so on multiplied the registry and the Prometheus scrape
   * without limit. Every meter now goes through {@link #boundedEndpoint(String)}, and {@link
   * FluxgateMetrics} shares the budget through {@link #endpointTag(String)}.
   */
  private final EndpointTags endpointTags;

  /**
   * Creates a recorder with default metric settings.
   *
   * @param registry the Micrometer registry
   */
  public MicrometerMetricsRecorder(MeterRegistry registry) {
    this(registry, true, true, 1000);
  }

  /**
   * Creates a recorder.
   *
   * @param registry the Micrometer registry
   * @param includeEndpoint tag metrics with the endpoint
   * @param normalizeEndpoints replace numeric, UUID and 24 char hex path segments with {@code {id}}
   * @param maxEndpointTags upper bound on the number of distinct endpoint tag values used by any
   *     meter; endpoints beyond it are reported as {@code other}
   */
  public MicrometerMetricsRecorder(
      MeterRegistry registry,
      boolean includeEndpoint,
      boolean normalizeEndpoints,
      int maxEndpointTags) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.endpointTags = new EndpointTags(includeEndpoint, normalizeEndpoints, maxEndpointTags);
    this.maxEndpointTags = endpointTags.getMaxEndpointTags();
    log.info(
        "MicrometerMetricsRecorder initialized (includeEndpoint={}, endpointNormalization={},"
            + " maxEndpointTags={})",
        includeEndpoint,
        normalizeEndpoints,
        this.maxEndpointTags);
  }

  @Override
  public void record(RequestContext context, RateLimitResult result) {
    String ruleSetId = getRuleSetId(result);
    String endpoint = context.getEndpoint();
    String method = context.getMethod();

    String resultTag = result.isAllowed() ? Metrics.RESULT_ALLOWED : Metrics.RESULT_REJECTED;
    counter(Metrics.REQUESTS, ruleSetId, endpoint, method, resultTag).increment();

    recordRemainingTokens(ruleSetId, endpoint, result.getRemainingTokens());
  }

  @Override
  public void recordDuration(String ruleSetId, String endpoint, String method, Duration duration) {
    timer(Metrics.REQUESTS_DURATION, ruleSetId, endpoint, method).record(duration);
  }

  private void recordRemainingTokens(String ruleSetId, String endpoint, long remainingTokens) {
    if (remainingTokens < 0) {
      return; // Unknown: reporting it as a number would be a lie.
    }
    Tags tags = baseTags(ruleSetId, endpoint, null, null);
    AtomicLong holder = remainingTokenGauges.get(tags);
    if (holder == null) {
      if (remainingTokenGauges.size() >= maxEndpointTags) {
        return;
      }
      AtomicLong created = new AtomicLong(remainingTokens);
      holder = remainingTokenGauges.putIfAbsent(tags, created);
      if (holder == null) {
        Gauge.builder(Metrics.TOKENS_REMAINING, created, AtomicLong::get)
            .description("Remaining tokens in the most restrictive FluxGate bucket")
            .tags(tags)
            .strongReference(true)
            .register(registry);
        return;
      }
    }
    holder.set(remainingTokens);
  }

  private Counter counter(
      String name, String ruleSetId, String endpoint, String method, String result) {
    // The registry already caches meters by name plus tags, so no local map is needed.
    return Counter.builder(name)
        .description("FluxGate rate limit counter")
        .tags(baseTags(ruleSetId, endpoint, method, result))
        .register(registry);
  }

  private Timer timer(String name, String ruleSetId, String endpoint, String method) {
    return Timer.builder(name)
        .description("FluxGate rate limit processing time")
        .tags(baseTags(ruleSetId, endpoint, method, null))
        .register(registry);
  }

  private Tags baseTags(String ruleSetId, String endpoint, String method, String result) {
    Tags tags = Tags.of(Metrics.TAG_RULE_SET, sanitize(ruleSetId));
    String endpointTag = endpointTags.tag(endpoint);
    if (endpointTag != null) {
      tags = tags.and(Metrics.TAG_ENDPOINT, endpointTag);
    }
    if (method != null) {
      tags = tags.and(Metrics.TAG_METHOD, sanitize(method));
    }
    if (result != null) {
      tags = tags.and(Metrics.TAG_RESULT, result);
    }
    return tags;
  }

  /**
   * The {@code endpoint} tag value this recorder uses for a request path - normalized, sanitized
   * and counted against the shared tag budget, so {@code other} once the budget is spent - or null
   * when {@code fluxgate.metrics.include-endpoint=false}.
   *
   * <p>{@link FluxgateMetrics} tags {@code fluxgate.limiter.failures} through this method, so the
   * failure counter and the request meters agree on every endpoint value.
   *
   * @param endpoint the request path, may be null
   * @return the tag value, or null when endpoints are not tagged
   * @since 0.4.0
   */
  public String endpointTag(String endpoint) {
    return endpointTags.tag(endpoint);
  }

  /** Normalizes and sanitizes an endpoint, then keeps the set of distinct values bounded. */
  String boundedEndpoint(String endpoint) {
    return endpointTags.bounded(endpoint);
  }

  /** Replaces high cardinality path segments with {@code {id}} when normalization is on. */
  String normalizeEndpoint(String endpoint) {
    return endpointTags.normalize(endpoint);
  }

  private static String sanitize(String value) {
    return EndpointTags.sanitize(value);
  }

  private static String getRuleSetId(RateLimitResult result) {
    if (result.getMatchedRule() != null && result.getMatchedRule().getRuleSetIdOrNull() != null) {
      return result.getMatchedRule().getRuleSetIdOrNull();
    }
    return "unknown";
  }
}
