package org.fluxgate.spring.filter;

import java.time.Duration;

/**
 * Records how long a rate limited request took.
 *
 * <p>Only the filter and the aspect know the wall clock duration of a request, so the timer has to
 * be fed from there. This interface keeps the servlet layer free of a compile time dependency on
 * Micrometer, which is an optional dependency of the starter.
 *
 * @see org.fluxgate.spring.metrics.MicrometerMetricsRecorder
 */
@FunctionalInterface
public interface RateLimitDurationRecorder {

  /**
   * Records the processing duration of one request.
   *
   * @param ruleSetId the rule set that was applied
   * @param endpoint the normalized endpoint path
   * @param method the HTTP method, or an invocation marker for non-web calls
   * @param duration the measured duration
   */
  void recordDuration(String ruleSetId, String endpoint, String method, Duration duration);
}
