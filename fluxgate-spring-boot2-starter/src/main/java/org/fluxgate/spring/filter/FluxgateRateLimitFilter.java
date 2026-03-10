package org.fluxgate.spring.filter;

import static org.fluxgate.core.constants.FluxgateConstants.Headers;
import static org.fluxgate.core.constants.FluxgateConstants.MdcKeys;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.spring.util.ClientIpExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTTP Filter that applies rate limiting to incoming requests.
 *
 * <p>This filter delegates rate limiting logic to a {@link FluxgateRateLimitHandler}, which allows
 * flexible implementation strategies:
 *
 * <ul>
 *   <li>API-based: Call external FluxGate API server
 *   <li>Redis direct: Use Redis rate limiter directly
 *   <li>Standalone: In-memory rate limiting (for testing)
 * </ul>
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Extracts client IP from request (supports X-Forwarded-For)
 *   <li>Configurable include/exclude URL patterns
 *   <li>Sets standard rate limit HTTP headers
 *   <li>Returns 429 Too Many Requests when limit exceeded
 *   <li>Supports WAIT_FOR_REFILL policy with semaphore-based concurrency control
 * </ul>
 */
public class FluxgateRateLimitFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(FluxgateRateLimitFilter.class);

  private final FluxgateRateLimitHandler handler;
  private final String ruleSetId;
  private final String[] includePatterns;
  private final String[] excludePatterns;
  private final AntPathMatcher pathMatcher = new AntPathMatcher();

  // WAIT_FOR_REFILL configuration
  private final boolean waitForRefillEnabled;
  private final long maxWaitTimeMs;
  private final Semaphore waitSemaphore;

  // RequestContext customization
  private final RequestContextCustomizer contextCustomizer;

  /**
   * Creates a new FluxgateRateLimitFilter with default settings (WAIT_FOR_REFILL disabled).
   *
   * @param handler The rate limit handler (required)
   * @param ruleSetId Default rule set ID to use
   * @param includePatterns URL patterns to include
   * @param excludePatterns URL patterns to exclude
   */
  public FluxgateRateLimitFilter(
      FluxgateRateLimitHandler handler,
      String ruleSetId,
      String[] includePatterns,
      String[] excludePatterns) {
    this(handler, ruleSetId, includePatterns, excludePatterns, false, 5000, 100, null);
  }

  /**
   * Creates a new FluxgateRateLimitFilter with WAIT_FOR_REFILL configuration.
   *
   * @param handler The rate limit handler (required)
   * @param ruleSetId Default rule set ID to use
   * @param includePatterns URL patterns to include
   * @param excludePatterns URL patterns to exclude
   * @param waitForRefillEnabled Enable WAIT_FOR_REFILL behavior
   * @param maxWaitTimeMs Maximum time to wait for token refill
   * @param maxConcurrentWaits Maximum concurrent waiting requests (semaphore permits)
   */
  public FluxgateRateLimitFilter(
      FluxgateRateLimitHandler handler,
      String ruleSetId,
      String[] includePatterns,
      String[] excludePatterns,
      boolean waitForRefillEnabled,
      long maxWaitTimeMs,
      int maxConcurrentWaits) {
    this(
        handler,
        ruleSetId,
        includePatterns,
        excludePatterns,
        waitForRefillEnabled,
        maxWaitTimeMs,
        maxConcurrentWaits,
        null);
  }

  /**
   * Creates a new FluxgateRateLimitFilter with full configuration including RequestContext
   * customization.
   *
   * @param handler The rate limit handler (required)
   * @param ruleSetId Default rule set ID to use
   * @param includePatterns URL patterns to include
   * @param excludePatterns URL patterns to exclude
   * @param waitForRefillEnabled Enable WAIT_FOR_REFILL behavior
   * @param maxWaitTimeMs Maximum time to wait for token refill
   * @param maxConcurrentWaits Maximum concurrent waiting requests (semaphore permits)
   * @param contextCustomizer Customizer for RequestContext (nullable)
   */
  public FluxgateRateLimitFilter(
      FluxgateRateLimitHandler handler,
      String ruleSetId,
      String[] includePatterns,
      String[] excludePatterns,
      boolean waitForRefillEnabled,
      long maxWaitTimeMs,
      int maxConcurrentWaits,
      RequestContextCustomizer contextCustomizer) {
    this.handler = Objects.requireNonNull(handler, "handler must not be null");
    this.ruleSetId = Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    this.includePatterns = includePatterns != null ? includePatterns : new String[0];
    this.excludePatterns = excludePatterns != null ? excludePatterns : new String[0];
    this.waitForRefillEnabled = waitForRefillEnabled;
    this.maxWaitTimeMs = maxWaitTimeMs;
    this.waitSemaphore = new Semaphore(maxConcurrentWaits);
    this.contextCustomizer =
        contextCustomizer != null ? contextCustomizer : RequestContextCustomizer.identity();

    if (log.isDebugEnabled()) {
      log.debug("FluxgateRateLimitFilter initialized");
      log.debug("  Handler: {}", handler.getClass().getSimpleName());
      log.debug("  Rule set ID: {}", ruleSetId);
      log.debug("  Include patterns: {}", Arrays.toString(this.includePatterns));
      log.debug("  Exclude patterns: {}", Arrays.toString(this.excludePatterns));
      log.debug("  WAIT_FOR_REFILL enabled: {}", waitForRefillEnabled);
      if (waitForRefillEnabled) {
        log.debug("  Max wait time: {} ms", maxWaitTimeMs);
        log.debug("  Max concurrent waits: {}", maxConcurrentWaits);
      }
      log.debug(
          "  RequestContext customizer: {}",
          contextCustomizer != null ? contextCustomizer.getClass().getSimpleName() : "default");
    }
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    long startTimeMs = System.currentTimeMillis();

    // 1. MDC Setup - Comprehensive Request Information
    String traceId = request.getHeader(Headers.TRACE_ID);
    if (traceId == null || traceId.isEmpty()) {
      traceId = UUID.randomUUID().toString();
    }

    // Core identifiers
    MDC.put(MdcKeys.TRACE_ID, traceId);
    MDC.put(MdcKeys.RULE_SET_ID, ruleSetId);

    // Request details
    MDC.put(MdcKeys.METHOD, request.getMethod());
    MDC.put(MdcKeys.ENDPOINT, request.getRequestURI());
    MDC.put(MdcKeys.CLIENT_IP, ClientIpExtractor.extract(request));
    MDC.put(MdcKeys.PROTOCOL, request.getProtocol());
    MDC.put(MdcKeys.SERVER_PORT, String.valueOf(request.getServerPort()));

    // Query and context
    Optional.ofNullable(request.getQueryString()).ifPresent(v -> MDC.put(MdcKeys.QUERY_STRING, v));
    Optional.ofNullable(request.getHeader("User-Agent"))
        .ifPresent(v -> MDC.put(MdcKeys.USER_AGENT, v));
    Optional.ofNullable(request.getHeader("Referer")).ifPresent(v -> MDC.put(MdcKeys.REFERER, v));

    // User identification (if available)
    Optional.ofNullable(request.getHeader(Headers.USER_ID))
        .ifPresent(v -> MDC.put(MdcKeys.USER_ID, v));
    Optional.ofNullable(request.getHeader(Headers.API_KEY))
        .ifPresent(v -> MDC.put(MdcKeys.API_KEY, maskSensitive(v)));

    String path = request.getRequestURI();

    // Check if this path should be excluded
    if (shouldExclude(path)) {
      log.debug("Path excluded from rate limiting: {}", path);
      filterChain.doFilter(request, response);
      return;
    }

    // Check if this path should be included
    if (!shouldInclude(path)) {
      log.debug("Path not included in rate limiting: {}", path);
      filterChain.doFilter(request, response);
      return;
    }

    // Check rule set ID
    if (!StringUtils.hasText(ruleSetId)) {
      log.warn("No rule set ID configured, skipping rate limiting");
      filterChain.doFilter(request, response);
      return;
    }

    // Build request context
    RequestContext context = buildRequestContext(request);

    // Apply rate limiting via handler
    try {
      RateLimitResponse result = handler.tryConsume(context, ruleSetId);

      // Add rate limit result to MDC
      MDC.put(MdcKeys.RATE_LIMIT_ALLOWED, String.valueOf(result.isAllowed()));
      MDC.put(MdcKeys.REMAINING_TOKENS, String.valueOf(result.getRemainingTokens()));

      // Add rate limit headers
      addRateLimitHeaders(response, result);

      if (result.isAllowed()) {
        filterChain.doFilter(request, response);
        MDC.put(MdcKeys.STATUS_CODE, String.valueOf(response.getStatus()));
        MDC.put(MdcKeys.DURATION_MS, String.valueOf(System.currentTimeMillis() - startTimeMs));
        log.info("Request completed");
      } else if (shouldWaitForRefill(result)) {
        // WAIT_FOR_REFILL policy: wait for tokens and retry
        handleWaitForRefill(request, response, filterChain, context, result);
      } else {
        MDC.put(MdcKeys.RETRY_AFTER_MS, String.valueOf(result.getRetryAfterMillis()));
        MDC.put(MdcKeys.STATUS_CODE, "429");
        MDC.put(MdcKeys.DURATION_MS, String.valueOf(System.currentTimeMillis() - startTimeMs));
        log.warn("Request rate limited");
        handleRateLimitExceeded(response, result);
      }
    } catch (Exception e) {
      MDC.put(MdcKeys.ERROR, e.getClass().getSimpleName());
      MDC.put(MdcKeys.ERROR_MESSAGE, e.getMessage());
      MDC.put(MdcKeys.STATUS_CODE, "500");
      MDC.put(MdcKeys.DURATION_MS, String.valueOf(System.currentTimeMillis() - startTimeMs));
      log.error("Error during rate limiting, allowing request", e);
      // Fail open: allow request if rate limiter fails
      filterChain.doFilter(request, response);
    } finally {
      // Clear MDC
      MDC.clear();
    }
  }

  /**
   * Check if we should wait for refill based on response policy and configuration.
   *
   * @param result The rate limit response
   * @return true if we should wait for refill
   */
  private boolean shouldWaitForRefill(RateLimitResponse result) {
    return waitForRefillEnabled && result.shouldWaitForRefill();
  }

  /**
   * Handle WAIT_FOR_REFILL policy by waiting for tokens and retrying.
   *
   * <p>Uses a semaphore to limit the number of concurrent waiting requests. If the semaphore cannot
   * be acquired, or if the wait time exceeds the maximum, the request is rejected immediately.
   */
  private void handleWaitForRefill(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain,
      RequestContext context,
      RateLimitResponse result)
      throws ServletException, IOException {

    long waitTimeMs = result.getRetryAfterMillis();
    String path = request.getRequestURI();
    String method = request.getMethod();

    // Check if wait time exceeds maximum allowed
    if (waitTimeMs > maxWaitTimeMs) {
      log.info(
          "Wait time {} ms exceeds max {} ms, rejecting: {} {} from {}",
          waitTimeMs,
          maxWaitTimeMs,
          method,
          path,
          context.getClientIp());
      handleRateLimitExceeded(response, result);
      return;
    }

    // Try to acquire semaphore permit (non-blocking)
    if (!waitSemaphore.tryAcquire()) {
      log.info(
          "Too many concurrent waits, rejecting: {} {} from {}",
          method,
          path,
          context.getClientIp());
      handleRateLimitExceeded(response, result);
      return;
    }

    try {
      log.debug(
          "Waiting {} ms for token refill: {} {} from {}",
          waitTimeMs,
          method,
          path,
          context.getClientIp());

      // Sleep and wait for tokens to become available
      TimeUnit.MILLISECONDS.sleep(waitTimeMs);

      // Retry rate limit check after waiting
      RateLimitResponse retryResult = handler.tryConsume(context, ruleSetId);
      addRateLimitHeaders(response, retryResult);

      if (retryResult.isAllowed()) {
        log.debug(
            "Request allowed after wait: {} {} from {} (remaining: {})",
            method,
            path,
            context.getClientIp(),
            retryResult.getRemainingTokens());
        filterChain.doFilter(request, response);
      } else {
        // Still rejected after waiting - reject the request
        log.info(
            "Request still rate limited after wait: {} {} from {} (retry after: {} ms)",
            method,
            path,
            context.getClientIp(),
            retryResult.getRetryAfterMillis());
        handleRateLimitExceeded(response, retryResult);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Wait interrupted for: {} {} from {}", method, path, context.getClientIp());
      handleRateLimitExceeded(response, result);
    } finally {
      waitSemaphore.release();
    }
  }

  /**
   * Builds a RequestContext from the HTTP request with full tracking information.
   *
   * <p>This method first populates default values, then applies any registered
   * RequestContextCustomizer to allow users to override or add custom fields.
   */
  private RequestContext buildRequestContext(HttpServletRequest request) {
    String clientIp = ClientIpExtractor.extract(request);
    String userId = request.getHeader(Headers.USER_ID);
    String apiKey = request.getHeader(Headers.API_KEY);

    // Build the default context with core fields
    RequestContext.Builder builder =
        RequestContext.builder()
            .clientIp(clientIp)
            .userId(userId)
            .apiKey(apiKey)
            .endpoint(request.getRequestURI())
            .method(request.getMethod());

    // Collect HTTP headers for tracking
    collectHeaders(builder, request);

    // Apply customizer to allow user overrides and custom attributes
    builder = contextCustomizer.customize(builder, request);

    return builder.build();
  }

  /** Collects all HTTP headers from the request. */
  private void collectHeaders(RequestContext.Builder builder, HttpServletRequest request) {
    java.util.Enumeration<String> headerNames = request.getHeaderNames();
    if (headerNames != null) {
      while (headerNames.hasMoreElements()) {
        String headerName = headerNames.nextElement();
        builder.header(headerName, request.getHeader(headerName));
      }
    }

    // Add servlet-specific info that's not in headers
    long contentLength = request.getContentLengthLong();
    if (contentLength > 0 && builder.getHeader("Content-Length") == null) {
      builder.header("Content-Length", String.valueOf(contentLength));
    }

    String sessionId = request.getRequestedSessionId();
    if (sessionId != null) {
      builder.header("Session-Id", sessionId);
    }
  }

  /** Adds standard rate limit headers to the response. */
  private void addRateLimitHeaders(HttpServletResponse response, RateLimitResponse result) {
    if (result.getRemainingTokens() >= 0) {
      response.setHeader(Headers.RATE_LIMIT_REMAINING, String.valueOf(result.getRemainingTokens()));
    }
  }

  /** Handles a rate limit exceeded response. */
  private void handleRateLimitExceeded(HttpServletResponse response, RateLimitResponse result)
      throws IOException {

    long retryAfterSeconds = (result.getRetryAfterMillis() + 999) / 1000;
    response.setHeader(Headers.RETRY_AFTER, String.valueOf(retryAfterSeconds));
    response.setHeader(
        Headers.RATE_LIMIT_REMAINING, String.valueOf(Math.max(0, result.getRemainingTokens())));

    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType("application/json");
    response
        .getWriter()
        .write(
            String.format(
                "{\"error\":\"Rate limit exceeded\",\"retryAfter\":%d}", retryAfterSeconds));
  }

  /** Checks if a path should be excluded from rate limiting. */
  private boolean shouldExclude(String path) {
    if (excludePatterns.length == 0) {
      return false;
    }
    for (String pattern : excludePatterns) {
      if (pathMatcher.match(pattern, path)) {
        return true;
      }
    }
    return false;
  }

  /** Checks if a path should be included in rate limiting. */
  private boolean shouldInclude(String path) {
    if (includePatterns.length == 0) {
      return true; // Include all by default
    }
    for (String pattern : includePatterns) {
      if (pathMatcher.match(pattern, path)) {
        return true;
      }
    }
    return false;
  }

  /** Masks sensitive data for logging (shows first 4 and last 4 characters). */
  private String maskSensitive(String value) {
    if (value == null || value.length() <= 8) {
      return "****";
    }
    return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
  }
}
