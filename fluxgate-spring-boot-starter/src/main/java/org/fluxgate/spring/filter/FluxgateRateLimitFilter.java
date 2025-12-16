package org.fluxgate.spring.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final String HEADER_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
  private static final String HEADER_RETRY_AFTER = "Retry-After";

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

    log.info("FluxgateRateLimitFilter initialized");
    log.info("  Handler: {}", handler.getClass().getSimpleName());
    log.info("  Rule set ID: {}", ruleSetId);
    log.info("  Include patterns: {}", Arrays.toString(this.includePatterns));
    log.info("  Exclude patterns: {}", Arrays.toString(this.excludePatterns));
    log.info("  WAIT_FOR_REFILL enabled: {}", waitForRefillEnabled);
    if (waitForRefillEnabled) {
      log.info("  Max wait time: {} ms", maxWaitTimeMs);
      log.info("  Max concurrent waits: {}", maxConcurrentWaits);
    }
    log.info(
        "  RequestContext customizer: {}",
        contextCustomizer != null ? contextCustomizer.getClass().getSimpleName() : "default");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String path = request.getRequestURI();
    String method = request.getMethod();

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

      // Add rate limit headers
      addRateLimitHeaders(response, result);

      if (result.isAllowed()) {
        log.debug(
            "Request allowed: {} {} from {} (remaining: {})",
            method,
            path,
            context.getClientIp(),
            result.getRemainingTokens());
        filterChain.doFilter(request, response);
      } else if (shouldWaitForRefill(result)) {
        // WAIT_FOR_REFILL policy: wait for tokens and retry
        handleWaitForRefill(request, response, filterChain, context, result);
      } else {
        log.info(
            "Request rate limited: {} {} from {} (retry after: {} ms)",
            method,
            path,
            context.getClientIp(),
            result.getRetryAfterMillis());
        handleRateLimitExceeded(response, result);
      }
    } catch (Exception e) {
      log.error("Error during rate limiting, allowing request: {}", e.getMessage(), e);
      // Fail open: allow request if rate limiter fails
      filterChain.doFilter(request, response);
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
    String clientIp = extractClientIp(request);
    String userId = request.getHeader("X-User-Id");
    String apiKey = request.getHeader("X-API-Key");

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

  /**
   * Extracts the client IP address from the request. Supports X-Forwarded-For header for proxied
   * requests.
   */
  private String extractClientIp(HttpServletRequest request) {
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (StringUtils.hasText(forwardedFor)) {
      // X-Forwarded-For can contain multiple IPs, take the first one
      String[] ips = forwardedFor.split(",");
      return ips[0].trim();
    }
    return request.getRemoteAddr();
  }

  /** Adds standard rate limit headers to the response. */
  private void addRateLimitHeaders(HttpServletResponse response, RateLimitResponse result) {
    if (result.getRemainingTokens() >= 0) {
      response.setHeader(HEADER_RATE_LIMIT_REMAINING, String.valueOf(result.getRemainingTokens()));
    }
  }

  /** Handles a rate limit exceeded response. */
  private void handleRateLimitExceeded(HttpServletResponse response, RateLimitResponse result)
      throws IOException {

    long retryAfterSeconds = (result.getRetryAfterMillis() + 999) / 1000;
    response.setHeader(HEADER_RETRY_AFTER, String.valueOf(retryAfterSeconds));

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
}
