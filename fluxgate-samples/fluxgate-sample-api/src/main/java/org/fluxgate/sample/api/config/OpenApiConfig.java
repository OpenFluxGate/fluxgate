package org.fluxgate.sample.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  /**
   * The version is the Maven project version, from the {@code META-INF/build-info.properties} that
   * {@code spring-boot-maven-plugin}'s {@code build-info} goal writes; {@code unknown} when the
   * application runs without it (for example straight from an IDE without a Maven build).
   */
  @Bean
  public OpenAPI openAPI(ObjectProvider<BuildProperties> buildProperties) {
    BuildProperties build = buildProperties.getIfAvailable();
    String version = build != null && build.getVersion() != null ? build.getVersion() : "unknown";
    return new OpenAPI()
        .info(
            new Info()
                .title("FluxGate Sample - API Gateway")
                .description(
                    "API Gateway that proxies requests to Control-plane (MongoDB, port 8081) and Data-plane (Redis, port 8082) services")
                .version(version)
                .contact(
                    new Contact().name("FluxGate").url("https://github.com/OpenFluxGate/fluxgate"))
                .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")));
  }
}
