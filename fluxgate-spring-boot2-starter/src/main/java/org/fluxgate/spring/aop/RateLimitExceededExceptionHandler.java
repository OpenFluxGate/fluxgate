package org.fluxgate.spring.aop;

import static org.fluxgate.core.constants.FluxgateConstants.Headers;

import java.io.IOException;
import java.util.Objects;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.spring.filter.RateLimitHeaderWriter;
import org.fluxgate.spring.filter.RateLimitResponseWriter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Default Spring MVC mapping of {@link RateLimitExceededException}: HTTP 429 with the rate limit
 * headers and {@code Retry-After}, or HTTP 503 when {@link
 * RateLimitExceededException#isServiceUnavailable()} is set.
 *
 * <p>The aspect throws the exception whenever it cannot write the response itself - a {@code
 * RateLimit} service method called from a controller, a method returning a primitive, or {@code
 * throwOnReject = true}. Without a handler Spring MVC answers it with HTTP 500. The body is
 * rendered by the same {@link RateLimitResponseWriter} the filter and the aspect use, so every
 * rejection has one format.
 *
 * <p>Registered by {@code FluxgateAopExceptionHandlerAutoConfiguration} in a servlet web
 * application that uses the aspect, unless a bean of this type already exists.
 *
 * <p>It is ordered at {@link #ORDER}, close to the highest precedence. Spring MVC asks the advice
 * beans in order and uses the first one with any matching handler, however general: an application
 * advice with a catch-all {@code @ExceptionHandler(Exception.class)} at the default (lowest)
 * precedence would otherwise answer every rejection with its own 500. This advice handles nothing
 * but {@link RateLimitExceededException}, so its precedence affects no other exception. To answer
 * the exception yourself, either
 *
 * <ul>
 *   <li>declare a bean of this type (a subclass, for example), which replaces the default; or
 *   <li>handle the exception in an advice ordered before this one, for example
 *       {@code @Order(RateLimitExceededExceptionHandler.ORDER - 1)}.
 * </ul>
 *
 * <p>An application advice ordered before {@link #ORDER} with a catch-all handler still wins.
 *
 * @since 0.4.0
 */
@RestControllerAdvice
@Order(RateLimitExceededExceptionHandler.ORDER)
public class RateLimitExceededExceptionHandler {

  /**
   * The order of this advice: {@code Ordered.HIGHEST_PRECEDENCE + 1000}, ahead of unordered
   * application advice and of advice with an explicit order of 0 or more.
   */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1000;

  private final RateLimitHeaderWriter headerWriter;
  private final RateLimitResponseWriter responseWriter;

  /**
   * Creates the handler.
   *
   * @param headerWriter writes the rate limit headers of a 429 (required)
   * @param responseWriter writes the 429 and 503 bodies (required)
   */
  public RateLimitExceededExceptionHandler(
      RateLimitHeaderWriter headerWriter, RateLimitResponseWriter responseWriter) {
    this.headerWriter = Objects.requireNonNull(headerWriter, "headerWriter must not be null");
    this.responseWriter = Objects.requireNonNull(responseWriter, "responseWriter must not be null");
  }

  /**
   * Answers the rejection: 503 when rate limiting is unavailable, otherwise 429.
   *
   * @param exception the rejection
   * @param request the current request
   * @param response the current response
   * @throws IOException if the response cannot be written
   */
  @ExceptionHandler(RateLimitExceededException.class)
  public void handleRateLimitExceeded(
      RateLimitExceededException exception,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    long retryAfterMillis = exception.getRetryAfterMillis();
    if (exception.isServiceUnavailable()) {
      if (retryAfterMillis > 0 && !response.isCommitted()) {
        response.setHeader(Headers.RETRY_AFTER, Long.toString((retryAfterMillis + 999L) / 1000L));
      }
      responseWriter.writeUnavailable(request, response, retryAfterMillis);
      return;
    }
    RateLimitResponse result = exception.getRateLimitResponse();
    if (result == null) {
      // lost in serialization: the delay is still known
      result = RateLimitResponse.rejected(Math.max(0L, retryAfterMillis));
    }
    headerWriter.write(response, result);
    responseWriter.write(request, response, result);
  }
}
