package org.fluxgate.sample.standalone.config;

import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.spring.filter.FluxgateRateLimitFilter;
import org.fluxgate.spring.filter.RequestContextCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for multiple rate limit filters with different priorities.
 *
 * <p>This demonstrates how to configure multiple {@link FluxgateRateLimitFilter} instances with:
 *
 * <ul>
 *   <li>Different rule sets for different URL patterns
 *   <li>Different priorities (order) - lower number = higher priority
 *   <li>RequestContext customization
 * </ul>
 *
 * <p>Filter execution order (in Servlet Filter Chain):
 *
 * <pre>
 * 1. compositeKeyApiFilter (order=1)  - /api/test/composite/**    - Uses "composite-key-rules" (IP+User key)
 * 2. multiFilterApiFilter (order=2)   - /api/test/multi-filter/** - Uses "multi-filter-rules" (2 rules)
 * 3. apiFilter (order=3)              - /api/test/**              - Uses "standalone-rules" (1 rule)
 * </pre>
 *
 * <p>Test endpoints:
 *
 * <ul>
 *   <li>GET /api/test - Standard rate limit test (10 req/min per IP)
 *   <li>GET /api/test/multi-filter - Multi-filter test (10 req/min + 20 req/min)
 *   <li>GET /api/test/composite - Composite key test (10 req/min per IP+User)
 * </ul>
 */
@Configuration
public class MultipleFiltersConfig {

  private static final Logger log = LoggerFactory.getLogger(MultipleFiltersConfig.class);

  /**
   * RequestContext customizer that extracts rate limit key values from headers.
   *
   * <p>This customizer sets the following values for use by {@link
   * org.fluxgate.core.key.LimitScopeKeyResolver}:
   *
   * <ul>
   *   <li><b>X-User-Id</b> → builder.userId() (for PER_USER scope)
   *   <li><b>X-API-Key</b> → builder.apiKey() (for PER_API_KEY scope)
   *   <li><b>X-Real-IP</b> → builder.clientIp() (overrides IP for proxy setups)
   *   <li><b>X-Tenant-Id</b> → builder.attribute("tenantId", ...) (for CUSTOM scope)
   *   <li><b>X-Request-Id</b> → builder.attribute("requestId", ...) (for tracing)
   * </ul>
   *
   * <p><b>Example usage:</b>
   *
   * <pre>
   * # Rate limit by user ID (requires rule with LimitScope.PER_USER)
   * curl -H "X-User-Id: user-123" http://localhost:8085/api/test
   *
   * # Rate limit by API key (requires rule with LimitScope.PER_API_KEY)
   * curl -H "X-API-Key: api-key-abc" http://localhost:8085/api/test
   *
   * # Rate limit by IP (requires rule with LimitScope.PER_IP - default)
   * curl http://localhost:8085/api/test
   * </pre>
   *
   * <p><b>Note:</b> The rate limit key is determined by the rule's LimitScope, not by which headers
   * are present. If a rule has LimitScope.PER_USER but X-User-Id header is missing, it will fall
   * back to clientIp.
   */
  @Bean
  public RequestContextCustomizer requestContextCustomizer() {
    return (builder, request) -> {
      // Set userId for PER_USER scope rules
      String userId = request.getHeader("X-User-Id");
      if (userId != null && !userId.isEmpty()) {
        log.debug("Setting userId from X-User-Id header: {}", userId);
        builder.userId(userId);
      }

      // Set apiKey for PER_API_KEY scope rules
      String apiKey = request.getHeader("X-API-Key");
      if (apiKey != null && !apiKey.isEmpty()) {
        log.debug("Setting apiKey from X-API-Key header: {}", apiKey);
        builder.apiKey(apiKey);
      }

      // Override client IP from X-Real-IP header (common for nginx proxy)
      String realIp = request.getHeader("X-Real-IP");
      if (realIp != null && !realIp.isEmpty()) {
        log.debug("Overriding client IP from X-Real-IP: {}", realIp);
        builder.clientIp(realIp);
      }

      // Add tenant ID for CUSTOM scope with keyStrategyId="tenantId"
      String tenantId = request.getHeader("X-Tenant-Id");
      if (tenantId != null) {
        builder.attribute("tenantId", tenantId);
      }

      // Add request ID for tracing (not used for rate limiting)
      String requestId = request.getHeader("X-Request-Id");
      if (requestId != null) {
        builder.attribute("requestId", requestId);
      }

      // Build composite key (IP:userId) for CUSTOM scope with keyStrategyId="ipUser"
      // This demonstrates how to combine multiple identifiers into a single rate limit key
      String clientIp = builder.build().getClientIp();
      if (userId != null && !userId.isEmpty()) {
        // Composite key: "192.168.1.100:user-123"
        builder.attribute("ipUser", clientIp + ":" + userId);
        log.debug("Built composite ipUser key: {}:{}", clientIp, userId);
      } else {
        // Fallback to IP only if no userId provided
        builder.attribute("ipUser", clientIp);
        log.debug("Built ipUser key (IP only): {}", clientIp);
      }

      return builder;
    };
  }

