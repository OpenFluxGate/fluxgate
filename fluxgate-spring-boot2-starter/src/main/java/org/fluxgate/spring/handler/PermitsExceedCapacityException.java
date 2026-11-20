package org.fluxgate.spring.handler;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fluxgate.core.exception.InvalidRuleConfigException;

/**
 * A request asked for more permits than a band of a matching rule can ever hold.
 *
 * <p>The request cost is the client's input - typically the {@code fluxgate.ratelimit.cost-header}
 * value - so this is a client error, not a limiter failure: no amount of waiting makes the request
 * fit. {@link ResilientRateLimiter} raises it before the call reaches the circuit breaker, so an
 * unauthenticated client sending oversized costs can neither open the breaker nor trigger the
 * fallback. The filter answers it with HTTP 429 and a problem document naming the cost and the
 * capacity, without {@code Retry-After}; the aspect, whose cost is fixed by {@code
 * RateLimit#permits()}, treats it as the configuration problem it is there (HTTP 503).
 *
 * <p>It is an {@link InvalidRuleConfigException}, so a custom handler that already catches that
 * type keeps working.
 *
 * @since 0.4.0
 */
public class PermitsExceedCapacityException extends InvalidRuleConfigException {

  private static final long serialVersionUID = 1L;

  /** Capacity value used when the limiter did not report it. */
  public static final long UNKNOWN_CAPACITY = -1L;

  /**
   * The message the core and Redis limiters use for this condition: {@code permits (N) exceed the
   * capacity of band 'label' (M)}.
   */
  private static final Pattern LIMITER_MESSAGE =
      Pattern.compile("permits \\((\\d+)\\) exceed the capacity of band '[^']*' \\((\\d+)\\)");

  private final long permits;
  private final long capacity;

  /**
   * Creates the exception for a band of a known rule.
   *
   * @param permits the permits the request asked for
   * @param capacity the smallest capacity among the matching bands, or {@link #UNKNOWN_CAPACITY}
   * @param ruleId the rule whose band is too small
   */
  public PermitsExceedCapacityException(long permits, long capacity, String ruleId) {
    super(describe(permits, capacity), ruleId);
    this.permits = permits;
    this.capacity = capacity > 0 ? capacity : UNKNOWN_CAPACITY;
  }

  /**
   * Creates the exception when the rule is not known.
   *
   * @param permits the permits the request asked for
   * @param capacity the smallest capacity among the matching bands, or {@link #UNKNOWN_CAPACITY}
   */
  public PermitsExceedCapacityException(long permits, long capacity) {
    super(describe(permits, capacity));
    this.permits = permits;
    this.capacity = capacity > 0 ? capacity : UNKNOWN_CAPACITY;
  }

  private static String describe(long permits, long capacity) {
    return "Request cost of "
        + permits
        + " permits exceeds the capacity"
        + (capacity > 0 ? " of " + capacity : "")
        + " of a matching rate limit band";
  }

  /**
   * Recognises a limiter's own "permits exceed the capacity" error, for a limiter that is not
   * wrapped by {@link ResilientRateLimiter}.
   *
   * @param e the limiter's configuration error
   * @param permits the permits the request asked for
   * @return the equivalent exception, or null when {@code e} reports another configuration problem
   */
  public static PermitsExceedCapacityException from(InvalidRuleConfigException e, long permits) {
    if (e instanceof PermitsExceedCapacityException) {
      return (PermitsExceedCapacityException) e;
    }
    if (permits <= 1 || e.getMessage() == null) {
      return null;
    }
    Matcher matcher = LIMITER_MESSAGE.matcher(e.getMessage());
    if (!matcher.find()) {
      return null;
    }
    long capacity;
    try {
      capacity = Long.parseLong(matcher.group(2));
    } catch (NumberFormatException ignored) {
      capacity = UNKNOWN_CAPACITY;
    }
    PermitsExceedCapacityException converted =
        e.getRuleId() != null
            ? new PermitsExceedCapacityException(permits, capacity, e.getRuleId())
            : new PermitsExceedCapacityException(permits, capacity);
    converted.initCause(e);
    return converted;
  }

  /**
   * The permits the request asked for.
   *
   * @return the request cost in permits
   */
  public long getPermits() {
    return permits;
  }

  /**
   * The smallest capacity among the bands the request was checked against.
   *
   * @return the capacity, or {@link #UNKNOWN_CAPACITY}
   */
  public long getCapacity() {
    return capacity;
  }
}
