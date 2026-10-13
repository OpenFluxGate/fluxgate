package org.fluxgate.core.util;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lets a repeated log line through at most once per interval.
 *
 * <p>Used for failures that can repeat on every request, such as a broken metrics backend: a WARN
 * line per request would flood the log pipeline, while logging only the first one would hide a
 * failure that persists. Callers log the throttled occurrences at DEBUG instead.
 *
 * <p><strong>Internal; not part of the supported API, may change without notice.</strong> It is
 * public only so that Fluxgate's other modules can share it.
 */
public final class LogThrottle {

  /** The interval used by Fluxgate's own throttled warnings. */
  public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(1);

  private final long intervalNanos;
  private final AtomicLong lastNanos;

  /** Creates a throttle with {@link #DEFAULT_INTERVAL}. */
  public LogThrottle() {
    this(DEFAULT_INTERVAL);
  }

  /**
   * Creates a throttle whose first {@link #tryAcquire()} succeeds.
   *
   * @param interval the minimum gap between two acquisitions (must be positive)
   */
  public LogThrottle(Duration interval) {
    Objects.requireNonNull(interval, "interval must not be null");
    if (interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException("interval must be positive: " + interval);
    }
    this.intervalNanos = interval.toNanos();
    this.lastNanos = new AtomicLong(System.nanoTime() - intervalNanos);
  }

  /**
   * Returns true when the interval has elapsed since the last successful call, at most once per
   * interval across all threads.
   *
   * @return whether the caller should log at its normal level
   */
  public boolean tryAcquire() {
    long now = System.nanoTime();
    long last = lastNanos.get();
    return now - last >= intervalNanos && lastNanos.compareAndSet(last, now);
  }
}
