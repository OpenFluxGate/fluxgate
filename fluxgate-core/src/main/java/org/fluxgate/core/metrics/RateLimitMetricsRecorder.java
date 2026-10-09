package org.fluxgate.core.metrics;

import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.ratelimiter.RateLimitResult;

/** Hook invoked after every rate limit evaluation so metrics backends can record the outcome. */
public interface RateLimitMetricsRecorder {

  /**
   * Called after each rate limit check (success or reject).
   *
   * @param context the request context that was evaluated
   * @param result the rate limit result
   */
  void record(RequestContext context, RateLimitResult result);
}
