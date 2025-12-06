package org.fluxgate.sample.standalone;

import org.fluxgate.sample.standalone.handler.StandaloneRateLimitHandler;
import org.fluxgate.spring.annotation.EnableFluxgateFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone FluxGate sample application.
 * <p>
 * This application demonstrates:
 * - Direct MongoDB connection for rule storage
 * - Direct Redis connection for rate limiting
 * - @EnableFluxgateFilter for HTTP filter integration
 * <p>
 * APIs:
 * - POST /api/admin/ruleset - Create a rate limit ruleset (saved to MongoDB)
 * - POST /api/admin/sync - Sync rules to Redis rate limiter
 * - GET /api/test - Rate-limited test endpoint (10 requests/minute)
 */
@SpringBootApplication
@EnableFluxgateFilter(
        handler = StandaloneRateLimitHandler.class,
        ruleSetId = "standalone-rules",
        includePatterns = {"/api/**"},
        excludePatterns = {"/api/admin/**", "/swagger-ui/**", "/v3/api-docs/**"}
)
public class StandaloneApplication {

    public static void main(String[] args) {
        SpringApplication.run(StandaloneApplication.class, args);
    }
}
