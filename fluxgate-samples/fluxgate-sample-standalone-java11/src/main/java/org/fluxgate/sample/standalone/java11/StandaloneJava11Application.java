package org.fluxgate.sample.standalone.java11;

import org.fluxgate.spring.properties.FluxgateProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Standalone FluxGate sample application for Java 11.
 *
 * <p>This application demonstrates FluxGate with:
 *
 * <ul>
 *   <li>Java 11 compatibility
 *   <li>Spring Boot 2.7.x
 *   <li>Direct MongoDB connection for rule storage
 *   <li>Direct Redis connection for rate limiting
 *   <li>RequestContext customization
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties(FluxgateProperties.class)
public class StandaloneJava11Application {

  public static void main(String[] args) {
    SpringApplication.run(StandaloneJava11Application.class, args);
  }
}
