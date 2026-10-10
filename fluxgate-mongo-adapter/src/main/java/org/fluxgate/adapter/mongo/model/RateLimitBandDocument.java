package org.fluxgate.adapter.mongo.model;

import java.util.Objects;

/**
 * MongoDB document representation of a single rate limit band.
 *
 * <p>Fields added in 0.4.0 ({@code algorithm}, {@code quotaPeriod}, {@code zoneId}, {@code
 * slidingWindowBuckets}) are optional for backward compatibility: documents written by 0.3.x that
 * lack them are treated as {@code TOKEN_BUCKET} with {@code UTC} timezone and 10 sliding-window
 * buckets, which exactly matches the 0.3.x defaults.
 */
public class RateLimitBandDocument {

  /** Window size in seconds. */
  private long windowSeconds;

  /** Allowed tokens per window. */
  private long capacity;

  /** Human-readable label (e.g., "per-second", "per-minute"). */
  private String label;

  /**
   * Rate limiting algorithm name (enum name of {@code RateLimitAlgorithm}).
   *
   * <p>Stored as a String for forward compatibility. Absent in 0.3.x documents — defaults to {@code
   * "TOKEN_BUCKET"}.
   *
   * @since 0.4.0
   */
  private String algorithm;

  /**
   * Calendar-aligned quota period name (enum name of {@code QuotaPeriod}), or {@code null} when not
   * applicable.
   *
   * <p>Only meaningful with {@code FIXED_WINDOW} algorithm. Absent in 0.3.x documents — defaults to
   * {@code null}.
   *
   * @since 0.4.0
   */
  private String quotaPeriod;

  /**
   * IANA time-zone id used for calendar alignment (e.g. {@code "UTC"}, {@code "America/New_York"}).
   *
   * <p>Absent in 0.3.x documents — defaults to {@code "UTC"}.
   *
   * @since 0.4.0
   */
  private String zoneId;

  /**
   * Number of sub-buckets for the {@code SLIDING_WINDOW} algorithm; must be in [2, 60].
   *
   * <p>Absent in 0.3.x documents — defaults to {@code 10}.
   *
   * @since 0.4.0
   */
  private int slidingWindowBuckets;

  /** No-arg constructor for MongoDB driver deserialization. */
  protected RateLimitBandDocument() {}

  /**
   * Creates a new band document with all 0.4.0+ fields.
   *
   * @param windowSeconds window size in seconds (must be &gt; 0)
   * @param capacity tokens allowed per window (must be &gt; 0)
   * @param label optional human-readable label
   * @param algorithm algorithm name (never null; use {@code "TOKEN_BUCKET"} as default)
   * @param quotaPeriod quota period name, or {@code null}
   * @param zoneId IANA zone id (never null; use {@code "UTC"} as default)
   * @param slidingWindowBuckets sub-bucket count; must be in [2, 60] for {@code SLIDING_WINDOW}
   */
  public RateLimitBandDocument(
      long windowSeconds,
      long capacity,
      String label,
      String algorithm,
      String quotaPeriod,
      String zoneId,
      int slidingWindowBuckets) {
    if (windowSeconds <= 0) throw new IllegalArgumentException("windowSeconds must be > 0");
    if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
    this.windowSeconds = windowSeconds;
    this.capacity = capacity;
    this.label = label;
    this.algorithm = Objects.requireNonNull(algorithm, "algorithm must not be null");
    this.quotaPeriod = quotaPeriod;
    this.zoneId = Objects.requireNonNull(zoneId, "zoneId must not be null");
    this.slidingWindowBuckets = slidingWindowBuckets;
  }

  /**
   * Creates a legacy band document (0.3.x-style) without algorithm fields.
   *
   * <p>Kept for backward compatibility. New code should use the full constructor.
   *
   * @param windowSeconds window size in seconds (must be &gt; 0)
   * @param capacity tokens allowed per window (must be &gt; 0)
   * @param label optional human-readable label
   */
  public RateLimitBandDocument(long windowSeconds, long capacity, String label) {
    this(
        windowSeconds,
        capacity,
        Objects.requireNonNull(label, "label must not be null"),
        "TOKEN_BUCKET",
        null,
        "UTC",
        10);
  }

  public long getWindowSeconds() {
    return windowSeconds;
  }

  public void setWindowSeconds(long windowSeconds) {
    this.windowSeconds = windowSeconds;
  }

  public long getCapacity() {
    return capacity;
  }

  public void setCapacity(long capacity) {
    this.capacity = capacity;
  }

  public String getLabel() {
    return label;
  }

  public void setLabel(String label) {
    this.label = label;
  }

  /**
   * Returns the algorithm name, defaulting to {@code "TOKEN_BUCKET"} when absent.
   *
   * @return the algorithm name (never null)
   * @since 0.4.0
   */
  public String getAlgorithm() {
    return algorithm != null ? algorithm : "TOKEN_BUCKET";
  }

  public void setAlgorithm(String algorithm) {
    this.algorithm = algorithm;
  }

  /**
   * Returns the quota period name, or {@code null} when none is set.
   *
   * @return the quota period name, or null
   * @since 0.4.0
   */
  public String getQuotaPeriod() {
    return quotaPeriod;
  }

  public void setQuotaPeriod(String quotaPeriod) {
    this.quotaPeriod = quotaPeriod;
  }

  /**
   * Returns the IANA zone id, defaulting to {@code "UTC"} when absent.
   *
   * @return the zone id string (never null)
   * @since 0.4.0
   */
  public String getZoneId() {
    return zoneId != null ? zoneId : "UTC";
  }

  public void setZoneId(String zoneId) {
    this.zoneId = zoneId;
  }

  /**
   * Returns the sliding-window sub-bucket count, defaulting to {@code 10} when absent.
   *
   * @return the sub-bucket count
   * @since 0.4.0
   */
  public int getSlidingWindowBuckets() {
    return slidingWindowBuckets == 0 ? 10 : slidingWindowBuckets;
  }

  public void setSlidingWindowBuckets(int slidingWindowBuckets) {
    this.slidingWindowBuckets = slidingWindowBuckets;
  }
}
