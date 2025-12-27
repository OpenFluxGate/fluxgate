package org.fluxgate.spring.autoconfigure;

import org.aspectj.lang.annotation.Aspect;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.spring.annotation.EnableFluxgateAspect;
import org.fluxgate.spring.aop.RateLimitAspect;
import org.fluxgate.spring.filter.RequestContextCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Role;

/**
 * Auto-configuration for FluxGate AOP-based rate limiting.
 *
 * <p>This configuration is enabled when {@link EnableFluxgateAspect} annotation is used.
 *
 * <p>It creates a {@link RateLimitAspect} bean that intercepts methods annotated with {@link
 * org.fluxgate.spring.annotation.RateLimit}.
 *
 * <p>Example usage:
 *
 * <pre>
 * {@code @SpringBootApplication}
 * {@code @EnableFluxgateAspect}
 * public class MyApplication {
 *     public static void main(String[] args) {
 *         SpringApplication.run(MyApplication.class, args);
 *     }
 * }
 *
 * {@code @RestController}
 * public class ApiController {
 *     {@code @RateLimit}(ruleSetId = "api-rules")
 *     {@code @GetMapping}("/api/data")
 *     public Data getData() {
 *         return dataService.fetch();
 *     }
 * }
 * </pre>
 *
 * @see EnableFluxgateAspect
 * @see RateLimitAspect
 * @see org.fluxgate.spring.annotation.RateLimit
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({Aspect.class, FluxgateRateLimitHandler.class})
@ConditionalOnBean(FluxgateRateLimitHandler.class)
@EnableAspectJAutoProxy
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class FluxgateAopAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(FluxgateAopAutoConfiguration.class);

  /**
   * Creates the RateLimitAspect bean.
   *
   * @param handler the rate limit handler
   * @param customizerProvider optional request context customizer
   * @return the rate limit aspect
   */
  @Bean
  @ConditionalOnMissingBean
  public RateLimitAspect rateLimitAspect(
      FluxgateRateLimitHandler handler,
      ObjectProvider<RequestContextCustomizer> customizerProvider) {

    RequestContextCustomizer customizer = resolveContextCustomizer(customizerProvider);

    log.info("Creating RateLimitAspect");
    log.info("  Handler: {}", handler.getClass().getSimpleName());
    if (customizer != null) {
      log.info("  RequestContext customizer: {}", customizer.getClass().getSimpleName());
    }

    return new RateLimitAspect(handler, customizer);
  }

  /**
   * Resolves RequestContextCustomizer beans. If multiple customizers are registered, they are
   * combined in order.
   */
  private RequestContextCustomizer resolveContextCustomizer(
      ObjectProvider<RequestContextCustomizer> customizerProvider) {
    RequestContextCustomizer[] customizers =
        customizerProvider.orderedStream().toArray(RequestContextCustomizer[]::new);

    if (customizers.length == 0) {
      return null;
    } else if (customizers.length == 1) {
      return customizers[0];
    } else {
      // Combine multiple customizers
      log.info("Combining {} RequestContextCustomizers", customizers.length);
      RequestContextCustomizer combined = customizers[0];
      for (int i = 1; i < customizers.length; i++) {
        combined = combined.andThen(customizers[i]);
      }
      return combined;
    }
  }
}
