package org.fluxgate.spring.autoconfigure;

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
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
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
 * <p>The HTTP status of the custom {@code DEGRADED} status is contributed as a property default
 * ({@code management.endpoint.health.status.http-mapping.degraded}, from {@code
 * fluxgate.actuator.health.degraded-http-status}) by {@link
 * org.fluxgate.spring.actuator.FluxgateHealthStatusEnvironmentPostProcessor}, together with Boot's
 * own {@code DOWN} and {@code OUT_OF_SERVICE} defaults. No {@code HttpCodeStatusMapper} bean is
 * registered, so Boot's mapper and every user mapping stay in charge.
 *
 * @see FluxgateHealthIndicator
 */
@AutoConfiguration
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(
    name = "fluxgate.actuator.health.enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(FluxgateProperties.class)
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
}
