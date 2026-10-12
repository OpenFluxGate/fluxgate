package org.fluxgate.envoy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(EnvoyProperties.class)
public class EnvoyAuthzApplication {
  public static void main(String[] args) {
    SpringApplication.run(EnvoyAuthzApplication.class, args);
  }
}
