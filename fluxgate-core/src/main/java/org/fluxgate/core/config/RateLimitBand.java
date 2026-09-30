package org.fluxgate.core.config;

import java.time.Duration;
import java.util.Objects;
import org.fluxgate.core.exception.InvalidRuleConfigException;

/**
 * Represents a single rate limit band, such as: - 1 minute, 100 requests - 10 minutes, 500 requests
 */
public final class RateLimitBand {

  private final Duration window;
  private final long capacity;
  private final String label; // optional, for metrics / admin UI

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
   * Returns the stable identifier of this band, used as the last segment of the storage bucket key.
   *
   * <p>The display {@link #getLabel() label} is used when it is set, otherwise a value derived from
   * the band configuration (for example {@code 100-per-60s}). Deriving it keeps two unlabelled
   * bands of the same rule apart, which a {@code default} placeholder would not.
   *
   * <p>The derived form carries the full precision of the window, because truncating it to whole
   * seconds made every sub-second band collide: {@code 500ms} and {@code 200ms} both rendered as
   * {@code 0s}, so a burst band pair that worked before was rejected by {@link RateLimitRule}'s
   * duplicate-label check. A window that is a whole number of seconds keeps the {@code
   * <capacity>-per-<seconds>s} form so existing storage keys are unchanged; finer windows use
   * {@code ms}, and only a sub-millisecond window falls back to {@code ns}.
   *
   * @return the band key label (never null, never blank)
   */
  public String getKeyLabel() {
    if (label != null && !label.trim().isEmpty()) {
      return label;
    }
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
     * Builds the band.
     *
     * @return the band
     * @throws org.fluxgate.core.exception.InvalidRuleConfigException if the window is not positive
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
        && Objects.equals(window, that.window)
        && Objects.equals(label, that.label);
  }

  @Override
  public int hashCode() {
    return Objects.hash(window, capacity, label);
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
        + '}';
  }
}
