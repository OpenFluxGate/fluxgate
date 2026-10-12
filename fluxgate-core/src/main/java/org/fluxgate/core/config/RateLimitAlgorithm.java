package org.fluxgate.core.config;

/**
 * The algorithm used to enforce a {@link RateLimitBand}.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100)
 *     .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
 *     .slidingWindowBuckets(12)
 *     .build();
 * }</pre>
 *
 * @since 0.4.0
 */
public enum RateLimitAlgorithm {

  /**
   * Classic token bucket: tokens refill continuously at a constant rate. This is the default and
   * the only algorithm supported before 0.4.0 — existing bucket keys use the unchanged label
   * format.
   */
  TOKEN_BUCKET,

  /**
   * Sliding window counter: the window is divided into {@code slidingWindowBuckets} sub-buckets and
   * only counts within the most recent full window, giving a smoother rate than a fixed window
   * while using bounded memory.
   */
  SLIDING_WINDOW,

  /**
   * Fixed (calendar-aligned) window counter: requests are counted in a tumbling window. When
   * combined with a {@link QuotaPeriod} the window resets at midnight / start-of-week /
   * start-of-month in the configured {@link java.time.ZoneId}.
   */
  FIXED_WINDOW
}
