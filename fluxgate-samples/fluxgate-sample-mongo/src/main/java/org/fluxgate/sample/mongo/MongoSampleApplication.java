package org.fluxgate.sample.mongo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * FluxGate MongoDB Sample Application (Control-plane).
 *
 * <p>This sample demonstrates: - MongoDB integration for rate limit rule management - REST API for
 * CRUD operations on rules - No rate limiting filter (control-plane only)
 *
 * <p>Prerequisites: - MongoDB running at localhost:27017
 *
 * <p>Run with:
 *
 * <pre>
 * mvn spring-boot:run -pl fluxgate-samples/fluxgate-sample-mongo
 * </pre>
 */
@SpringBootApplication
public class MongoSampleApplication {

  public static void main(String[] args) {
    SpringApplication.run(MongoSampleApplication.class, args);
  }
}
