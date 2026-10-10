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
   * RequestContext customizer that derives the composite {@code ipUser} rate limit key.
   *
   * <p>The identity is <b>not</b> read from client headers here. {@code RequestContextFactory}
   * already fills the builder from the sources configured under {@code
   * fluxgate.ratelimit.identity.source} (default {@code PRINCIPAL}: the authenticated principal;
   * identity headers are ignored) and resolves the client IP with {@code trust-client-ip-header} /
   * {@code trusted-proxies}. A client-supplied {@code X-User-Id}, {@code X-API-Key} or {@code
   * X-Real-IP} therefore cannot pick its own bucket.
   *
   * <p>This sample has no Spring Security, so by default every caller shares the IP-only key. To
   * try the per-user composite key locally, opt in explicitly:
   *
   * <pre>
   * FLUXGATE_IDENTITY_SOURCE=HEADERS ./mvnw spring-boot:run   # DEMO ONLY
   * curl -H "X-User-Id: user-123" http://localhost:8085/api/test/composite
   * </pre>
   *
   * <p>{@code HEADERS} is only safe behind a gateway that authenticates the caller and strips any
   * incoming {@code X-User-Id}; pair it with {@code fluxgate.ratelimit.trust-client-ip-header} and
   * {@code fluxgate.ratelimit.trusted-proxies} (see application.yml). The starter logs a warning at
   * startup when it is enabled.
   */
  @Bean
  public RequestContextCustomizer requestContextCustomizer() {
    return (builder, request) -> {
      // userId/clientIp are already resolved by the starter according to identity.source
      String clientIp = builder.build().getClientIp();
      String userId = builder.build().getUserId();

      // Composite key (IP:userId) for CUSTOM scope with keyStrategyId="ipUser"
      if (userId != null && !userId.isEmpty()) {
        builder.attribute("ipUser", clientIp + ":" + userId);
      } else {
        builder.attribute("ipUser", clientIp);
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
   * curl -H "X-User-Id: user-A" http://localhost:8085/api/test/composite   # needs identity.source=HEADERS (demo only)
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
