package org.fluxgate.sample.mongo.config;

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
                .title("FluxGate Sample - MongoDB Control Plane")
                .description("Control-plane API for managing rate limit rules in MongoDB")
                .version(version)
                .contact(
                    new Contact().name("FluxGate").url("https://github.com/OpenFluxGate/fluxgate"))
                .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")));
  }
}