  /**
   * Filter 1: Composite key API rate limiter (highest priority).
   *
   * <p>Configuration:
   *
   * <ul>
   *   <li>Order: 1 (highest priority - checked first)
   *   <li>RuleSet: "composite-key-rules" (1 rule with CUSTOM scope, keyStrategyId="ipUser")
   *   <li>URL Pattern: /api/test/composite/**
   *   <li>Key Strategy: IP+User composite key (e.g., "192.168.1.100:user-123")
   * </ul>
   *
   * <p>Usage:
   *
   * <pre>
   * # Different users from same IP have separate rate limits
   * curl -H "X-User-Id: user-A" http://localhost:8085/api/test/composite
   * curl -H "X-User-Id: user-B" http://localhost:8085/api/test/composite
   * </pre>
   */
  @Bean
  public FilterRegistrationBean<FluxgateRateLimitFilter> compositeKeyApiFilter(
      FluxgateRateLimitHandler handler, RequestContextCustomizer customizer) {
    log.info("Registering compositeKeyApiFilter with order=1 for /api/test/composite/**");

    FluxgateRateLimitFilter filter =
        new FluxgateRateLimitFilter(
            handler,
            "composite-key-rules", // ruleSetId with CUSTOM scope
            new String[] {"/api/test/composite/**"}, // includePatterns
            new String[] {}, // excludePatterns
            false, // waitForRefillEnabled
            5000, // maxWaitTimeMs
            100, // maxConcurrentWaits
            customizer);

    FilterRegistrationBean<FluxgateRateLimitFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setOrder(1); // Highest priority - checked first
    registration.setName("compositeKeyApiRateLimitFilter");
    registration.addUrlPatterns("/api/test/composite/*");
    return registration;
  }

  /**
   * Filter 2: Multi-filter API rate limiter.
   *
   * <p>Configuration:
   *
   * <ul>
   *   <li>Order: 2
   *   <li>RuleSet: "multi-filter-rules" (2 rules: 10 req/min + 20 req/min)
   *   <li>URL Pattern: /api/test/multi-filter/**
   *   <li>Policy: REJECT_REQUEST
   * </ul>
   */
  @Bean
  public FilterRegistrationBean<FluxgateRateLimitFilter> multiFilterApiFilter(
      FluxgateRateLimitHandler handler, RequestContextCustomizer customizer) {
    log.info("Registering multiFilterApiFilter with order=2 for /api/test/multi-filter/**");

    FluxgateRateLimitFilter filter =
        new FluxgateRateLimitFilter(
            handler,
            "multi-filter-rules", // ruleSetId - different from standalone-rules
            new String[] {"/api/test/multi-filter/**"}, // includePatterns
            new String[] {}, // excludePatterns
            false, // waitForRefillEnabled
            5000, // maxWaitTimeMs
            100, // maxConcurrentWaits
            customizer);

    FilterRegistrationBean<FluxgateRateLimitFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setOrder(2); // Second priority
    registration.setName("multiFilterApiRateLimitFilter");
    registration.addUrlPatterns("/api/test/multi-filter/*");
    return registration;
  }

  /**
   * Filter 3: Standard API rate limiter.
   *
   * <p>Configuration:
   *
   * <ul>
   *   <li>Order: 3 (lowest priority)
   *   <li>RuleSet: "standalone-rules" (1 rule: 10 req/min)
   *   <li>URL Pattern: /api/test/** (excluding composite and multi-filter)
   *   <li>Policy: REJECT_REQUEST (immediate 429 response)
   * </ul>
   *
   * <p><b>NOTICE:</b> The excludePatterns MUST include other specific endpoints to prevent double
   * rate limiting. Without these exclusions, requests would be checked by BOTH filters.
   */
  @Bean
  public FilterRegistrationBean<FluxgateRateLimitFilter> apiFilter(
      FluxgateRateLimitHandler handler, RequestContextCustomizer customizer) {
    log.info(
        "Registering apiFilter with order=3 for /api/test/** (excluding composite, multi-filter)");

    FluxgateRateLimitFilter filter =
        new FluxgateRateLimitFilter(
            handler,
            "standalone-rules", // ruleSetId
            new String[] {"/api/test/**"}, // includePatterns
            new String[] {
              "/api/test/composite/**", "/api/test/multi-filter/**"
            }, // excludePatterns - Exclude specific endpoints
            false, // waitForRefillEnabled
            5000, // maxWaitTimeMs
            100, // maxConcurrentWaits
            customizer);

    FilterRegistrationBean<FluxgateRateLimitFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setOrder(3); // Lowest priority
    registration.setName("apiRateLimitFilter");
    registration.addUrlPatterns("/api/test/*");
    return registration;
  }
}
