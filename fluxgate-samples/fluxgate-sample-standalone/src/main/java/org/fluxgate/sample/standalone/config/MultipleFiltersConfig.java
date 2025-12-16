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
 * 1. multiFilterApiFilter (order=1) - /api/test/multi-filter/** - Uses "multi-filter-rules" (2 rules: 10 req/min + 20 req/min)
 * 2. apiFilter (order=2)            - /api/test/**              - Uses "standalone-rules" (1 rule: 10 req/min)
 * </pre>
 *
 * <p>Test endpoints:
 *
 * <ul>
 *   <li>GET /api/test - Standard rate limit test (10 req/min, 1 rule)
 *   <li>GET /api/test/multi-filter - Multi-filter test (10 req/min + 20 req/min, 2 rules)
 * </ul>
 */
@Configuration
public class MultipleFiltersConfig {

  private static final Logger log = LoggerFactory.getLogger(MultipleFiltersConfig.class);

  /**
   * RequestContext customizer that adds custom attributes and handles special headers.
   *
   * <p>This customizer:
   *
   * <ul>
   *   <li>Overrides client IP from X-Real-IP header (for proxy setups)
   *   <li>Adds tenant ID from X-Tenant-Id header
   *   <li>Removes sensitive headers before logging
   * </ul>
   */
  @Bean
  public RequestContextCustomizer requestContextCustomizer() {
    return (builder, request) -> {
      // Override client IP from X-Real-IP header (common for nginx proxy)
      String realIp = request.getHeader("X-Real-IP");
      if (realIp != null && !realIp.isEmpty()) {
        log.debug("Overriding client IP from X-Real-IP: {}", realIp);
        builder.clientIp(realIp);
      }

      // Add tenant ID for multi-tenant rate limiting
      String tenantId = request.getHeader("X-Tenant-Id");
      if (tenantId != null) {
        builder.attribute("tenantId", tenantId);
      }

      // Add request ID for tracing
      String requestId = request.getHeader("X-Request-Id");
      if (requestId != null) {
        builder.attribute("requestId", requestId);
      }

      // Remove sensitive headers before they are logged to MongoDB
      builder.getHeaders().remove("Authorization");
      builder.getHeaders().remove("Cookie");
      builder.getHeaders().remove("X-API-Key");

      return builder;
    };
  }

  /**
   * Filter 1: Multi-filter API rate limiter (highest priority).
   *
   * <p>Configuration:
   *
   * <ul>
   *   <li>Order: 1 (highest priority - checked first)
   *   <li>RuleSet: "multi-filter-rules" (2 rules: 10 req/min + 20 req/min)
   *   <li>URL Pattern: /api/test/multi-filter/**
   *   <li>Policy: REJECT_REQUEST
   * </ul>
   */
  @Bean
  public FilterRegistrationBean<FluxgateRateLimitFilter> multiFilterApiFilter(
      FluxgateRateLimitHandler handler, RequestContextCustomizer customizer) {
    log.info("Registering multiFilterApiFilter with order=1 for /api/test/multi-filter/**");

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
    registration.setOrder(1); // Highest priority - checked first
    registration.setName("multiFilterApiRateLimitFilter");
    registration.addUrlPatterns("/api/test/multi-filter/*");
    return registration;
  }

  /**
   * Filter 2: Standard API rate limiter.
   *
   * <p>Configuration:
   *
   * <ul>
   *   <li>Order: 2 (lower priority than multiFilterApiFilter)
   *   <li>RuleSet: "standalone-rules" (1 rule: 10 req/min)
   *   <li>URL Pattern: /api/test/** (excluding multi-filter)
   *   <li>Policy: REJECT_REQUEST (immediate 429 response)
   * </ul>
   *
   * <p><b>NOTICE:</b> The excludePatterns MUST include "/api/test/multi-filter/**" to prevent
   * double rate limiting. Without this exclusion, requests to /api/test/multi-filter would be
   * checked by BOTH filters:
   *
   * <ol>
   *   <li>multiFilterApiFilter (order=1) → applies "multi-filter-rules" (2 rules)
   *   <li>apiFilter (order=2) → applies "standalone-rules" (1 rule) - UNINTENDED!
   * </ol>
   *
   * <p>This would result in 3 rules being applied instead of the intended 2 rules.
   */
  @Bean
  public FilterRegistrationBean<FluxgateRateLimitFilter> apiFilter(
      FluxgateRateLimitHandler handler, RequestContextCustomizer customizer) {
    log.info("Registering apiFilter with order=2 for /api/test/** (excluding multi-filter)");

    FluxgateRateLimitFilter filter =
        new FluxgateRateLimitFilter(
            handler,
            "standalone-rules", // ruleSetId
            new String[] {"/api/test/**"}, // includePatterns
            new String[] {
              "/api/test/multi-filter/**"
            }, // excludePatterns - IMPORTANT! See NOTICE above
            false, // waitForRefillEnabled
            5000, // maxWaitTimeMs
            100, // maxConcurrentWaits
            customizer);

    FilterRegistrationBean<FluxgateRateLimitFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setOrder(2); // Filter execution order in Servlet Filter Chain
    registration.setName("apiRateLimitFilter");
    registration.addUrlPatterns("/api/test/*");
    return registration;
  }
}
