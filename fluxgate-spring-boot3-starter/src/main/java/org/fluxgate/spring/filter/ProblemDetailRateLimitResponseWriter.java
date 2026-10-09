package org.fluxgate.spring.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.fluxgate.core.handler.RateLimitResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

/**
 * Default {@link RateLimitResponseWriter}: writes an RFC 9457 problem document.
 *
 * <p>The body is produced without a JSON library so the starter keeps working in applications that
 * do not have Jackson on the classpath:
 *
 * <pre>
 * {"type":"about:blank","title":"Too Many Requests","status":429,
 *  "detail":"Rate limit exceeded, retry after 30 seconds","retryAfterMillis":30000}
 * </pre>
 *
 * <p>A custom {@code fluxgate.ratelimit.response.body-template} replaces the document entirely. The
 * placeholders {@code {status}}, {@code {retryAfterSeconds}}, {@code {retryAfterMillis}}, {@code
 * {remaining}} and {@code {limit}} are substituted; everything else is copied verbatim.
 */
public class ProblemDetailRateLimitResponseWriter implements RateLimitResponseWriter {

  private static final Logger log =
      LoggerFactory.getLogger(ProblemDetailRateLimitResponseWriter.class);

  /** Media type used when none is configured. */
  public static final String DEFAULT_CONTENT_TYPE = "application/problem+json";

  private final String contentType;
  private final String bodyTemplate;

  /** Creates a writer using the default problem+json content type and body. */
  public ProblemDetailRateLimitResponseWriter() {
    this(DEFAULT_CONTENT_TYPE, null);
  }

  /**
   * Creates a writer.
   *
   * @param contentType the response content type; {@code charset=UTF-8} is appended when the value
   *     does not already carry a charset. Falls back to {@value #DEFAULT_CONTENT_TYPE} when blank
   * @param bodyTemplate optional body template replacing the problem document (nullable)
   */
  public ProblemDetailRateLimitResponseWriter(String contentType, String bodyTemplate) {
    String resolved = StringUtils.hasText(contentType) ? contentType.trim() : DEFAULT_CONTENT_TYPE;
    this.contentType =
        resolved.toLowerCase(Locale.ROOT).contains("charset=")
            ? resolved
            : resolved + ";charset=UTF-8";
    this.bodyTemplate = StringUtils.hasText(bodyTemplate) ? bodyTemplate : null;
  }

  @Override
  public void write(
      HttpServletRequest request, HttpServletResponse response, RateLimitResponse result)
      throws IOException {

    if (response.isCommitted()) {
      // The downstream application already started writing; overwriting would only produce an
      // IllegalStateException and hide the real cause.
      log.debug("Response already committed, cannot write rate limit body");
      return;
    }

    long retryAfterSeconds = RateLimitHeaderWriter.retryAfterSeconds(result);

    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType(contentType);
    response.getWriter().write(buildBody(result, retryAfterSeconds));
  }

  private String buildBody(RateLimitResponse result, long retryAfterSeconds) {
    if (bodyTemplate != null) {
      return bodyTemplate
          .replace("{status}", Integer.toString(HttpStatus.TOO_MANY_REQUESTS.value()))
          .replace("{retryAfterSeconds}", Long.toString(retryAfterSeconds))
          .replace("{retryAfterMillis}", Long.toString(Math.max(0L, result.getRetryAfterMillis())))
          .replace("{remaining}", Long.toString(result.getRemainingTokens()))
          .replace("{limit}", Long.toString(result.getLimit()));
    }

    return "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":"
        + HttpStatus.TOO_MANY_REQUESTS.value()
        + ",\"detail\":\"Rate limit exceeded, retry after "
        + retryAfterSeconds
        + " seconds\",\"retryAfterMillis\":"
        + Math.max(0L, result.getRetryAfterMillis())
        + "}";
  }
}
