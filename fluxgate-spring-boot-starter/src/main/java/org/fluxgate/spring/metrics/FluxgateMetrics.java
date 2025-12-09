package org.fluxgate.spring.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prometheus/Micrometer metrics for FluxGate rate limiting.
 *
 * <p>Provides the following metrics:
 *
 * <ul>
 *   <li>{@code fluxgate.requests.total} - Total requests processed (counter)
 *   <li>{@code fluxgate.requests.allowed} - Allowed requests (counter)
 *   <li>{@code fluxgate.requests.rejected} - Rejected/rate-limited requests (counter)
 *   <li>{@code fluxgate.requests.duration} - Request processing duration (timer)
 * </ul>
 *
 * <p>All metrics are tagged with:
 *
 * <ul>
 *   <li>{@code rule_set} - The rule set ID applied
 *   <li>{@code endpoint} - The request endpoint (optional)
 * </ul>
 */
public class FluxgateMetrics {

  private static final Logger log = LoggerFactory.getLogger(FluxgateMetrics.class);

  private static final String METRIC_PREFIX = "fluxgate";
  private static final String TAG_RULE_SET = "rule_set";
  private static final String TAG_ENDPOINT = "endpoint";
  private static final String TAG_RESULT = "result";

  private final MeterRegistry registry;
  private final ConcurrentMap<String, Counter> requestCounters = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Timer> requestTimers = new ConcurrentHashMap<>();

  public FluxgateMetrics(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
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

    // Increment total requests counter
    getOrCreateCounter(METRIC_PREFIX + ".requests.total", ruleSetId, endpoint, null).increment();

    // Increment result-specific counter
    getOrCreateCounter(METRIC_PREFIX + ".requests", ruleSetId, endpoint, result).increment();

    // Record duration
    getOrCreateTimer(METRIC_PREFIX + ".requests.duration", ruleSetId, endpoint).record(duration);
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
    registry.gauge(
        METRIC_PREFIX + ".tokens.remaining",
        io.micrometer.core.instrument.Tags.of(TAG_RULE_SET, sanitize(ruleSetId)),
        remainingTokens);
  }

  private Counter getOrCreateCounter(
      String name, String ruleSetId, String endpoint, String result) {
    String key = buildKey(name, ruleSetId, endpoint, result);
    return requestCounters.computeIfAbsent(
        key,
        k -> {
          var builder =
              Counter.builder(name)
                  .description("FluxGate rate limit counter")
                  .tag(TAG_RULE_SET, sanitize(ruleSetId));

          if (endpoint != null && !endpoint.isEmpty()) {
            builder.tag(TAG_ENDPOINT, sanitize(endpoint));
          }
          if (result != null && !result.isEmpty()) {
            builder.tag(TAG_RESULT, result);
          }

          return builder.register(registry);
        });
  }

  private Timer getOrCreateTimer(String name, String ruleSetId, String endpoint) {
    String key = buildKey(name, ruleSetId, endpoint, null);
    return requestTimers.computeIfAbsent(
        key,
        k -> {
          var builder =
              Timer.builder(name)
                  .description("FluxGate rate limit processing time")
                  .tag(TAG_RULE_SET, sanitize(ruleSetId));

          if (endpoint != null && !endpoint.isEmpty()) {
            builder.tag(TAG_ENDPOINT, sanitize(endpoint));
          }

          return builder.register(registry);
        });
  }

  private String buildKey(String name, String ruleSetId, String endpoint, String result) {
    StringBuilder sb = new StringBuilder(name).append(":").append(sanitize(ruleSetId));
    if (endpoint != null) {
      sb.append(":").append(sanitize(endpoint));
    }
    if (result != null) {
      sb.append(":").append(result);
    }
    return sb.toString();
  }

  private String sanitize(String value) {
    if (value == null || value.isEmpty()) {
      return "unknown";
    }
    // Replace characters that might cause issues in metric names/tags
    return value.replaceAll("[^a-zA-Z0-9_/.-]", "_");
  }
}
