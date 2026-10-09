package org.fluxgate.sample.filter.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI customOpenAPI() {
    return new OpenAPI()
        .info(
            new Info()
                .title("FluxGate Filter Sample API")
                .version("1.0.0")
                .description(
                    "Sample demonstrating **automatic rate limiting** using FluxgateRateLimitFilter.\n\n"
                        + "## How it works\n\n"
                        + "All requests to `/api/**` are automatically rate-limited by the filter. "
                        + "No rate limiting code is needed in controllers!\n\n"
                        + "## Configuration\n\n"
                        + "```yaml\n"
                        + "fluxgate:\n"
                        + "  ratelimit:\n"
                        + "    filter-enabled: true\n"
                        + "    default-rule-set-id: api-limits\n"
                        + "    include-patterns:\n"
                        + "      - /api/*\n"
                        + "```\n\n"
                        + "## Rate Limit Response\n\n"
                        + "When rate limit is exceeded, you'll receive:\n"
                        + "- HTTP 429 Too Many Requests\n"
                        + "- `Retry-After` header with seconds to wait\n"
                        + "- `X-RateLimit-Remaining` header with remaining tokens")
                .contact(
                    new Contact().name("FluxGate").url("https://github.com/openfluxgate/fluxgate"))
                .license(
                    new License()
                        .name("Apache 2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0")));
  }
}
