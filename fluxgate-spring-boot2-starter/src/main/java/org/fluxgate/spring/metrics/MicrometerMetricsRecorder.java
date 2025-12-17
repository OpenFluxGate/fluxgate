package org.fluxgate.spring.metrics;

import static org.fluxgate.core.constants.FluxgateConstants.Metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitResult;
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
 *   <li>{@code fluxgate.requests.total} - Total requests processed (counter)
 *   <li>{@code fluxgate.requests} - Requests by result (counter, tagged with
 *       result=allowed/rejected)
 *   <li>{@code fluxgate.requests.duration} - Request processing duration (timer)
 *   <li>{@code fluxgate.tokens.remaining} - Remaining tokens (gauge)
 * </ul>
 *
 * <p>All metrics are tagged with:
 *
 * <ul>
 *   <li>{@code rule_set} - The rule set ID applied
 *   <li>{@code endpoint} - The request endpoint
 *   <li>{@code method} - The HTTP method
 * </ul>
 */
public class MicrometerMetricsRecorder implements RateLimitMetricsRecorder {

  private static final Logger log = LoggerFactory.getLogger(MicrometerMetricsRecorder.class);

  private final MeterRegistry registry;
  private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Timer> timers = new ConcurrentHashMap<>();

  public MicrometerMetricsRecorder(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    log.info("MicrometerMetricsRecorder initialized");
  }

  @Override
  public void record(RequestContext context, RateLimitResult result) {
    String ruleSetId = getRuleSetId(result);
    String endpoint = context.getEndpoint();
    String method = context.getMethod();
    boolean allowed = result.isAllowed();

    // Increment total requests
    getOrCreateCounter(Metrics.REQUESTS_TOTAL, ruleSetId, endpoint, method, null).increment();

    // Increment result-specific counter
    String resultTag = allowed ? Metrics.RESULT_ALLOWED : Metrics.RESULT_REJECTED;
    getOrCreateCounter(Metrics.REQUESTS, ruleSetId, endpoint, method, resultTag).increment();

    // Record remaining tokens as gauge
    recordRemainingTokens(ruleSetId, endpoint, result.getRemainingTokens());
  }

  /** Records request duration. Call this separately as duration is measured at filter level. */
  public void recordDuration(String ruleSetId, String endpoint, String method, Duration duration) {
    getOrCreateTimer(Metrics.REQUESTS_DURATION, ruleSetId, endpoint, method).record(duration);
  }

  private void recordRemainingTokens(String ruleSetId, String endpoint, long remainingTokens) {
    registry.gauge(
        Metrics.TOKENS_REMAINING,
        Tags.of(
            Metrics.TAG_RULE_SET, sanitize(ruleSetId),
            Metrics.TAG_ENDPOINT, sanitize(endpoint)),
        remainingTokens);
  }

  private Counter getOrCreateCounter(
      String name, String ruleSetId, String endpoint, String method, String result) {
    String key = buildKey(name, ruleSetId, endpoint, method, result);
    return counters.computeIfAbsent(
        key,
        k -> {
          Counter.Builder builder =
              Counter.builder(name)
                  .description("FluxGate rate limit counter")
                  .tag(Metrics.TAG_RULE_SET, sanitize(ruleSetId))
                  .tag(Metrics.TAG_ENDPOINT, sanitize(endpoint))
                  .tag(Metrics.TAG_METHOD, sanitize(method));

          if (result != null) {
            builder.tag(Metrics.TAG_RESULT, result);
          }

          return builder.register(registry);
        });
  }

  private Timer getOrCreateTimer(String name, String ruleSetId, String endpoint, String method) {
    String key = buildKey(name, ruleSetId, endpoint, method, null);
    return timers.computeIfAbsent(
        key,
        k ->
            Timer.builder(name)
                .description("FluxGate rate limit processing time")
                .tag(Metrics.TAG_RULE_SET, sanitize(ruleSetId))
                .tag(Metrics.TAG_ENDPOINT, sanitize(endpoint))
                .tag(Metrics.TAG_METHOD, sanitize(method))
                .register(registry));
  }

  private String buildKey(
      String name, String ruleSetId, String endpoint, String method, String result) {
    StringBuilder sb =
        new StringBuilder(name)
            .append(":")
            .append(sanitize(ruleSetId))
            .append(":")
            .append(sanitize(endpoint))
            .append(":")
            .append(sanitize(method));
    if (result != null) {
      sb.append(":").append(result);
    }
    return sb.toString();
  }

  private String sanitize(String value) {
    if (value == null || value.isEmpty()) {
      return "unknown";
    }
    return value.replaceAll("[^a-zA-Z0-9_/.-]", "_");
  }

  private String getRuleSetId(RateLimitResult result) {
    if (result.getMatchedRule() != null && result.getMatchedRule().getRuleSetIdOrNull() != null) {
      return result.getMatchedRule().getRuleSetIdOrNull();
    }
    return "unknown";
  }
}
