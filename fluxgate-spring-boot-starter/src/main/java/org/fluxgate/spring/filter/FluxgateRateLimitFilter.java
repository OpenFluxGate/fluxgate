package org.fluxgate.spring.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

/**
 * HTTP Filter that applies rate limiting to incoming requests.
 * <p>
 * Features:
 * <ul>
 *   <li>Extracts client IP from request (supports X-Forwarded-For)</li>
 *   <li>Loads rule set from provider (MongoDB or custom)</li>
 *   <li>Applies rate limiting via RedisRateLimiter</li>
 *   <li>Sets standard rate limit HTTP headers</li>
 *   <li>Returns 429 Too Many Requests when limit exceeded</li>
 * </ul>
 * <p>
 * This filter requires:
 * <ul>
 *   <li>A {@link RateLimiter} bean (typically RedisRateLimiter)</li>
 *   <li>Optionally a {@link RateLimitRuleSetProvider} for dynamic rules</li>
 * </ul>
 */
public class FluxgateRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FluxgateRateLimitFilter.class);

    // Standard rate limit headers
    private static final String HEADER_RATE_LIMIT_LIMIT = "X-RateLimit-Limit";
    private static final String HEADER_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_RATE_LIMIT_RESET = "X-RateLimit-Reset";
    private static final String HEADER_RETRY_AFTER = "Retry-After";

    private final RateLimiter rateLimiter;
    private final RateLimitRuleSetProvider ruleSetProvider;
    private final FluxgateProperties.RateLimitProperties rateLimitProperties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * Creates a new FluxgateRateLimitFilter.
     *
     * @param rateLimiter        The rate limiter to use (required)
     * @param ruleSetProvider    Provider for loading rule sets (optional, can be null)
     * @param rateLimitProperties Configuration properties
     */
    public FluxgateRateLimitFilter(
            RateLimiter rateLimiter,
            RateLimitRuleSetProvider ruleSetProvider,
            FluxgateProperties.RateLimitProperties rateLimitProperties) {
        this.rateLimiter = rateLimiter;
        this.ruleSetProvider = ruleSetProvider;
        this.rateLimitProperties = rateLimitProperties;

        log.info("FluxgateRateLimitFilter initialized");
        log.info("  Include patterns: {}", Arrays.toString(rateLimitProperties.getIncludePatterns()));
        log.info("  Exclude patterns: {}", Arrays.toString(rateLimitProperties.getExcludePatterns()));
        log.info("  Default rule set: {}", rateLimitProperties.getDefaultRuleSetId());
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

        // Get rule set
        String ruleSetId = rateLimitProperties.getDefaultRuleSetId();
        if (!StringUtils.hasText(ruleSetId)) {
            log.warn("No default rule set ID configured, skipping rate limiting");
            filterChain.doFilter(request, response);
            return;
        }

        Optional<RateLimitRuleSet> ruleSetOpt = loadRuleSet(ruleSetId);
        if (ruleSetOpt.isEmpty()) {
            log.warn("Rule set not found: {}, allowing request", ruleSetId);
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitRuleSet ruleSet = ruleSetOpt.get();

        // Build request context
        RequestContext context = buildRequestContext(request);

        // Apply rate limiting
        try {
            RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

            // Add rate limit headers
            if (rateLimitProperties.isIncludeHeaders()) {
                addRateLimitHeaders(response, result, ruleSet);
            }

            if (result.isAllowed()) {
                log.debug("Request allowed: {} {} from {} (remaining: {})",
                        method, path, context.getClientIp(), result.getRemainingTokens());
                filterChain.doFilter(request, response);
            } else {
                log.info("Request rate limited: {} {} from {} (wait: {} ms)",
                        method, path, context.getClientIp(),
                        result.getNanosToWaitForRefill() / 1_000_000);
                handleRateLimitExceeded(response, result);
            }
        } catch (Exception e) {
            log.error("Error during rate limiting, allowing request: {}", e.getMessage(), e);
            // Fail open: allow request if rate limiter fails
            filterChain.doFilter(request, response);
        }
    }

    /**
     * Loads a rule set from the provider.
     */
    private Optional<RateLimitRuleSet> loadRuleSet(String ruleSetId) {
        if (ruleSetProvider == null) {
            log.warn("No RuleSetProvider configured");
            return Optional.empty();
        }

        try {
            return ruleSetProvider.findById(ruleSetId);
        } catch (Exception e) {
            log.error("Error loading rule set {}: {}", ruleSetId, e.getMessage(), e);
            return Optional.empty();
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
        if (rateLimitProperties.isTrustClientIpHeader()) {
            String headerName = rateLimitProperties.getClientIpHeader();
            String forwardedFor = request.getHeader(headerName);

            if (StringUtils.hasText(forwardedFor)) {
                // X-Forwarded-For can contain multiple IPs, take the first one
                String[] ips = forwardedFor.split(",");
                return ips[0].trim();
            }
        }

        return request.getRemoteAddr();
    }

    /**
     * Adds standard rate limit headers to the response.
     */
    private void addRateLimitHeaders(
            HttpServletResponse response,
            RateLimitResult result,
            RateLimitRuleSet ruleSet) {

        // Get capacity from the first band of the matched rule
        long limit = 0;
        if (result.getMatchedRule() != null && !result.getMatchedRule().getBands().isEmpty()) {
            limit = result.getMatchedRule().getBands().get(0).getCapacity();
        }

        response.setHeader(HEADER_RATE_LIMIT_LIMIT, String.valueOf(limit));
        response.setHeader(HEADER_RATE_LIMIT_REMAINING, String.valueOf(result.getRemainingTokens()));

        // Calculate reset time
        long resetEpochSeconds = System.currentTimeMillis() / 1000
                + (result.getNanosToWaitForRefill() / 1_000_000_000);
        response.setHeader(HEADER_RATE_LIMIT_RESET, String.valueOf(resetEpochSeconds));
    }

    /**
     * Handles a rate limit exceeded response.
     */
    private void handleRateLimitExceeded(
            HttpServletResponse response,
            RateLimitResult result) throws IOException {

        // Calculate retry after (seconds, rounded up)
        long retryAfterSeconds = (result.getNanosToWaitForRefill() + 999_999_999) / 1_000_000_000;
        response.setHeader(HEADER_RETRY_AFTER, String.valueOf(retryAfterSeconds));

        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE); // 429
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
        String[] excludePatterns = rateLimitProperties.getExcludePatterns();
        if (excludePatterns == null || excludePatterns.length == 0) {
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
        String[] includePatterns = rateLimitProperties.getIncludePatterns();
        if (includePatterns == null || includePatterns.length == 0) {
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
