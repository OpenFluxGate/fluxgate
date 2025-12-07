package org.fluxgate.core.metrics;

import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.ratelimiter.RateLimitResult;

public interface RateLimitMetricsRecorder {

  /** Called after each rate limit check (success or reject). */
  void record(RequestContext context, RateLimitResult result);
}
