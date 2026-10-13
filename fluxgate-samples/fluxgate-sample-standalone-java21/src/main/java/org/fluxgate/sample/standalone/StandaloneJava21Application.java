package org.fluxgate.sample.standalone;

import org.fluxgate.spring.annotation.EnableFluxgateAspect;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone FluxGate sample application.
 *
 * <p>This application demonstrates:
 *
 * <ul>
 *   <li>MongoDB rule storage through the starter's {@code fluxgate.mongo.*} auto-configuration
 *   <li>Direct Redis connection for rate limiting ({@code FluxgateConfig})
 *   <li>Multiple rate limit filters with different rule sets and orders ({@code
 *       MultipleFiltersConfig})
 *   <li>RequestContext customization for a composite IP+User key attribute
 *   <li>AOP-based rate limiting with @RateLimit
 * </ul>
 *
 * <p>Filter Configuration (see {@code MultipleFiltersConfig}):
 *
 * <ul>
 *   <li>Filter 1 (order=1): /api/test/composite/** - "composite-key-rules"
 *   <li>Filter 2 (order=2): /api/test/multi-filter/** - "multi-filter-rules"
 *   <li>Filter 3 (order=3): /api/test/** without the two above - "standalone-rules"
 * </ul>
 *
 * <p>APIs:
 *
 * <ul>
 *   <li>POST /api/admin/rules/{standalone,multi-filter,composite} - Create rules (saved to MongoDB)
 *   <li>GET /api/admin/rules/{ruleSetId} - Show the rules of a rule set
 *   <li>GET /api/test, /api/test/multi-filter, /api/test/composite?userId= - Filter-limited
 *   <li>GET /api/test/aop/aop-test1, /api/test/aop/aop-test2 - AOP-based rate limiting
 * </ul>
 */
@SpringBootApplication
@EnableFluxgateAspect
public class StandaloneJava21Application {

  public static void main(String[] args) {
    SpringApplication.run(StandaloneJava21Application.class, args);
  }
}
