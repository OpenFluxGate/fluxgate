package org.fluxgate.sample.redis.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("FluxGate Sample - Redis Data Plane")
                        .description("Data-plane API with Redis-backed rate limiting. Uses config-based rules (no MongoDB required).")
                        .version("0.0.1-SNAPSHOT")
                        .contact(new Contact()
                                .name("FluxGate")
                                .url("https://github.com/OpenFluxGate/fluxgate"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}
