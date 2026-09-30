package org.fluxgate.spring.filter;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.fluxgate.core.handler.RateLimitResponse;

/**
 * Strategy for writing the body of a rate limited (429) response.
 *
 * <p>Register a bean of this type to replace the default RFC 9457 problem document with your own
 * error envelope. The implementation owns the status code, the content type and the body; rate
 * limit headers are written before it runs by {@link RateLimitHeaderWriter}.
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
}
