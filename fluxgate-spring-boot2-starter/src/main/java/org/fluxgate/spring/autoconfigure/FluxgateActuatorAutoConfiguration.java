package org.fluxgate.spring.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;
import org.fluxgate.spring.actuator.FluxgateHealthIndicator;
import org.fluxgate.spring.actuator.FluxgateHealthIndicator.MongoHealthChecker;
import org.fluxgate.spring.actuator.FluxgateHealthIndicator.RedisHealthChecker;
import org.fluxgate.spring.filter.FluxgateRateLimitFilter;
import org.fluxgate.spring.handler.RedisConnectionState;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointProperties;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.SimpleHttpCodeStatusMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.ClassUtils;

/**
 * Auto-configuration for FluxGate Spring Boot Actuator health endpoint.
 *
 * <p>This configuration is activated when:
 *
 * <ul>
 *   <li>Spring Boot Actuator is on the classpath
 *   <li>{@code fluxgate.actuator.health.enabled} is true (default: true)
 * </ul>
 *
 * <p>Provides a health indicator at {@code /actuator/health/fluxgate} showing:
 *
 * <ul>
 *   <li>Rate limiting enabled status
 *   <li>Whether a rate limit filter and aspect bean actually exist
 *   <li>MongoDB connection status (if enabled)
 *   <li>Redis connection status (if enabled)
 * </ul>
 *
 * <p>Map the custom {@code DEGRADED} status to HTTP 503 with {@code
 * management.endpoint.health.status.http-mapping.DEGRADED=503}; Spring Boot answers 200 for unknown
 * statuses by default.
 *
 * @see FluxgateHealthIndicator
 */
@AutoConfiguration
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(
    name = "fluxgate.actuator.health.enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties({FluxgateProperties.class, HealthEndpointProperties.class})
public class FluxgateActuatorAutoConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(FluxgateActuatorAutoConfiguration.class);

  /** Fully qualified name of the aspect, looked up by name so aspectjweaver stays optional. */
  private static final String ASPECT_CLASS_NAME = "org.fluxgate.spring.aop.RateLimitAspect";

  /**
   * Creates the FluxGate health indicator.
   *
   * @param properties the FluxGate properties
   * @param mongoHealthChecker optional MongoDB checker
   * @param redisHealthChecker optional Redis checker
   * @param filterProvider provider used to detect whether a filter bean exists
   * @param connectionStateProvider provider for the Redis limiter's connection state
   * @param applicationContext context used to detect whether an aspect bean exists
   * @return the health indicator
   */
  @Bean
  @ConditionalOnMissingBean(name = "fluxgateHealthIndicator")
  public FluxgateHealthIndicator fluxgateHealthIndicator(
      FluxgateProperties properties,
      @Autowired(required = false) MongoHealthChecker mongoHealthChecker,
      @Autowired(required = false) RedisHealthChecker redisHealthChecker,
      ObjectProvider<FluxgateRateLimitFilter> filterProvider,
      ObjectProvider<RedisConnectionState> connectionStateProvider,
      ApplicationContext applicationContext) {

    log.info("Configuring FluxGate Actuator health indicator");
    return new FluxgateHealthIndicator(
        properties,
        mongoHealthChecker,
        redisHealthChecker,
        () -> filterProvider.getIfAvailable() != null,
        () -> isBeanPresent(applicationContext, ASPECT_CLASS_NAME),
        connectionStateProvider.getIfUnique());
  }

  /**
   * Checks for a bean of the named type without holding a compile time reference to it, so an
   * absent optional dependency reports "no bean" instead of failing the context.
   */
  private static boolean isBeanPresent(ApplicationContext context, String className) {
    try {
      Class<?> type = ClassUtils.forName(className, context.getClassLoader());
      return context.getBeanNamesForType(type, true, false).length > 0;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /**
   * Maps the {@code DEGRADED} status to an HTTP status code (default 503).
   *
   * <p>Spring Boot maps any unrecognised status to HTTP 200 by default. A degraded FluxGate health
   * indicator means the rate limiting system is running in partial mode (e.g. Redis is unreachable
   * and the circuit has opened); callers and load balancers should treat it as a service problem,
   * not a success.
   *
   * <p>Set {@code fluxgate.actuator.health.degraded-http-status=0} to disable this bean and keep
   * Spring Boot's default behaviour.
   *
   * <p>Define your own {@link HttpCodeStatusMapper} bean to override this completely.
   *
   * @see org.fluxgate.spring.actuator.FluxgateHealthIndicator
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HttpCodeStatusMapper")
  @Conditional(DegradedHttpStatusEnabledCondition.class)
  public static class DegradedHttpStatusMapperConfiguration {

    /**
     * Creates the HTTP-code mapper that promotes {@code DEGRADED} to the configured status.
     *
     * <p>Any user-supplied mapping in {@code management.endpoint.health.status.http-mapping} takes
     * precedence over the {@code DEGRADED} default, allowing fine-grained per-deployment control.
     *
     * @param properties the FluxGate properties
     * @param healthEndpointPropertiesProvider the Spring Boot health endpoint properties
     * @return the status mapper
     */
    @Bean(name = "fluxgateDegradedHttpCodeStatusMapper")
    @ConditionalOnMissingBean(HttpCodeStatusMapper.class)
    public HttpCodeStatusMapper fluxgateDegradedHttpCodeStatusMapper(
        FluxgateProperties properties,
        ObjectProvider<HealthEndpointProperties> healthEndpointPropertiesProvider) {
      int degradedStatus = properties.getActuator().getHealth().getDegradedHttpStatus();
      Map<String, Integer> mapping = new LinkedHashMap<>();
      // Our DEGRADED default goes in first; user mappings (from management.endpoint.health.status
      // .http-mapping) are added after and therefore override it when explicitly set.
      mapping.put("DEGRADED", degradedStatus);
      HealthEndpointProperties healthProps = healthEndpointPropertiesProvider.getIfAvailable();
      if (healthProps != null) {
        mapping.putAll(healthProps.getStatus().getHttpMapping());
      }
      log.info(
          "Registering HttpCodeStatusMapper: DEGRADED -> HTTP {} (override via"
              + " management.endpoint.health.status.http-mapping.DEGRADED or a"
              + " HttpCodeStatusMapper bean)",
          mapping.getOrDefault("DEGRADED", degradedStatus));
      return new SimpleHttpCodeStatusMapper(mapping);
    }
  }

  /**
   * Matches when {@code fluxgate.actuator.health.degraded-http-status} is positive (non-zero).
   *
   * <p>Value {@code 0} or negative disables the automatic DEGRADED mapping bean.
   */
  public static final class DegradedHttpStatusEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      int status =
          Binder.get(context.getEnvironment())
              .bind("fluxgate.actuator.health.degraded-http-status", Integer.class)
              .orElse(503);
      return status > 0;
    }
  }
}
