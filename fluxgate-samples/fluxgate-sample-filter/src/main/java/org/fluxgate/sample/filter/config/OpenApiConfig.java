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
                .info(new Info()
                        .title("FluxGate Filter Sample API")
                        .version("1.0.0")
                        .description("""
                                Sample demonstrating **automatic rate limiting** using FluxgateRateLimitFilter.

                                ## How it works

                                All requests to `/api/**` are automatically rate-limited by the filter.
                                No rate limiting code is needed in controllers!

                                ## Configuration

                                ```yaml
                                fluxgate:
                                  ratelimit:
                                    filter-enabled: true
                                    default-rule-set-id: api-limits
                                    include-patterns:
                                      - /api/*
                                ```

                                ## Rate Limit Response

                                When rate limit is exceeded, you'll receive:
                                - HTTP 429 Too Many Requests
                                - `Retry-After` header with seconds to wait
                                - `X-RateLimit-Remaining` header with remaining tokens
                                """)
                        .contact(new Contact()
                                .name("FluxGate")
                                .url("https://github.com/openfluxgate/fluxgate"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}
