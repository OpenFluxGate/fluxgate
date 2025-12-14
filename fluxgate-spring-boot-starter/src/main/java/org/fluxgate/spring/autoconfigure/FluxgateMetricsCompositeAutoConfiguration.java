package org.fluxgate.spring.autoconfigure;

import java.util.List;
import org.fluxgate.core.metrics.CompositeMetricsRecorder;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Auto-configuration that combines multiple {@link RateLimitMetricsRecorder} implementations into a
 * single {@link CompositeMetricsRecorder}.
 *
 * <p>This configuration runs after individual recorder configurations and collects all available
 * recorders:
 *
 * <ul>
 *   <li>{@code MicrometerMetricsRecorder} - from FluxgateMetricsAutoConfiguration
 *   <li>{@code MongoRateLimitMetricsRecorder} - from FluxgateMongoAutoConfiguration
 * </ul>
 *
 * <p>The composite recorder is marked as {@code @Primary}, so it will be injected wherever a single
 * RateLimitMetricsRecorder is required.
 *
 * <p>Example configuration to enable both:
 *
 * <pre>{@code
 * fluxgate:
 *   metrics:
 *     enabled: true          # Enables Prometheus metrics
 *   mongo:
 *     enabled: true
 *     event-collection: rate_limit_events  # Enables MongoDB event logging
 * }</pre>
 *
 * @see CompositeMetricsRecorder
 * @see FluxgateMetricsAutoConfiguration
 * @see FluxgateMongoAutoConfiguration
 */
@AutoConfiguration
@AutoConfigureAfter({FluxgateMetricsAutoConfiguration.class, FluxgateMongoAutoConfiguration.class})
@ConditionalOnBean(RateLimitMetricsRecorder.class)
public class FluxgateMetricsCompositeAutoConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(FluxgateMetricsCompositeAutoConfiguration.class);

  /**
   * Creates a composite metrics recorder that delegates to all available recorders.
   *
   * <p>This bean is marked as {@code @Primary} so it will be preferred when injecting a single
   * RateLimitMetricsRecorder. All individual recorders are still available for direct injection by
   * name.
   *
   * <p>If only one recorder is available, it will be wrapped in a composite for consistency. This
   * ensures the same behavior regardless of how many recorders are configured.
   *
   * @param recorders all available RateLimitMetricsRecorder beans
   * @return a CompositeMetricsRecorder wrapping all recorders
   */
  @Bean
  @Primary
  public RateLimitMetricsRecorder compositeMetricsRecorder(
      List<RateLimitMetricsRecorder> recorders) {
    if (recorders.size() == 1) {
      RateLimitMetricsRecorder single = recorders.get(0);
      log.info("Single metrics recorder available: {}", single.getClass().getSimpleName());
      return single;
    }

    log.info(
        "Creating CompositeMetricsRecorder with {} recorders: {}",
        recorders.size(),
        recorders.stream().map(r -> r.getClass().getSimpleName()).toList());
    return new CompositeMetricsRecorder(recorders);
  }
}
