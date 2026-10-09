package org.fluxgate.redis.store;

import java.util.Objects;

/**
 * Represents the result of a token bucket consume operation.
 *
 * <p>The numbers describe the <em>binding</em> band of the call: the band that rejected the
 * request, or - when the request was allowed - the band left with the fewest tokens. {@link
 * #limit()} is that band's capacity and {@link #bandIndex()} its position in the band list that was
 * passed to {@link RedisTokenBucketStore#tryConsume(java.util.List, java.util.List, long)}, so the
 * caller can map the result back onto a {@code RateLimitBand} for HTTP headers. {@link
 * #redisTimeMicros()} is the Redis clock the decision was taken at, which a later refund of the
 * consumption needs. Unknown values are {@code -1}.
 */
public final class BucketState {

  /** Index reported when the binding band is unknown. */
  public static final int UNKNOWN_BAND_INDEX = -1;

  private final boolean consumed;
  private final long remainingTokens;
  private final long nanosToWaitForRefill;
  private final long resetTimeMillis;
  private final long limit;
  private final int bandIndex;
  private final long redisTimeMicros;

  /**
   * Creates a new BucketState.
   *
   * @param consumed Whether the permits were successfully consumed (1 = allowed, 0 = rejected)
   * @param remainingTokens Number of tokens remaining in the bucket
   * @param nanosToWaitForRefill Nanoseconds to wait until enough tokens are available
   * @param resetTimeMillis Unix timestamp in milliseconds when bucket will be full again
   */
  public BucketState(
      boolean consumed, long remainingTokens, long nanosToWaitForRefill, long resetTimeMillis) {
    this(consumed, remainingTokens, nanosToWaitForRefill, resetTimeMillis, -1L, UNKNOWN_BAND_INDEX);
  }

  /**
   * Creates a new BucketState that also identifies the binding band.
   *
   * @param consumed Whether the permits were successfully consumed
   * @param remainingTokens Number of tokens remaining in the binding band's bucket
   * @param nanosToWaitForRefill Nanoseconds to wait until enough tokens are available
   * @param resetTimeMillis Unix timestamp in milliseconds when the bucket will be full again
   * @param limit Capacity of the binding band, or {@code -1} if unknown
   * @param bandIndex Zero-based index of the binding band, or {@link #UNKNOWN_BAND_INDEX}
   */
  public BucketState(
      boolean consumed,
      long remainingTokens,
      long nanosToWaitForRefill,
      long resetTimeMillis,
      long limit,
      int bandIndex) {
    this(consumed, remainingTokens, nanosToWaitForRefill, resetTimeMillis, limit, bandIndex, -1L);
  }

  /**
   * Creates a new BucketState that identifies the binding band and the Redis time of the decision.
   *
   * @param consumed Whether the permits were successfully consumed
   * @param remainingTokens Number of tokens remaining in the binding band's bucket
   * @param nanosToWaitForRefill Nanoseconds to wait until enough tokens are available
   * @param resetTimeMillis Unix timestamp in milliseconds when the bucket will be full again
   * @param limit Capacity of the binding band, or {@code -1} if unknown
   * @param bandIndex Zero-based index of the binding band, or {@link #UNKNOWN_BAND_INDEX}
   * @param redisTimeMicros Redis {@code TIME} of the decision in microseconds, or {@code -1}
   */
  public BucketState(
      boolean consumed,
      long remainingTokens,
      long nanosToWaitForRefill,
      long resetTimeMillis,
      long limit,
      int bandIndex,
      long redisTimeMicros) {
    this.consumed = consumed;
    this.remainingTokens = remainingTokens;
    this.nanosToWaitForRefill = nanosToWaitForRefill;
    this.resetTimeMillis = resetTimeMillis;
    this.limit = limit;
    this.bandIndex = bandIndex;
    this.redisTimeMicros = redisTimeMicros;
  }

