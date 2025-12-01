package org.fluxgate.sample.api.config;

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
                        .title("FluxGate Sample - API Gateway")
                        .description("API Gateway that proxies requests to Control-plane (MongoDB, port 8081) and Data-plane (Redis, port 8082) services")
                        .version("0.0.1-SNAPSHOT")
                        .contact(new Contact()
                                .name("FluxGate")
                                .url("https://github.com/OpenFluxGate/fluxgate"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}
