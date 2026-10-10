package org.fluxgate.sample.filter.config;

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
  public OpenAPI customOpenAPI(ObjectProvider<BuildProperties> buildProperties) {
    BuildProperties build = buildProperties.getIfAvailable();
    String version = build != null && build.getVersion() != null ? build.getVersion() : "unknown";
    return new OpenAPI()
        .info(
            new Info()
                .title("FluxGate Filter Sample API")
                .version(version)
                .description(
                    "Sample demonstrating **automatic rate limiting** using FluxgateRateLimitFilter.\n\n"
                        + "## How it works\n\n"
                        + "Requests matching `/api/*` (one path segment) are rate-limited by the filter, "
                        + "which asks the rate limit check API at `fluxgate.api.url` "
                        + "(fluxgate-sample-redis by default). "
                        + "No rate limiting code is needed in controllers.\n\n"
                        + "## Configuration\n\n"
                        + "```java\n"
                        + "@EnableFluxgateFilter(\n"
                        + "    handler = HttpRateLimitHandler.class,\n"
                        + "    ruleSetId = \"api-limits\",\n"
                        + "    includePatterns = {\"/api/*\"},\n"
                        + "    excludePatterns = {\"/health\", \"/actuator/*\", \"/swagger-ui/*\", \"/v3/api-docs/*\"})\n"
                        + "```\n\n"
                        + "## Rate Limit Response\n\n"
                        + "When rate limit is exceeded, you'll receive:\n"
                        + "- HTTP 429 Too Many Requests with an application/problem+json body\n"
                        + "- `Retry-After` header with seconds to wait\n"
                        + "- `X-RateLimit-Remaining` / `RateLimit-Remaining` headers with remaining tokens")
                .contact(
                    new Contact().name("FluxGate").url("https://github.com/OpenFluxGate/fluxgate"))
                .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")));
  }
}
