package org.fluxgate.sample.standalone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone FluxGate sample application.
 *
 * <p>This application demonstrates:
 *
 * <ul>
 *   <li>Direct MongoDB connection for rule storage
 *   <li>Direct Redis connection for rate limiting
 *   <li>Multiple rate limit filters with different priorities (Java Config)
 *   <li>RequestContext customization for IP override and attribute injection
 *   <li>WAIT_FOR_REFILL policy for public APIs
 * </ul>
 *
 * <p>Filter Configuration (see {@code MultipleFiltersConfig}):
 *
 * <ul>
 *   <li>Filter 1 (order=1): /api/public/** - Public API with WAIT_FOR_REFILL
 *   <li>Filter 2 (order=2): /api/** - Standard API with REJECT_REQUEST
 *   <li>Filter 3 (order=3): /api/admin/** - Admin API with strict limits
 * </ul>
 *
 * <p>APIs:
 *
 * <ul>
 *   <li>POST /api/admin/ruleset - Create rate limit rules (saved to MongoDB)
 *   <li>POST /api/admin/sync - Sync rules to Redis rate limiter
 *   <li>GET /api/test - Rate-limited test endpoint (10 requests/minute)
 *   <li>GET /api/public/test - Public API with WAIT_FOR_REFILL policy
 * </ul>
 */
@SpringBootApplication
public class StandaloneApplication {

  public static void main(String[] args) {
    SpringApplication.run(StandaloneApplication.class, args);
  }
}
