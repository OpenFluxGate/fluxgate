package org.fluxgate.core.config;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import org.fluxgate.core.exception.InvalidRuleConfigException;

/**
 * Represents a single rate limit band, such as: - 1 minute, 100 requests - 10 minutes, 500 requests
 *
 * <p>A band may specify the rate limiting {@link RateLimitAlgorithm}:
 *
 * <ul>
 *   <li>{@link RateLimitAlgorithm#TOKEN_BUCKET} (default) — continuous refill; key label unchanged
 *       for backwards compatibility
 *   <li>{@link RateLimitAlgorithm#SLIDING_WINDOW} — rolling window counter; requires {@code 2 <=
 *       slidingWindowBuckets <= 60}
 *   <li>{@link RateLimitAlgorithm#FIXED_WINDOW} — tumbling counter; optionally calendar-aligned via
 *       {@link QuotaPeriod}
 * </ul>
 */
public final class RateLimitBand {

  private static final int SLIDING_WINDOW_BUCKETS_MIN = 2;
  private static final int SLIDING_WINDOW_BUCKETS_MAX = 60;
  private static final int SLIDING_WINDOW_BUCKETS_DEFAULT = 10;

  private final Duration window;
  private final long capacity;
  private final String label; // optional, for metrics / admin UI
  private final RateLimitAlgorithm algorithm;
  private final QuotaPeriod quotaPeriod; // nullable; only valid with FIXED_WINDOW
  private final ZoneId zoneId; // only meaningful with quotaPeriod
  private final int slidingWindowBuckets; // only valid with SLIDING_WINDOW; 2..60

  private RateLimitBand(Builder builder) {
    this.window = Objects.requireNonNull(builder.window, "window must not be null");
    if (this.window.isZero() || this.window.isNegative()) {
      throw new InvalidRuleConfigException("window must be positive, but was " + this.window);
    }
    if (builder.capacity <= 0) {
      throw new IllegalArgumentException("capacity must be > 0");
    }
    this.capacity = builder.capacity;
    this.label = builder.label;
    this.algorithm =
        builder.algorithm != null ? builder.algorithm : RateLimitAlgorithm.TOKEN_BUCKET;
    this.quotaPeriod = builder.quotaPeriod;
    this.zoneId = builder.zoneId != null ? builder.zoneId : ZoneOffset.UTC;
    this.slidingWindowBuckets = builder.slidingWindowBuckets;

    // validate combinations
    if (this.quotaPeriod != null && this.algorithm != RateLimitAlgorithm.FIXED_WINDOW) {
      throw new InvalidRuleConfigException(
          "quotaPeriod is only valid with algorithm FIXED_WINDOW, but algorithm is "
              + this.algorithm);
    }
    if (this.algorithm == RateLimitAlgorithm.SLIDING_WINDOW
        && (this.slidingWindowBuckets < SLIDING_WINDOW_BUCKETS_MIN
            || this.slidingWindowBuckets > SLIDING_WINDOW_BUCKETS_MAX)) {
      throw new InvalidRuleConfigException(
          "slidingWindowBuckets must be in range ["
              + SLIDING_WINDOW_BUCKETS_MIN
              + ", "
              + SLIDING_WINDOW_BUCKETS_MAX
              + "], but was "
              + this.slidingWindowBuckets);
    }
  }

  /**
   * Returns the time window this band applies to.
   *
   * @return the window duration (never null, always positive)
   */
  public Duration getWindow() {
    return window;
  }

  /**
   * Returns the number of permits allowed within the window.
   *
   * @return the capacity (always greater than zero)
   */
  public long getCapacity() {
    return capacity;
  }

  /**
   * Returns the optional human-readable label.
   *
   * @return the label, or null if none was set
   */
  public String getLabel() {
    return label;
  }

  /**
   * Returns the rate limiting algorithm for this band.
   *
   * @return the algorithm (never null)
   * @since 0.4.0
   */
  public RateLimitAlgorithm getAlgorithm() {
    return algorithm;
  }

  /**
   * Returns the calendar-aligned quota period, or {@code null} if none was set.
   *
   * <p>Only meaningful when the algorithm is {@link RateLimitAlgorithm#FIXED_WINDOW}.
   *
   * @return the quota period, or null
   * @since 0.4.0
   */
  public QuotaPeriod getQuotaPeriod() {
    return quotaPeriod;
  }

