package org.fluxgate.sample.standalone.java11.config;

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
 */
@Configuration
public class MultipleFiltersConfig {

  private static final Logger log = LoggerFactory.getLogger(MultipleFiltersConfig.class);

  /**
   * RequestContext customizer that extracts rate limit key values from headers.
   *
   * <ul>
   *   <li><b>X-User-Id</b> - builder.userId() (for PER_USER scope)
   *   <li><b>X-API-Key</b> - builder.apiKey() (for PER_API_KEY scope)
   *   <li><b>X-Real-IP</b> - builder.clientIp() (overrides IP for proxy setups)
   *   <li><b>X-Tenant-Id</b> - builder.attribute("tenantId", ...) (for CUSTOM scope)
   * </ul>
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
      String clientIp = builder.build().getClientIp();
      if (userId != null && !userId.isEmpty()) {
        builder.attribute("ipUser", clientIp + ":" + userId);
        log.debug("Built composite ipUser key: {}:{}", clientIp, userId);
      } else {
        builder.attribute("ipUser", clientIp);
        log.debug("Built ipUser key (IP only): {}", clientIp);
      }

      return builder;
    };
  }

  /** Filter 1: Composite key API rate limiter (highest priority). */
  @Bean
  public FilterRegistrationBean<FluxgateRateLimitFilter> compositeKeyApiFilter(
      FluxgateRateLimitHandler handler, RequestContextCustomizer customizer) {
    log.info("Registering compositeKeyApiFilter with order=1 for /api/test/composite/**");

    FluxgateRateLimitFilter filter =
        new FluxgateRateLimitFilter(
            handler,
            "composite-key-rules",
            new String[] {"/api/test/composite/**"},
            new String[] {},
            false,
            5000,
            100,
            customizer);

    FilterRegistrationBean<FluxgateRateLimitFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setOrder(1);
    registration.setName("compositeKeyApiRateLimitFilter");
    registration.addUrlPatterns("/api/test/composite/*");
    return registration;
  }

  /** Filter 2: Multi-filter API rate limiter. */
  @Bean
  public FilterRegistrationBean<FluxgateRateLimitFilter> multiFilterApiFilter(
      FluxgateRateLimitHandler handler, RequestContextCustomizer customizer) {
    log.info("Registering multiFilterApiFilter with order=2 for /api/test/multi-filter/**");

    FluxgateRateLimitFilter filter =
        new FluxgateRateLimitFilter(
            handler,
            "multi-filter-rules",
            new String[] {"/api/test/multi-filter/**"},
            new String[] {},
            false,
            5000,
            100,
            customizer);

    FilterRegistrationBean<FluxgateRateLimitFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setOrder(2);
    registration.setName("multiFilterApiRateLimitFilter");
    registration.addUrlPatterns("/api/test/multi-filter/*");
    return registration;
  }

  /** Filter 3: Standard API rate limiter. */
  @Bean
  public FilterRegistrationBean<FluxgateRateLimitFilter> apiFilter(
      FluxgateRateLimitHandler handler, RequestContextCustomizer customizer) {
    log.info(
        "Registering apiFilter with order=3 for /api/test/** (excluding composite, multi-filter)");

    FluxgateRateLimitFilter filter =
        new FluxgateRateLimitFilter(
            handler,
            "standalone-rules",
            new String[] {"/api/test/**"},
            new String[] {"/api/test/composite/**", "/api/test/multi-filter/**"},
            false,
            5000,
            100,
            customizer);

    FilterRegistrationBean<FluxgateRateLimitFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setOrder(3);
    registration.setName("apiRateLimitFilter");
    registration.addUrlPatterns("/api/test/*");
    return registration;
  }
}
