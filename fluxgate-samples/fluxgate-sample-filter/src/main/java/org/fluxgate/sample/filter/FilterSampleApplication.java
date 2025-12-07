package org.fluxgate.sample.filter;

import org.fluxgate.sample.filter.handler.HttpRateLimitHandler;
import org.fluxgate.spring.annotation.EnableFluxgateFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * FluxGate Filter Sample Application.
 *
 * <p>This sample demonstrates <b>automatic rate limiting</b> using {@code FluxgateRateLimitFilter}
 * with HTTP API-based rate limiting.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Automatic rate limiting via HTTP filter (enabled by {@code @EnableFluxgateFilter})
 *   <li>No rate limiting code in controllers
 *   <li>HTTP API-based rate limiting (calls external FluxGate API server)
 *   <li>Configurable URL patterns
 * </ul>
 *
 * <p>Handler modes:
 *
 * <ul>
 *   <li><b>HttpRateLimitHandler</b> (current) - Calls external FluxGate API server
 *   <li><b>RedisRateLimitHandler</b> (commented) - Direct Redis access
 * </ul>
 *
 * <p>Prerequisites:
 *
 * <ul>
 *   <li>FluxGate API server running at configured URL (default: http://localhost:8080)
 * </ul>
 *
 * <p>Run with:
 *
 * <pre>
 * mvn spring-boot:run -pl fluxgate-samples/fluxgate-sample-filter
 * </pre>
 *
 * <p>Test endpoints:
 *
 * <ul>
 *   <li>GET /api/hello - Rate limited endpoint
 *   <li>GET /api/users - Rate limited endpoint
 *   <li>GET /health - NOT rate limited (excluded pattern)
 * </ul>
 */
@SpringBootApplication
@EnableFluxgateFilter(
    handler = HttpRateLimitHandler.class,
    ruleSetId = "api-limits",
    includePatterns = {"/api/*"},
    excludePatterns = {"/health", "/actuator/*", "/swagger-ui/*", "/v3/api-docs/*"})
public class FilterSampleApplication {

  public static void main(String[] args) {
    SpringApplication.run(FilterSampleApplication.class, args);
  }
}