  /**
   * Returns the time zone used for calendar alignment when a {@link QuotaPeriod} is set.
   *
   * @return the zone id (never null; defaults to {@link ZoneOffset#UTC})
   * @since 0.4.0
   */
  public ZoneId getZoneId() {
    return zoneId;
  }

  /**
   * Returns the number of sub-buckets used by the {@link RateLimitAlgorithm#SLIDING_WINDOW}
   * algorithm.
   *
   * @return the sub-bucket count (always in [{@value #SLIDING_WINDOW_BUCKETS_MIN}, {@value
   *     #SLIDING_WINDOW_BUCKETS_MAX}])
   * @since 0.4.0
   */
  public int getSlidingWindowBuckets() {
    return slidingWindowBuckets;
  }

  /**
   * Returns the stable identifier of this band, used as the last segment of the storage bucket key.
   *
   * <p>The display {@link #getLabel() label} is used when it is set, otherwise a value derived from
   * the band configuration (for example {@code 100-per-60s}). Deriving it keeps two unlabelled
   * bands of the same rule apart, which a {@code default} placeholder would not.
   *
   * <p>For {@link RateLimitAlgorithm#TOKEN_BUCKET} bands the format is unchanged from 0.3.x to
   * preserve existing storage keys. For other algorithms a suffix encodes the algorithm:
   *
   * <ul>
   *   <li>{@code SLIDING_WINDOW} — suffix {@code -sw}, e.g. {@code 100-per-60s-sw}
   *   <li>{@code FIXED_WINDOW} with {@link QuotaPeriod#MONTHLY} — suffix {@code -monthly}, e.g.
   *       {@code 1000-per-30d-monthly}
   *   <li>{@code FIXED_WINDOW} with {@link QuotaPeriod#WEEKLY} — suffix {@code -weekly}
   *   <li>{@code FIXED_WINDOW} with {@link QuotaPeriod#DAILY} — suffix {@code -daily}
   *   <li>{@code FIXED_WINDOW} without a quota period — suffix {@code -fw}
   * </ul>
   *
   * @return the band key label (never null, never blank)
   */
  public String getKeyLabel() {
    if (label != null && !label.trim().isEmpty()) {
      return label;
    }

    // TOKEN_BUCKET: keep existing stable format unchanged
    if (algorithm == RateLimitAlgorithm.TOKEN_BUCKET) {
      return derivedLabelTokenBucket();
    }

    // other algorithms: use extended window format with algorithm suffix
    String windowStr = formatWindowForNewAlgorithm();
    switch (algorithm) {
      case SLIDING_WINDOW:
        return capacity + "-per-" + windowStr + "-sw";
      case FIXED_WINDOW:
        if (quotaPeriod == null) {
          return capacity + "-per-" + windowStr + "-fw";
        }
        switch (quotaPeriod) {
          case DAILY:
            return capacity + "-per-" + windowStr + "-daily";
          case WEEKLY:
            return capacity + "-per-" + windowStr + "-weekly";
          case MONTHLY:
            return capacity + "-per-" + windowStr + "-monthly";
          default:
            return capacity + "-per-" + windowStr + "-fw";
        }
      default:
        return derivedLabelTokenBucket();
    }
  }

  /** Derives the key label using the original (0.3.x) format for TOKEN_BUCKET bands. */
  private String derivedLabelTokenBucket() {
    int nanoOfSecond = window.getNano();
    if (nanoOfSecond == 0) {
      return capacity + "-per-" + window.getSeconds() + "s";
    }
    if (nanoOfSecond % 1_000_000 == 0) {
      return capacity + "-per-" + window.toMillis() + "ms";
    }
    return capacity + "-per-" + window.toNanos() + "ns";
  }

