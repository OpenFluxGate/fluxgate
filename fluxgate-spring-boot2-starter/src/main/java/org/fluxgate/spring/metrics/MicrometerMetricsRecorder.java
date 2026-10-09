package org.fluxgate.spring.metrics;

import static org.fluxgate.core.constants.FluxgateConstants.Metrics;

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
import java.util.regex.Pattern;
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
  static final String ID_PLACEHOLDER = "{id}";

  /** Endpoint tag value every endpoint past the cardinality cap collapses into. */
  static final String OTHER_ENDPOINT = "other";

  private static final Pattern NUMERIC_SEGMENT = Pattern.compile("\\d+");
  private static final Pattern UUID_SEGMENT =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final Pattern HEX24_SEGMENT = Pattern.compile("[0-9a-fA-F]{24}");
  private static final Pattern UNSAFE_TAG_CHARS = Pattern.compile("[^a-zA-Z0-9_/.{}-]");

  /** Upper bound on a tag value, so a long URI cannot blow up the registry's memory. */
  private static final int MAX_TAG_LENGTH = 200;

  private final MeterRegistry registry;
  private final boolean includeEndpoint;
  private final boolean normalizeEndpoints;
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
   * Endpoint tag values already handed to the registry.
   *
   * <p>N-13: {@code maxEndpointTags} used to bound the gauges only, so counters and timers grew one
   * series per distinct path. Endpoint normalization collapses ids but not invented names, so
   * {@code /api/aaa}, {@code /api/aab} and so on multiplied the registry and the Prometheus scrape
   * without limit. Every meter now goes through {@link #boundedEndpoint(String)}.
   */
  private final Set<String> knownEndpoints = ConcurrentHashMap.newKeySet();

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
    this.includeEndpoint = includeEndpoint;
    this.normalizeEndpoints = normalizeEndpoints;
    this.maxEndpointTags = maxEndpointTags > 0 ? maxEndpointTags : Integer.MAX_VALUE;
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
    if (includeEndpoint) {
      tags = tags.and(Metrics.TAG_ENDPOINT, boundedEndpoint(endpoint));
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
   * Normalizes and sanitizes an endpoint, then keeps the set of distinct values bounded.
   *
   * <p>The cap is shared by every meter, so one attacker-invented path costs one series in total
   * rather than one per meter name. Everything past the cap becomes {@link #OTHER_ENDPOINT}, which
   * keeps the metric usable instead of silently dropping the measurement.
   */
  String boundedEndpoint(String endpoint) {
    String normalized = sanitize(normalizeEndpoint(endpoint));
    if (knownEndpoints.contains(normalized)) {
      return normalized;
    }
    if (knownEndpoints.size() >= maxEndpointTags) {
      return OTHER_ENDPOINT;
    }
    knownEndpoints.add(normalized);
    return normalized;
  }

  /**
   * Replaces high cardinality path segments with {@code {id}}.
   *
   * <p>C6/H-11: the endpoint arrives as the raw request path, so {@code /api/users/12345/orders}
   * would create one meter per user id. Normalizing collapses those to one series.
   */
  String normalizeEndpoint(String endpoint) {
    if (!normalizeEndpoints || endpoint == null || endpoint.isEmpty()) {
      return endpoint;
    }
    String[] segments = endpoint.split("/", -1);
    boolean changed = false;
    for (int i = 0; i < segments.length; i++) {
      if (isHighCardinality(segments[i])) {
        segments[i] = ID_PLACEHOLDER;
        changed = true;
      }
    }
    return changed ? String.join("/", segments) : endpoint;
  }

  private static boolean isHighCardinality(String segment) {
    if (segment.isEmpty()) {
      return false;
    }
    return NUMERIC_SEGMENT.matcher(segment).matches()
        || UUID_SEGMENT.matcher(segment).matches()
        || HEX24_SEGMENT.matcher(segment).matches();
  }

  private static String sanitize(String value) {
    if (value == null || value.isEmpty()) {
      return "unknown";
    }
    String bounded = value.length() > MAX_TAG_LENGTH ? value.substring(0, MAX_TAG_LENGTH) : value;
    return UNSAFE_TAG_CHARS.matcher(bounded).replaceAll("_");
  }

  private static String getRuleSetId(RateLimitResult result) {
    if (result.getMatchedRule() != null && result.getMatchedRule().getRuleSetIdOrNull() != null) {
      return result.getMatchedRule().getRuleSetIdOrNull();
    }
    return "unknown";
  }
}
