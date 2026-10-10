package org.fluxgate.spring.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
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
 * {remaining}} and {@code {limit}} are substituted; everything else is copied verbatim. The same
 * template renders the 503 sent when rate limiting is unavailable ({@code {status}} tells them
 * apart) unless {@code fluxgate.ratelimit.response.unavailable-body-template} gives the 503 its
 * own.
 */
public class ProblemDetailRateLimitResponseWriter implements RateLimitResponseWriter {

  private static final Logger log =
      LoggerFactory.getLogger(ProblemDetailRateLimitResponseWriter.class);

  /** Media type used when none is configured. */
  public static final String DEFAULT_CONTENT_TYPE = "application/problem+json";

  private final String contentType;
  private final String bodyTemplate;
  private final String unavailableBodyTemplate;

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
    this(contentType, bodyTemplate, null);
  }

  /**
   * Creates a writer with a separate template for the 503.
   *
   * @param contentType the response content type; {@code charset=UTF-8} is appended when the value
   *     does not already carry a charset. Falls back to {@value #DEFAULT_CONTENT_TYPE} when blank
   * @param bodyTemplate optional body template replacing the problem document (nullable)
   * @param unavailableBodyTemplate optional body template for the 503 sent when rate limiting is
   *     unavailable (nullable: {@code bodyTemplate} is used)
   * @since 0.4.0
   */
  public ProblemDetailRateLimitResponseWriter(
      String contentType, String bodyTemplate, String unavailableBodyTemplate) {
    String resolved = StringUtils.hasText(contentType) ? contentType.trim() : DEFAULT_CONTENT_TYPE;
    this.contentType =
        resolved.toLowerCase(Locale.ROOT).contains("charset=")
            ? resolved
            : resolved + ";charset=UTF-8";
    this.bodyTemplate = StringUtils.hasText(bodyTemplate) ? bodyTemplate : null;
    this.unavailableBodyTemplate =
        StringUtils.hasText(unavailableBodyTemplate) ? unavailableBodyTemplate : this.bodyTemplate;
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
    writeBody(
        response,
        bodyTemplate,
        HttpStatus.TOO_MANY_REQUESTS,
        "Rate limit exceeded, retry after " + retryAfterSeconds + " seconds",
        retryAfterSeconds,
        Math.max(0L, result.getRetryAfterMillis()),
        result.getRemainingTokens(),
        result.getLimit());
  }

  /**
   * Writes HTTP 503 for a request rejected because rate limiting is unavailable or not configured.
   * The unavailable body template - or, when it is not set, the body template - is used here, with
   * {@code {status}} set to 503 and unknown values rendered as {@code -1}.
   */
  @Override
  public void writeUnavailable(
      HttpServletRequest request, HttpServletResponse response, long retryAfterMillis)
      throws IOException {
    if (response.isCommitted()) {
      log.debug("Response already committed, cannot write rate limit body");
      return;
    }
    long retryAfterSeconds = retryAfterMillis > 0 ? (retryAfterMillis + 999L) / 1000L : -1L;
    writeBody(
        response,
        unavailableBodyTemplate,
        HttpStatus.SERVICE_UNAVAILABLE,
        "Rate limiting is temporarily unavailable",
        retryAfterSeconds,
        retryAfterMillis > 0 ? retryAfterMillis : -1L,
        -1L,
        -1L);
  }

  /**
   * Writes HTTP 429 for a request whose cost exceeds the capacity of a matching band. A configured
   * body template is used here too, with {@code {limit}} set to the capacity and the other values
   * rendered as {@code -1}.
   */
  @Override
  public void writeCostExceeded(
      HttpServletRequest request, HttpServletResponse response, long permits, long capacity)
      throws IOException {
    if (response.isCommitted()) {
      log.debug("Response already committed, cannot write rate limit body");
      return;
    }
    writeBody(
        response,
        bodyTemplate,
        HttpStatus.TOO_MANY_REQUESTS,
        RateLimitResponseWriter.costExceededDetail(permits, capacity),
        -1L,
        -1L,
        -1L,
        capacity > 0 ? capacity : -1L);
  }

  private void writeBody(
      HttpServletResponse response,
      String template,
      HttpStatus status,
      String detail,
      long retryAfterSeconds,
      long retryAfterMillis,
      long remaining,
      long limit)
      throws IOException {
    response.setStatus(status.value());
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType(contentType);
    response
        .getWriter()
        .write(
            buildBody(
                template, status, detail, retryAfterSeconds, retryAfterMillis, remaining, limit));
  }

  private static String buildBody(
      String template,
      HttpStatus status,
      String detail,
      long retryAfterSeconds,
      long retryAfterMillis,
      long remaining,
      long limit) {
    if (template != null) {
      return template
          .replace("{status}", Integer.toString(status.value()))
          .replace("{retryAfterSeconds}", Long.toString(retryAfterSeconds))
          .replace("{retryAfterMillis}", Long.toString(retryAfterMillis))
          .replace("{remaining}", Long.toString(remaining))
          .replace("{limit}", Long.toString(limit));
    }

    StringBuilder body =
        new StringBuilder("{\"type\":\"about:blank\",\"title\":\"")
            .append(status.getReasonPhrase())
            .append("\",\"status\":")
            .append(status.value())
            .append(",\"detail\":\"")
            .append(detail)
            .append('"');
    if (retryAfterMillis >= 0) {
      body.append(",\"retryAfterMillis\":").append(retryAfterMillis);
    }
    return body.append('}').toString();
  }
}
