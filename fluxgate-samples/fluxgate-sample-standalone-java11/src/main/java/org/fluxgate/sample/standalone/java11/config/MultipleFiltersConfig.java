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
   * curl -H "X-User-Id: user-123" "http://localhost:8085/api/test/composite?userId=user-123"
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
