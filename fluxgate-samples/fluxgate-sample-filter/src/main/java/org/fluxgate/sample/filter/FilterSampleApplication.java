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
 *   <li><b>EngineBackedRateLimitHandler</b> - the starter's default handler, registered
 *       automatically for direct Redis access (see README)
 * </ul>
 *
 * <p>Prerequisites:
 *
 * <ul>
 *   <li>A rate limit check API at {@code fluxgate.api.url}: application.yml points it at {@code
 *       fluxgate-sample-redis} on http://localhost:8082
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
 *   <li>GET /api/hello, /api/users, /api/stats - Rate limited endpoints
 *   <li>GET /api/users/{id} - NOT rate limited ({@code /api/*} matches one segment only)
 *   <li>GET /health, /ready - NOT rate limited
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
