package org.fluxgate.spring.autoconfigure;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.fluxgate.core.constants.FluxgateConstants.Metrics;
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.fluxgate.spring.handler.ResilientRateLimiter;
import org.fluxgate.spring.metrics.FluxgateMetrics;
import org.fluxgate.spring.metrics.MicrometerMetricsRecorder;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
@AutoConfigureAfter({
  CompositeMeterRegistryAutoConfiguration.class,
  FluxgateRateLimiterAutoConfiguration.class
})
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
   * Creates the {@link FluxgateMetrics} facade behind the {@code fluxgate.limiter.failures}
   * counter.
   *
   * <p>Its endpoint tag comes from the {@link MicrometerMetricsRecorder}: the same {@code
   * include-endpoint} and normalization settings, and the same budget of distinct values, so the
   * failure counter folds an endpoint past the budget into {@code other} exactly like the request
   * meters do.
   *
   * @param meterRegistry the Micrometer registry
   * @param recorderProvider the recorder whose endpoint tag policy is shared
   * @param properties the FluxGate properties
   * @return the metrics facade
   */
  @Bean
  @ConditionalOnMissingBean
  public FluxgateMetrics fluxgateMetrics(
      MeterRegistry meterRegistry,
      ObjectProvider<MicrometerMetricsRecorder> recorderProvider,
      FluxgateProperties properties) {
    MicrometerMetricsRecorder recorder = recorderProvider.getIfUnique();
    return recorder != null
        ? new FluxgateMetrics(meterRegistry, recorder)
        : new FluxgateMetrics(meterRegistry, properties.getMetrics().getMaxEndpointTags());
  }

  /**
   * Adapts {@link FluxgateMetrics} onto the limiter failure sink of {@link ResilientRateLimiter},
   * so every degraded limiter decision increments {@code fluxgate.limiter.failures}.
   *
   * <p>It used to be registered by the rate limiter auto-configuration on condition of a {@code
   * FluxgateMetrics} bean that nothing created, so the counter was never emitted.
   *
   * @param fluxgateMetrics the metrics facade
   * @return the failure recorder
   */
  @Bean
  @ConditionalOnMissingBean(ResilientRateLimiter.FailureRecorder.class)
  public ResilientRateLimiter.FailureRecorder fluxgateLimiterFailureRecorder(
      FluxgateMetrics fluxgateMetrics) {
    return fluxgateMetrics::recordLimiterFailure;
  }

  /**
   * Registers an eviction counter for the in-memory Bucket4j limiter.
   *
   * <p>The counter is named {@code fluxgate.limiter.bucket_evictions} and bound to {@link
   * Bucket4jRateLimiter#getEvictionCount()}, which increments on every size- or idle-based bucket
   * eviction. An evicted bucket is re-initialised full on the next request, effectively resetting
   * that key's limit.
   *
   * <p>Declared here, after both the meter registry and the limiters exist; as a nested class of
   * the rate limiter auto-configuration its {@code @ConditionalOnBean(MeterRegistry.class)} was
   * evaluated before Boot had created the registry, and the counter never appeared.
   *
   * @param rateLimiters the in-memory limiter
   * @param registry the Micrometer registry
   * @return the registered counter
   */
  @Bean
  @ConditionalOnBean(Bucket4jRateLimiter.class)
  public FunctionCounter bucketEvictionCounter(
      ObjectProvider<Bucket4jRateLimiter> rateLimiters, MeterRegistry registry) {
    return FunctionCounter.builder(
            "fluxgate.limiter.bucket_evictions",
            rateLimiters.getIfUnique(),
            Bucket4jRateLimiter::getEvictionCount)
        .description(
            "Total number of in-memory token buckets evicted from the cache. "
                + "Each eviction resets that key's quota to full.")
        .register(registry);
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
   * <p>The overflow value {@code other} and the placeholder {@code unknown} are exempt from the
   * cap. With the same budget as the recorder, the filter used to deny exactly the {@code other}
   * series the recorder falls back to, so overflowing traffic disappeared from the metrics. {@code
   * fluxgate.limiter.failures} is exempt as a whole: {@link FluxgateMetrics} already bounds it with
   * the recorder's budget, and a limiter failure must never be dropped from the metrics.
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
    return new EndpointTagLimitMeterFilter(properties.getMetrics().getMaxEndpointTags());
  }

  /** Denies FluxGate meters whose endpoint tag would exceed the budget, except the fallbacks. */
  static final class EndpointTagLimitMeterFilter implements MeterFilter {

    private static final Set<String> EXEMPT = Set.of("other", "unknown");

    private final int maxEndpointTags;
    private final Set<String> observed = ConcurrentHashMap.newKeySet();

    EndpointTagLimitMeterFilter(int maxEndpointTags) {
      this.maxEndpointTags = maxEndpointTags;
    }

    @Override
    public MeterFilterReply accept(Meter.Id id) {
      if (!id.getName().startsWith(Metrics.PREFIX)
          || FluxgateMetrics.LIMITER_FAILURES.equals(id.getName())) {
        return MeterFilterReply.NEUTRAL;
      }
      String endpoint = id.getTag(Metrics.TAG_ENDPOINT);
      if (endpoint == null || EXEMPT.contains(endpoint) || observed.contains(endpoint)) {
        return MeterFilterReply.NEUTRAL;
      }
      synchronized (observed) {
        if (observed.size() >= maxEndpointTags) {
          return MeterFilterReply.DENY;
        }
        observed.add(endpoint);
      }
      return MeterFilterReply.NEUTRAL;
    }
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
