package org.fluxgate.sample.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * FluxGate Full API Gateway Sample Application.
 * <p>
 * This sample demonstrates the complete FluxGate integration:
 * - MongoDB for rule storage and management
 * - Redis for distributed rate limiting
 * - HTTP Filter for request interception
 * <p>
 * Prerequisites:
 * - MongoDB running at localhost:27017
 * - Redis running at localhost:6379
 * <p>
 * Run with:
 * <pre>
 * mvn spring-boot:run -pl fluxgate-samples/fluxgate-sample-api
 * </pre>
 * <p>
 * Usage:
 * <pre>
 * # 1. Create sample rules
 * curl -X POST http://localhost:8080/admin/rules/init
 *
 * # 2. Test rate limiting
 * for i in {1..15}; do curl -s http://localhost:8080/api/hello; echo; done
 *
 * # 3. Check rate limit headers
 * curl -i http://localhost:8080/api/hello
 * </pre>
 */
@SpringBootApplication
public class ApiSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiSampleApplication.class, args);
    }
}
