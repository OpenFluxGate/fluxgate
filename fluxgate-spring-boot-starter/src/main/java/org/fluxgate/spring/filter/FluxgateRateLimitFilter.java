package org.fluxgate.spring.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

/**
 * HTTP Filter that applies rate limiting to incoming requests.
 * <p>
 * This filter delegates rate limiting logic to a {@link FluxgateRateLimitHandler},
 * which allows flexible implementation strategies:
 * <ul>
 *   <li>API-based: Call external FluxGate API server</li>
 *   <li>Redis direct: Use Redis rate limiter directly</li>
 *   <li>Standalone: In-memory rate limiting (for testing)</li>
 * </ul>
 * <p>
 * Features:
 * <ul>
 *   <li>Extracts client IP from request (supports X-Forwarded-For)</li>
 *   <li>Configurable include/exclude URL patterns</li>
 *   <li>Sets standard rate limit HTTP headers</li>
 *   <li>Returns 429 Too Many Requests when limit exceeded</li>
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

    /**
     * Creates a new FluxgateRateLimitFilter.
     *
     * @param handler         The rate limit handler (required)
     * @param ruleSetId       Default rule set ID to use
     * @param includePatterns URL patterns to include
     * @param excludePatterns URL patterns to exclude
     */
    public FluxgateRateLimitFilter(
            FluxgateRateLimitHandler handler,
            String ruleSetId,
            String[] includePatterns,
            String[] excludePatterns) {
        this.handler = handler;
        this.ruleSetId = ruleSetId;
        this.includePatterns = includePatterns != null ? includePatterns : new String[0];
        this.excludePatterns = excludePatterns != null ? excludePatterns : new String[0];

        log.info("FluxgateRateLimitFilter initialized");
        log.info("  Handler: {}", handler.getClass().getSimpleName());
        log.info("  Rule set ID: {}", ruleSetId);
        log.info("  Include patterns: {}", Arrays.toString(this.includePatterns));
        log.info("  Exclude patterns: {}", Arrays.toString(this.excludePatterns));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

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
                log.debug("Request allowed: {} {} from {} (remaining: {})",
                        method, path, context.getClientIp(), result.getRemainingTokens());
                filterChain.doFilter(request, response);
            } else {
                log.info("Request rate limited: {} {} from {} (retry after: {} ms)",
                        method, path, context.getClientIp(), result.getRetryAfterMillis());
                handleRateLimitExceeded(response, result);
            }
        } catch (Exception e) {
            log.error("Error during rate limiting, allowing request: {}", e.getMessage(), e);
            // Fail open: allow request if rate limiter fails
            filterChain.doFilter(request, response);
        }
    }

    /**
     * Builds a RequestContext from the HTTP request.
     */
    private RequestContext buildRequestContext(HttpServletRequest request) {
        String clientIp = extractClientIp(request);
        String userId = request.getHeader("X-User-Id");
        String apiKey = request.getHeader("X-API-Key");

        return RequestContext.builder()
                .clientIp(clientIp)
                .userId(userId)
                .apiKey(apiKey)
                .endpoint(request.getRequestURI())
                .method(request.getMethod())
                .build();
    }

    /**
     * Extracts the client IP address from the request.
     * Supports X-Forwarded-For header for proxied requests.
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

    /**
     * Adds standard rate limit headers to the response.
     */
    private void addRateLimitHeaders(HttpServletResponse response, RateLimitResponse result) {
        if (result.getRemainingTokens() >= 0) {
            response.setHeader(HEADER_RATE_LIMIT_REMAINING, String.valueOf(result.getRemainingTokens()));
        }
    }

    /**
     * Handles a rate limit exceeded response.
     */
    private void handleRateLimitExceeded(
            HttpServletResponse response,
            RateLimitResponse result) throws IOException {

        long retryAfterSeconds = (result.getRetryAfterMillis() + 999) / 1000;
        response.setHeader(HEADER_RETRY_AFTER, String.valueOf(retryAfterSeconds));

        response.setStatus(429); // Too Many Requests
        response.setContentType("application/json");
        response.getWriter().write(String.format(
                "{\"error\":\"Rate limit exceeded\",\"retryAfter\":%d}",
                retryAfterSeconds));
    }

    /**
     * Checks if a path should be excluded from rate limiting.
     */
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

    /**
     * Checks if a path should be included in rate limiting.
     */
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
