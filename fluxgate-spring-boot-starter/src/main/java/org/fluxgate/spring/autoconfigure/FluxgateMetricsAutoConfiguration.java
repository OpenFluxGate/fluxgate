package org.fluxgate.spring.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;

import org.fluxgate.spring.metrics.MicrometerMetricsRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for FluxGate Prometheus/Micrometer metrics.
 *
 * <p>This configuration is activated when:
 *
 * <ul>
 *   <li>Micrometer is on the classpath
 *   <li>A MeterRegistry bean exists
 *   <li>{@code fluxgate.metrics.enabled} is true (default: true)
 * </ul>
 *
 * <p>Provides metrics for:
 *
 * <ul>
 *   <li>Total requests processed
 *   <li>Allowed vs rejected requests
 *   <li>Request processing duration
 *   <li>Remaining tokens per bucket
 * </ul>
 *
 * <p>This recorder is automatically combined with other recorders (like
 * MongoRateLimitMetricsRecorder) via {@link FluxgateMetricsCompositeAutoConfiguration}.
 *
 * @see MicrometerMetricsRecorder
 * @see FluxgateMetricsCompositeAutoConfiguration
 */
@AutoConfiguration
@AutoConfigureAfter(CompositeMeterRegistryAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(
    name = "fluxgate.metrics.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class FluxgateMetricsAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(FluxgateMetricsAutoConfiguration.class);

  /**
   * Creates the MicrometerMetricsRecorder for Prometheus metrics.
   *
   * <p>This bean is named explicitly to allow multiple RateLimitMetricsRecorder
   * implementations to coexist. The CompositeMetricsRecorder will collect all
   * available recorders.
   *
   * @param meterRegistry the Micrometer registry
   * @return configured MicrometerMetricsRecorder
   */
  @Bean(name = "micrometerMetricsRecorder")
  public MicrometerMetricsRecorder micrometerMetricsRecorder(MeterRegistry meterRegistry) {
    log.info("Creating MicrometerMetricsRecorder for Prometheus metrics");
    return new MicrometerMetricsRecorder(meterRegistry);
  }
}
