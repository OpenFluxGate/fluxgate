package org.fluxgate.sample.redis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * FluxGate Redis Sample Application (Data-plane).
 * <p>
 * This sample demonstrates:
 * - Redis-based rate limiting
 * - HTTP Filter for rate limit enforcement
 * - Config-based rule definition (no MongoDB)
 * <p>
 * Prerequisites:
 * - Redis running at localhost:6379
 * <p>
 * Run with:
 * <pre>
 * mvn spring-boot:run -pl fluxgate-samples/fluxgate-sample-redis
 * </pre>
 * <p>
 * Test rate limiting:
 * <pre>
 * # Send requests rapidly
 * for i in {1..15}; do curl -s http://localhost:8082/api/hello; echo; done
 * </pre>
 */
@SpringBootApplication
public class RedisSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedisSampleApplication.class, args);
    }
}
