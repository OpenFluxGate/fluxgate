package org.fluxgate.core.metrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.util.LogThrottle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A composite implementation of {@link RateLimitMetricsRecorder} that delegates to multiple
 * underlying recorders.
 *
 * <p>This allows using multiple metrics backends simultaneously, for example:
 *
 * <ul>
 *   <li>{@code MicrometerMetricsRecorder} for Prometheus/Grafana real-time metrics
 *   <li>{@code MongoRateLimitMetricsRecorder} for MongoDB audit logging
 * </ul>
 *
 * <p>Each recorder's {@link #record(RequestContext, RateLimitResult)} method is called in order. If
 * one recorder throws an exception (not an {@link Error}), the failure is logged and subsequent
 * recorders are still invoked.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * List<RateLimitMetricsRecorder> recorders = List.of(
 *     new MicrometerMetricsRecorder(meterRegistry),
 *     new MongoRateLimitMetricsRecorder(eventCollection)
 * );
 * RateLimitMetricsRecorder composite = new CompositeMetricsRecorder(recorders);
 * }</pre>
 *
 * @see RateLimitMetricsRecorder
 */
public class CompositeMetricsRecorder implements RateLimitMetricsRecorder {

  private static final Logger log = LoggerFactory.getLogger(CompositeMetricsRecorder.class);

  private final List<RateLimitMetricsRecorder> recorders;

  /** One warning throttle per delegate, so a noisy recorder cannot mute another one's failures. */
  private final List<LogThrottle> failureWarnings;

  /**
   * Creates a CompositeMetricsRecorder with the given recorders.
   *
   * @param recorders the list of recorders to delegate to (must not be null or empty)
   * @throws IllegalArgumentException if recorders is null or empty
   */
  public CompositeMetricsRecorder(Collection<? extends RateLimitMetricsRecorder> recorders) {
    Objects.requireNonNull(recorders, "recorders must not be null");
    if (recorders.isEmpty()) {
      throw new IllegalArgumentException("recorders must not be empty");
    }
    this.recorders = Collections.unmodifiableList(new ArrayList<>(recorders));
    List<LogThrottle> throttles = new ArrayList<>(this.recorders.size());
    for (int i = 0; i < this.recorders.size(); i++) {
      throttles.add(new LogThrottle());
    }
    this.failureWarnings = throttles;
  }

  /**
   * Records a rate limit event to all underlying recorders.
   *
   * <p>Each recorder is invoked in order. If a recorder throws an exception (not an {@link Error}),
   * the failure is logged - at WARN at most once a minute per recorder, at DEBUG otherwise - and
   * the subsequent recorders are still invoked, so every metrics backend receives the event. Such
   * exceptions are not rethrown; an {@code Error} propagates.
   *
   * @param context the request context
   * @param result the rate limit result
   */
  @Override
  public void record(RequestContext context, RateLimitResult result) {
    for (int i = 0; i < recorders.size(); i++) {
      RateLimitMetricsRecorder recorder = recorders.get(i);
      try {
        recorder.record(context, result);
      } catch (Exception e) {
        if (failureWarnings.get(i).tryAcquire()) {
          log.warn(
              "Metrics recorder {} failed; the other recorders still run. Further failures within"
                  + " {} are logged at DEBUG.",
              recorder.getClass().getName(),
              LogThrottle.DEFAULT_INTERVAL,
              e);
        } else {
          log.debug("Metrics recorder {} failed", recorder.getClass().getName(), e);
        }
      }
    }
  }

  /**
   * Returns the number of underlying recorders.
   *
   * @return the recorder count
   */
  public int size() {
    return recorders.size();
  }

  /**
   * Returns an unmodifiable view of the underlying recorders.
   *
   * @return the list of recorders
   */
  public List<RateLimitMetricsRecorder> getRecorders() {
    return recorders;
  }
}
