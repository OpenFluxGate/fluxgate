package org.openfluxgate.core.metrics;

import org.openfluxgate.core.ratelimiter.RateLimitResult;
import org.openfluxgate.core.context.RequestContext;

public interface RateLimitMetricsRecorder {

    /**
     * Called after each rate limit check (success or reject).
     */
    void record(RequestContext context, RateLimitResult result);
}