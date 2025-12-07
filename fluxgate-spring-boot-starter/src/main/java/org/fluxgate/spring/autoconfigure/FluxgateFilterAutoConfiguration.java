package org.fluxgate.spring.autoconfigure;

import java.util.Map;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.spring.annotation.EnableFluxgateFilter;
import org.fluxgate.spring.filter.FluxgateRateLimitFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.StringUtils;

/**
 * Configuration for FluxGate HTTP Filter.
 *
 * <p>This configuration is enabled when {@link EnableFluxgateFilter} annotation is used.
 *
 * <p>The filter reads configuration from the annotation:
 *
 * <ul>
 *   <li>{@code handler} - The handler class for rate limiting
 *   <li>{@code ruleSetId} - Default rule set ID
 *   <li>{@code includePatterns} - URL patterns to include
 *   <li>{@code excludePatterns} - URL patterns to exclude
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>
 * {@code @SpringBootApplication}
 * {@code @EnableFluxgateFilter(handler = MyHandler.class, ruleSetId = "api-limits")}
 * public class MyApplication {
 *     public static void main(String[] args) {
 *         SpringApplication.run(MyApplication.class, args);
 *     }
 * }
 * </pre>
 *
 * @see EnableFluxgateFilter
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "jakarta.servlet.Filter")
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class FluxgateFilterAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(FluxgateFilterAutoConfiguration.class);

  /**
   * Creates the FluxgateRateLimitFilter.
   *
   * <p>Reads configuration from {@link EnableFluxgateFilter} annotation.
   */
  @Bean
  @ConditionalOnMissingBean(FluxgateRateLimitFilter.class)
  public FluxgateRateLimitFilter fluxgateRateLimitFilter(
      ApplicationContext applicationContext,
      ObjectProvider<FluxgateRateLimitHandler> handlerProvider) {

    // Find the annotation
    EnableFluxgateFilter annotation = findEnableFluxgateFilterAnnotation(applicationContext);
    if (annotation == null) {
      log.warn("@EnableFluxgateFilter annotation not found, using defaults");
      FluxgateRateLimitHandler handler =
          handlerProvider.getIfAvailable(() -> FluxgateRateLimitHandler.ALLOW_ALL);
      return new FluxgateRateLimitFilter(handler, "", new String[0], new String[0]);
    }

    // Get handler
    FluxgateRateLimitHandler handler =
        resolveHandler(applicationContext, handlerProvider, annotation);

    // Get configuration from annotation
    String ruleSetId = annotation.ruleSetId();
    String[] includePatterns = annotation.includePatterns();
    String[] excludePatterns = annotation.excludePatterns();

    log.info("Creating FluxgateRateLimitFilter");
    log.info("  Handler: {}", handler.getClass().getSimpleName());
    log.info("  Rule set ID: {}", StringUtils.hasText(ruleSetId) ? ruleSetId : "(not set)");

    return new FluxgateRateLimitFilter(handler, ruleSetId, includePatterns, excludePatterns);
  }

  /** Registers the FluxgateRateLimitFilter with the servlet container. */
  @Bean
  @ConditionalOnMissingBean(name = "fluxgateRateLimitFilterRegistration")
  public FilterRegistrationBean<FluxgateRateLimitFilter> fluxgateRateLimitFilterRegistration(
      FluxgateRateLimitFilter filter, ApplicationContext applicationContext) {

    EnableFluxgateFilter annotation = findEnableFluxgateFilterAnnotation(applicationContext);

    FilterRegistrationBean<FluxgateRateLimitFilter> registration =
        new FilterRegistrationBean<>(filter);

    registration.setName("fluxgateRateLimitFilter");

    // Set filter order from annotation
    int filterOrder = annotation != null ? annotation.filterOrder() : 1;
    registration.setOrder(filterOrder);

    // Always use /* for servlet filter registration
    // The actual path matching (with Ant patterns like /api/**) is done inside the filter
    // because Servlet spec only supports simple wildcard patterns (/*), not Ant patterns (**)
    registration.addUrlPatterns("/*");

    log.info("Registered FluxgateRateLimitFilter with order: {}", filterOrder);

    return registration;
  }

  /** Finds the @EnableFluxgateFilter annotation in the application context. */
  private EnableFluxgateFilter findEnableFluxgateFilterAnnotation(ApplicationContext context) {
    Map<String, Object> beans = context.getBeansWithAnnotation(EnableFluxgateFilter.class);
    for (Object bean : beans.values()) {
      EnableFluxgateFilter annotation =
          AnnotationUtils.findAnnotation(bean.getClass(), EnableFluxgateFilter.class);
      if (annotation != null) {
        return annotation;
      }
    }
    return null;
  }

  /** Resolves the handler from annotation or context. */
  private FluxgateRateLimitHandler resolveHandler(
      ApplicationContext context,
      ObjectProvider<FluxgateRateLimitHandler> handlerProvider,
      EnableFluxgateFilter annotation) {

    Class<? extends FluxgateRateLimitHandler> handlerClass = annotation.handler();

    // If handler class is specified and not the interface itself
    if (handlerClass != FluxgateRateLimitHandler.class) {
      try {
        return context.getBean(handlerClass);
      } catch (NoSuchBeanDefinitionException e) {
        log.warn(
            "Handler bean of type {} not found, falling back to available handler: {}",
            handlerClass.getSimpleName(),
            e.getMessage());
      }
    }

    // Fall back to any available handler
    FluxgateRateLimitHandler handler = handlerProvider.getIfAvailable();
    if (handler != null) {
      return handler;
    }

    // Final fallback
    log.warn("No FluxgateRateLimitHandler found, using ALLOW_ALL handler");
    return FluxgateRateLimitHandler.ALLOW_ALL;
  }
}
