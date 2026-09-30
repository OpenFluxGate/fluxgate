package org.fluxgate.spring.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.fluxgate.core.constants.FluxgateConstants.Metrics;
import org.fluxgate.spring.metrics.MicrometerMetricsRecorder;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

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
@EnableConfigurationProperties(FluxgateProperties.class)
public class FluxgateMetricsAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(FluxgateMetricsAutoConfiguration.class);

  /**
   * Creates the MicrometerMetricsRecorder for Prometheus metrics.
   *
   * <p>This bean is named explicitly to allow multiple RateLimitMetricsRecorder implementations to
   * coexist. The CompositeMetricsRecorder will collect all available recorders.
   *
   * @param meterRegistry the Micrometer registry
   * @return configured MicrometerMetricsRecorder
   */
  @Bean(name = "micrometerMetricsRecorder")
  public MicrometerMetricsRecorder micrometerMetricsRecorder(
      MeterRegistry meterRegistry, FluxgateProperties properties) {
    FluxgateProperties.MetricsProperties metrics = properties.getMetrics();
    log.info(
        "Creating MicrometerMetricsRecorder for Prometheus metrics "
            + "(includeEndpoint={}, endpointNormalization={}, maxEndpointTags={})",
        metrics.isIncludeEndpoint(),
        metrics.isEndpointNormalization(),
        metrics.getMaxEndpointTags());
    return new MicrometerMetricsRecorder(
        meterRegistry,
        metrics.isIncludeEndpoint(),
        metrics.isEndpointNormalization(),
        metrics.getMaxEndpointTags());
  }

  /**
   * Caps how many distinct {@code endpoint} tag values FluxGate meters may create.
   *
   * <p>Endpoint normalization keeps ordinary traffic bounded, but nothing stops a scanner from
   * inventing paths. {@link MicrometerMetricsRecorder} collapses endpoints past the budget into the
   * single tag value {@code other}, which is what keeps the registry and the Prometheus scrape
   * bounded (N-13). This filter stays as defence in depth: it also covers meters registered by a
   * custom recorder, which never goes through that budget.
   *
   * <p>When {@code fluxgate.metrics.max-endpoint-tags} is {@code 0} or negative the budget is
   * considered unbounded: this bean is not registered and {@link MicrometerMetricsRecorder} also
   * applies no limit.
   *
   * @param properties the FluxGate properties
   * @return a meter filter denying meters beyond the configured tag budget
   */
  @Bean(name = "fluxgateEndpointTagLimitMeterFilter")
  @ConditionalOnMissingBean(name = "fluxgateEndpointTagLimitMeterFilter")
  @Conditional(PositiveMaxEndpointTagsCondition.class)
  public MeterFilter fluxgateEndpointTagLimitMeterFilter(FluxgateProperties properties) {
    int maxEndpointTags = properties.getMetrics().getMaxEndpointTags();
    return MeterFilter.maximumAllowableTags(
        Metrics.PREFIX, Metrics.TAG_ENDPOINT, maxEndpointTags, MeterFilter.deny());
  }

  /**
   * Matches only when {@code fluxgate.metrics.max-endpoint-tags} is greater than zero.
   *
   * <p>A value of {@code 0} or negative means the tag budget is unbounded: neither this {@link
   * MeterFilter} nor any limit is applied.
   */
  public static final class PositiveMaxEndpointTagsCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      int maxEndpointTags =
          Binder.get(context.getEnvironment())
              .bind("fluxgate.metrics.max-endpoint-tags", Integer.class)
              .orElse(1000);
      return maxEndpointTags > 0;
    }
  }
}