  /** Create a BucketState for a successful consumption. */
  public static BucketState allowed(long remainingTokens, long resetTimeMillis) {
    return new BucketState(true, remainingTokens, 0, resetTimeMillis);
  }

  /**
   * Create a BucketState for a successful consumption that identifies the binding band.
   *
   * @param remainingTokens tokens left in the binding band's bucket
   * @param resetTimeMillis epoch millis when that bucket is full again
   * @param limit capacity of the binding band
   * @param bandIndex zero-based index of the binding band
   * @return the allowed state
   */
  public static BucketState allowed(
      long remainingTokens, long resetTimeMillis, long limit, int bandIndex) {
    return new BucketState(true, remainingTokens, 0, resetTimeMillis, limit, bandIndex);
  }

  /** Create a BucketState for a rejected consumption. */
  public static BucketState rejected(long remainingTokens, long nanosToWait, long resetTimeMillis) {
    return new BucketState(false, remainingTokens, nanosToWait, resetTimeMillis);
  }

  /**
   * Create a BucketState for a rejected consumption that identifies the rejecting band.
   *
   * @param remainingTokens tokens left in the rejecting band's bucket
   * @param nanosToWait nanoseconds until that band can serve the request
   * @param resetTimeMillis epoch millis when that bucket is full again
   * @param limit capacity of the rejecting band
   * @param bandIndex zero-based index of the rejecting band
   * @return the rejected state
   */
  public static BucketState rejected(
      long remainingTokens, long nanosToWait, long resetTimeMillis, long limit, int bandIndex) {
    return new BucketState(false, remainingTokens, nanosToWait, resetTimeMillis, limit, bandIndex);
  }

  /** Whether the permits were successfully consumed. */
  public boolean consumed() {
    return consumed;
  }

  /** Number of tokens remaining in the bucket. */
  public long remainingTokens() {
    return remainingTokens;
  }

  /** Nanoseconds to wait until enough tokens are available. */
  public long nanosToWaitForRefill() {
    return nanosToWaitForRefill;
  }

  /** Unix timestamp in milliseconds when bucket will be full again. */
  public long resetTimeMillis() {
    return resetTimeMillis;
  }

  /**
   * Capacity of the binding band, or {@code -1} if unknown.
   *
   * @return the band capacity
   */
  public long limit() {
    return limit;
  }

  /**
   * Zero-based index of the binding band within the band list of the call, or {@link
   * #UNKNOWN_BAND_INDEX} if unknown.
   *
   * @return the band index
   */
  public int bandIndex() {
    return bandIndex;
  }

  /**
   * Redis {@code TIME}, in microseconds since the epoch, at which the decision was taken, or {@code
   * -1} if unknown. It identifies the sliding sub-bucket and fixed window a consumption charged, so
   * {@link RedisTokenBucketStore#refund} can give back exactly that.
   *
   * @return the decision time in microseconds, or {@code -1}
   */
  public long redisTimeMicros() {
    return redisTimeMicros;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof BucketState)) return false;
    BucketState that = (BucketState) o;
    return consumed == that.consumed
        && remainingTokens == that.remainingTokens
        && nanosToWaitForRefill == that.nanosToWaitForRefill
        && resetTimeMillis == that.resetTimeMillis
        && limit == that.limit
        && bandIndex == that.bandIndex
        && redisTimeMicros == that.redisTimeMicros;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        consumed,
        remainingTokens,
        nanosToWaitForRefill,
        resetTimeMillis,
        limit,
        bandIndex,
        redisTimeMicros);
  }

  @Override
  public String toString() {
    return "BucketState{"
        + "consumed="
        + consumed
        + ", remainingTokens="
        + remainingTokens
        + ", nanosToWaitForRefill="
        + nanosToWaitForRefill
        + ", resetTimeMillis="
        + resetTimeMillis
        + ", limit="
        + limit
        + ", bandIndex="
        + bandIndex
        + ", redisTimeMicros="
        + redisTimeMicros
        + '}';
  }
}
