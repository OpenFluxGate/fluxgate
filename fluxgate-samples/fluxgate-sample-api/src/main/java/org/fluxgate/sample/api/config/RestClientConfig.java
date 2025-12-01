package org.fluxgate.sample.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient controlPlaneClient(ServiceProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.getControlPlaneUrl())
                .build();
    }

    @Bean
    public RestClient dataPlaneClient(ServiceProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.getDataPlaneUrl())
                .build();
    }
}