  /**
   * Formats the window using days where possible (for quota-period use cases), falling back to
   * seconds / milliseconds / nanoseconds.
   */
  private String formatWindowForNewAlgorithm() {
    long days = window.toDays();
    if (days > 0 && window.getSeconds() == days * 86400 && window.getNano() == 0) {
      return days + "d";
    }
    int nanoOfSecond = window.getNano();
    if (nanoOfSecond == 0) {
      return window.getSeconds() + "s";
    }
    if (nanoOfSecond % 1_000_000 == 0) {
      return window.toMillis() + "ms";
    }
    return window.toNanos() + "ns";
  }

  /**
   * Creates a new builder for the given window and capacity.
   *
   * @param window the time window (must be positive)
   * @param capacity the number of permits allowed within the window (must be greater than zero)
   * @return a new builder
   */
  public static Builder builder(Duration window, long capacity) {
    return new Builder(window, capacity);
  }

  /** Builder for {@link RateLimitBand}. */
  public static final class Builder {
    private final Duration window;
    private final long capacity;
    private String label;
    private RateLimitAlgorithm algorithm;
    private QuotaPeriod quotaPeriod;
    private ZoneId zoneId;
    private int slidingWindowBuckets = SLIDING_WINDOW_BUCKETS_DEFAULT;

    private Builder(Duration window, long capacity) {
      this.window = window;
      this.capacity = capacity;
    }

    /**
     * Optional human-readable label for metrics or admin UI.
     *
     * @param label the label
     * @return this builder
     */
    public Builder label(String label) {
      this.label = label;
      return this;
    }

    /**
     * Sets the rate limiting algorithm. Defaults to {@link RateLimitAlgorithm#TOKEN_BUCKET}.
     *
     * @param algorithm the algorithm (must not be null)
     * @return this builder
     * @since 0.4.0
     */
    public Builder algorithm(RateLimitAlgorithm algorithm) {
      this.algorithm = algorithm;
      return this;
    }

    /**
     * Sets the calendar-aligned quota period. Only valid with {@link
     * RateLimitAlgorithm#FIXED_WINDOW}.
     *
     * @param quotaPeriod the quota period (null to clear)
     * @return this builder
     * @since 0.4.0
     */
    public Builder quotaPeriod(QuotaPeriod quotaPeriod) {
      this.quotaPeriod = quotaPeriod;
      return this;
    }

    /**
     * Sets the zone used for calendar alignment. Defaults to {@link ZoneOffset#UTC}.
     *
     * @param zoneId the zone (must not be null)
     * @return this builder
     * @since 0.4.0
     */
    public Builder zoneId(ZoneId zoneId) {
      this.zoneId = zoneId;
      return this;
    }

    /**
     * Sets the number of sub-buckets for the {@link RateLimitAlgorithm#SLIDING_WINDOW} algorithm.
     * Must be in [2, 60]; defaults to 10.
     *
     * @param slidingWindowBuckets the sub-bucket count
     * @return this builder
     * @since 0.4.0
     */
    public Builder slidingWindowBuckets(int slidingWindowBuckets) {
      this.slidingWindowBuckets = slidingWindowBuckets;
      return this;
    }

    /**
     * Builds the band.
     *
     * @return the band
     * @throws org.fluxgate.core.exception.InvalidRuleConfigException if the window is not positive,
     *     quotaPeriod is used without FIXED_WINDOW, or slidingWindowBuckets is out of range
     * @throws IllegalArgumentException if the capacity is not greater than zero
     */
    public RateLimitBand build() {
      return new RateLimitBand(this);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RateLimitBand)) return false;
    RateLimitBand that = (RateLimitBand) o;
    return capacity == that.capacity
        && slidingWindowBuckets == that.slidingWindowBuckets
        && Objects.equals(window, that.window)
        && Objects.equals(label, that.label)
        && algorithm == that.algorithm
        && quotaPeriod == that.quotaPeriod
        && Objects.equals(zoneId, that.zoneId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        window, capacity, label, algorithm, quotaPeriod, zoneId, slidingWindowBuckets);
  }

  @Override
  public String toString() {
    return "RateLimitBand{"
        + "window="
        + window
        + ", capacity="
        + capacity
        + ", label='"
        + label
        + '\''
        + ", algorithm="
        + algorithm
        + ", quotaPeriod="
        + quotaPeriod
        + ", zoneId="
        + zoneId
        + ", slidingWindowBuckets="
        + slidingWindowBuckets
        + '}';
  }
}
