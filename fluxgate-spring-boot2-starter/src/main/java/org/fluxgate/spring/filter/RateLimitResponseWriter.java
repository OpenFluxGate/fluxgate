package org.fluxgate.spring.filter;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.fluxgate.core.handler.RateLimitResponse;

/**
 * Strategy for writing the body of a rate limited (429) response.
 *
 * <p>Register a bean of this type to replace the default RFC 9457 problem document with your own
 * error envelope. {@link #write} renders an exceeded limit (429); {@link #writeUnavailable} renders
 * a rejection caused by the rate limiter being unavailable or unconfigured (503); {@link
 * #writeCostExceeded} renders a request whose cost no band can hold (429). The implementation owns
 * the status code, the content type and the body; rate limit headers are written before it runs by
 * {@link RateLimitHeaderWriter}.
 *
 * <p>Example:
 *
 * <pre>
 * {@code @Bean}
 * RateLimitResponseWriter apiErrorWriter(ObjectMapper mapper) {
 *   return (request, response, result) -&gt; {
 *     response.setStatus(429);
 *     response.setContentType("application/vnd.acme.error+json;charset=UTF-8");
 *     mapper.writeValue(response.getWriter(), new ApiError("RATE_LIMITED", result.getRetryAfterMillis()));
 *   };
 * }
 * </pre>
 *
 * <p>Implementations must be thread safe and must check {@link HttpServletResponse#isCommitted()}
 * before writing.
 *
 * @see ProblemDetailRateLimitResponseWriter
 */
@FunctionalInterface
public interface RateLimitResponseWriter {

  /**
   * Writes the rate limited response.
   *
   * @param request the HTTP request that was rejected (may be null for non-web invocations)
   * @param response the HTTP response to write to
   * @param result the rate limit decision that caused the rejection
   * @throws IOException if the response cannot be written
   */
  void write(HttpServletRequest request, HttpServletResponse response, RateLimitResponse result)
      throws IOException;

  /**
   * Writes the response for a request rejected because rate limiting is unavailable or not
   * configured: the limiter failed under {@code failure-behavior=DENY}, or no rule set is
   * configured under {@code missing-rule-behavior=DENY}.
   *
   * <p>The default writes HTTP 503 with a minimal RFC 9457 problem document. {@code Retry-After}
   * has already been set when the delay is known. Override it to use the application's own error
   * envelope.
   *
   * @param request the HTTP request that was rejected (may be null for non-web invocations)
   * @param response the HTTP response to write to
   * @param retryAfterMillis how long the client should wait, or a negative value when unknown
   * @throws IOException if the response cannot be written
   * @since 0.4.0
   */
  default void writeUnavailable(
      HttpServletRequest request, HttpServletResponse response, long retryAfterMillis)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    response.setStatus(503);
    response.setCharacterEncoding("UTF-8");
    response.setContentType("application/problem+json;charset=UTF-8");
    response
        .getWriter()
        .write(
            "{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503,"
                + "\"detail\":\"Rate limiting is temporarily unavailable\"}");
  }

  /**
   * Writes the response for a request whose cost exceeds the capacity of a matching rate limit band
   * - typically a {@code cost-header} value above the rule's capacity. The request can never be
   * served, so no {@code Retry-After} is set.
   *
   * <p>The default writes HTTP 429 with a minimal RFC 9457 problem document naming the cost and the
   * capacity. Override it to use the application's own error envelope.
   *
   * @param request the HTTP request that was rejected (may be null for non-web invocations)
   * @param response the HTTP response to write to
   * @param permits the cost the request asked for
   * @param capacity the smallest capacity of the matching bands, or a negative value when unknown
   * @throws IOException if the response cannot be written
   * @since 0.4.0
   */
  default void writeCostExceeded(
      HttpServletRequest request, HttpServletResponse response, long permits, long capacity)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    response.setStatus(429);
    response.setCharacterEncoding("UTF-8");
    response.setContentType("application/problem+json;charset=UTF-8");
    response
        .getWriter()
        .write(
            "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\""
                + costExceededDetail(permits, capacity)
                + "\"}");
  }

  /**
   * The problem document detail for {@link #writeCostExceeded}.
   *
   * @param permits the cost the request asked for
   * @param capacity the smallest capacity of the matching bands, or a negative value when unknown
   * @return a human readable explanation
   * @since 0.4.0
   */
  static String costExceededDetail(long permits, long capacity) {
    return "Request cost of "
        + permits
        + " permits exceeds the rate limit capacity"
        + (capacity > 0 ? " of " + capacity : "")
        + " and can never be served";
  }
}
