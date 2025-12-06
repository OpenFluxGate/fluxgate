package org.fluxgate.spring.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import org.fluxgate.spring.metrics.FluxgateMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for FluxGate Prometheus/Micrometer metrics.
 * <p>
 * This configuration is activated when:
 * <ul>
 *   <li>Micrometer is on the classpath</li>
 *   <li>A MeterRegistry bean exists</li>
 *   <li>{@code fluxgate.metrics.enabled} is true (default: true)</li>
 * </ul>
 * <p>
 * Provides metrics for:
 * <ul>
 *   <li>Total requests processed</li>
 *   <li>Allowed vs rejected requests</li>
 *   <li>Request processing duration</li>
 *   <li>Remaining tokens per bucket</li>
 * </ul>
 *
 * @see FluxgateMetrics
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(name = "fluxgate.metrics.enabled", havingValue = "true", matchIfMissing = true)
public class FluxgateMetricsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FluxgateMetricsAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public FluxgateMetrics fluxgateMetrics(MeterRegistry meterRegistry) {
        log.info("Configuring FluxGate Prometheus metrics");
        return new FluxgateMetrics(meterRegistry);
    }
}
