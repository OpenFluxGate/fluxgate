package org.fluxgate.spring.filter;

import static org.fluxgate.core.constants.FluxgateConstants.Headers;

import javax.servlet.http.HttpServletResponse;
import org.fluxgate.core.handler.RateLimitResponse;

/**
 * Writes rate limit headers onto the HTTP response.
 *
 * <p>Shared by {@link FluxgateRateLimitFilter} and {@link org.fluxgate.spring.aop.RateLimitAspect}
 * so both modes advertise exactly the same information. Headers are written for allowed responses
 * too, which is what lets a well behaved client pace itself instead of discovering the limit only
 * after a 429.
 *
 * <p>Two header families are supported and can be enabled independently:
 *
 * <ul>
 *   <li>legacy {@code X-RateLimit-Limit} / {@code X-RateLimit-Remaining} / {@code
 *       X-RateLimit-Reset} (epoch seconds), plus {@code Retry-After} on rejection
 *   <li>IETF style {@code RateLimit-Limit} / {@code RateLimit-Remaining} / {@code RateLimit-Reset}
 *       (delta seconds) and {@code RateLimit-Policy}
 * </ul>
 *
 * <p>A value that the rate limiter reported as unknown ({@code -1}) is omitted rather than written
 * as a misleading number.
 */
public class RateLimitHeaderWriter {

  /** Window length is not known or is sub-second, so {@code RateLimit-Policy} is omitted. */
  public static final long UNKNOWN_WINDOW_SECONDS = -1L;

  private final boolean includeLegacyHeaders;
  private final boolean includeStandardHeaders;

  /**
   * Creates a header writer.
   *
   * @param includeLegacyHeaders write the {@code X-RateLimit-*} family
   * @param includeStandardHeaders write the IETF {@code RateLimit-*} family
   */
  public RateLimitHeaderWriter(boolean includeLegacyHeaders, boolean includeStandardHeaders) {
    this.includeLegacyHeaders = includeLegacyHeaders;
    this.includeStandardHeaders = includeStandardHeaders;
  }

  /**
   * Writes the rate limit headers for a decision, taking the window length from the decision
   * itself.
   *
   * <p>This is the overload callers should use: the window travels with the {@link
   * RateLimitResponse}, so {@code RateLimit-Policy} cannot be lost by forgetting to thread it
   * through.
   *
   * @param response the HTTP response
   * @param result the rate limit decision (ignored when null)
   */
  public void write(HttpServletResponse response, RateLimitResponse result) {
    write(response, result, result != null ? result.getWindowSeconds() : UNKNOWN_WINDOW_SECONDS);
  }

  /**
   * Writes the rate limit headers for a decision.
   *
   * @param response the HTTP response
   * @param result the rate limit decision (ignored when null)
   * @param windowSeconds length of the window behind {@link RateLimitResponse#getLimit()},
   *     overriding {@link RateLimitResponse#getWindowSeconds()}. A non-positive value means unknown
   *     or sub-second, and {@code RateLimit-Policy} is then omitted rather than advertising {@code
   *     w=0}
   */
  public void write(HttpServletResponse response, RateLimitResponse result, long windowSeconds) {
    if (response == null || result == null || response.isCommitted()) {
      return;
    }

    long limit = result.getLimit();
    long remaining = result.getRemainingTokens();
    long resetTimeMillis = result.getResetTimeMillis();

    if (includeLegacyHeaders) {
      if (limit >= 0) {
        response.setHeader(Headers.RATE_LIMIT_LIMIT, Long.toString(limit));
      }
      if (remaining >= 0) {
        response.setHeader(Headers.RATE_LIMIT_REMAINING, Long.toString(remaining));
      }
      if (resetTimeMillis >= 0) {
        response.setHeader(
            Headers.RATE_LIMIT_RESET, Long.toString(ceilDiv(resetTimeMillis, 1000L)));
      }
    }

    if (includeStandardHeaders) {
      if (limit >= 0) {
        response.setHeader(Headers.STANDARD_RATE_LIMIT_LIMIT, Long.toString(limit));
      }
      if (remaining >= 0) {
        response.setHeader(Headers.STANDARD_RATE_LIMIT_REMAINING, Long.toString(remaining));
      }
      if (resetTimeMillis >= 0) {
        long deltaMillis = Math.max(0L, resetTimeMillis - System.currentTimeMillis());
        response.setHeader(
            Headers.STANDARD_RATE_LIMIT_RESET, Long.toString(ceilDiv(deltaMillis, 1000L)));
      }
      // A zero limit or a sub-second window would render as "0;w=..." or "...;w=0", neither of
      // which is a quota a client can act on, so the header is omitted instead.
      if (limit > 0 && windowSeconds > 0) {
        response.setHeader(Headers.STANDARD_RATE_LIMIT_POLICY, limit + ";w=" + windowSeconds);
      }
    }

    if (!result.isAllowed()) {
      response.setHeader(Headers.RETRY_AFTER, Long.toString(retryAfterSeconds(result)));
    }
  }

  /**
   * Converts the retry delay into whole seconds, rounding up and never returning {@code 0}.
   *
   * <p>A sub-second delay truncated to {@code Retry-After: 0} sends clients into a busy loop, which
   * is exactly the traffic the rate limiter is meant to shed.
   *
   * @param result the rejected rate limit decision
   * @return the number of seconds to advertise, at least 1
   */
  public static long retryAfterSeconds(RateLimitResponse result) {
    long millis = result.getRetryAfterMillis();
    if (millis <= 0) {
      return 1L;
    }
    return Math.max(1L, ceilDiv(millis, 1000L));
  }

  private static long ceilDiv(long value, long divisor) {
    return (value + divisor - 1) / divisor;
  }
}
