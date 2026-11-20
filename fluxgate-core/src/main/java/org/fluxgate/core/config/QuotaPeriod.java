package org.fluxgate.core.config;

/**
 * Calendar-aligned quota period used with {@link RateLimitAlgorithm#FIXED_WINDOW}.
 *
 * <p>When a band specifies a quota period the counter resets at the start of each calendar period
 * (midnight for {@link #DAILY}, Monday midnight for {@link #WEEKLY}, first-of-month midnight for
 * {@link #MONTHLY}) in the {@link java.time.ZoneId} configured on the band.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * RateLimitBand band = RateLimitBand.builder(Duration.ofDays(30), 1000)
 *     .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
 *     .quotaPeriod(QuotaPeriod.MONTHLY)
 *     .build();
 * // produces key label: "1000-per-30d-monthly"
 * }</pre>
 *
 * @since 0.4.0
 */
public enum QuotaPeriod {

  /** Counter resets at midnight each day in the configured zone. */
  DAILY,

  /** Counter resets at the start of each ISO week (Monday midnight) in the configured zone. */
  WEEKLY,

  /** Counter resets on the first day of each calendar month at midnight in the configured zone. */
  MONTHLY
}
