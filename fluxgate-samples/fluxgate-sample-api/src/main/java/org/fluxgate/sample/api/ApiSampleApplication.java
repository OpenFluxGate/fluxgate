package org.fluxgate.sample.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * FluxGate API Gateway Sample Application: an HTTP demo that ties two other samples together.
 *
 * <p>A gateway that ties the two other samples together over HTTP: rules are managed in the
 * Control-plane ({@code fluxgate-sample-mongo}, MongoDB) and enforced by the Data-plane ({@code
 * fluxgate-sample-redis}, Redis). This application itself uses neither database.
 *
 * <p>Prerequisites: {@code fluxgate-sample-mongo} on localhost:8081 and {@code
 * fluxgate-sample-redis} on localhost:8082 ({@code fluxgate.services.*-url}).
 *
 * <p>Run with:
 *
 * <pre>
 * mvn spring-boot:run -pl fluxgate-samples/fluxgate-sample-api
 * </pre>
 *
 * <p>Usage:
 *
 * <pre>
 * # 1. Create sample rules in MongoDB (Control-plane, fluxgate-sample-mongo on 8081)
 * curl -X POST http://localhost:8080/admin/rules/init
 *
 * # 2. Register the rule set in Redis (Data-plane, fluxgate-sample-redis on 8082)
 * curl -X POST "http://localhost:8080/admin/sync?ruleSetId=api-gateway-rules"
 *
 * # 3. Test rate limiting: one request more than the synced capacity (100 per 60 s) gets 429
 * for i in {1..101}; do curl -s "http://localhost:8080/api/test?ruleSetId=api-gateway-rules"; echo; done
 * </pre>
 */
@SpringBootApplication
public class ApiSampleApplication {

  public static void main(String[] args) {
    SpringApplication.run(ApiSampleApplication.class, args);
  }
}
