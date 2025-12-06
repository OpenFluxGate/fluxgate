package org.fluxgate.spring.autoconfigure;

import org.fluxgate.spring.actuator.FluxgateHealthIndicator;
import org.fluxgate.spring.actuator.FluxgateHealthIndicator.MongoHealthChecker;
import org.fluxgate.spring.actuator.FluxgateHealthIndicator.RedisHealthChecker;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for FluxGate Spring Boot Actuator health endpoint.
 * <p>
 * This configuration is activated when:
 * <ul>
 *   <li>Spring Boot Actuator is on the classpath</li>
 *   <li>{@code fluxgate.actuator.health.enabled} is true (default: true)</li>
 * </ul>
 * <p>
 * Provides a health indicator at {@code /actuator/health/fluxgate} showing:
 * <ul>
 *   <li>Rate limiting enabled status</li>
 *   <li>MongoDB connection status (if enabled)</li>
 *   <li>Redis connection status (if enabled)</li>
 * </ul>
 *
 * @see FluxgateHealthIndicator
 */
@AutoConfiguration
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(name = "fluxgate.actuator.health.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FluxgateProperties.class)
public class FluxgateActuatorAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FluxgateActuatorAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(name = "fluxgateHealthIndicator")
    public FluxgateHealthIndicator fluxgateHealthIndicator(
            FluxgateProperties properties,
            @Autowired(required = false) MongoHealthChecker mongoHealthChecker,
            @Autowired(required = false) RedisHealthChecker redisHealthChecker) {

        log.info("Configuring FluxGate Actuator health indicator");
        return new FluxgateHealthIndicator(properties, mongoHealthChecker, redisHealthChecker);
    }
}
