package org.fluxgate.core.metrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.ratelimiter.RateLimitResult;

/**
 * A composite implementation of {@link RateLimitMetricsRecorder} that delegates
 * to multiple underlying recorders.
 *
 * <p>This allows using multiple metrics backends simultaneously, for example:
 * <ul>
 *   <li>{@code MicrometerMetricsRecorder} for Prometheus/Grafana real-time metrics</li>
 *   <li>{@code MongoRateLimitMetricsRecorder} for MongoDB audit logging</li>
 * </ul>
 *
 * <p>Each recorder's {@link #record(RequestContext, RateLimitResult)} method is called
 * in order. If one recorder fails, subsequent recorders are still invoked.
 *
 * <p>Example usage:
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

    private final List<RateLimitMetricsRecorder> recorders;

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
    }

    /**
     * Records a rate limit event to all underlying recorders.
     *
     * <p>Each recorder is invoked in order. If a recorder throws an exception,
     * the error is logged but subsequent recorders are still invoked to ensure
     * all metrics backends receive the event.
     *
     * @param context the request context
     * @param result the rate limit result
     */
    @Override
    public void record(RequestContext context, RateLimitResult result) {
        for (RateLimitMetricsRecorder recorder : recorders) {
            try {
                recorder.record(context, result);
            } catch (Exception e) {
                // Log error but continue to next recorder
                // Using stderr here to avoid circular dependency on logging framework
                System.err.println("[CompositeMetricsRecorder] Error in recorder "
                        + recorder.getClass().getSimpleName() + ": " + e.getMessage());
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
