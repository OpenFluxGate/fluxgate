package org.fluxgate.spring.autoconfigure;

import org.fluxgate.spring.aop.RateLimitExceededException;
import org.fluxgate.spring.aop.RateLimitExceededExceptionHandler;
import org.fluxgate.spring.filter.RateLimitResponseWriter;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.fluxgate.spring.properties.FluxgateProperties.RateLimitProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the default Spring MVC mapping of {@link RateLimitExceededException} in a servlet web
 * application that uses the {@code @RateLimit} aspect.
 *
 * <p>This is a real auto-configuration rather than part of the configuration that {@code
 * EnableFluxgateAspect} imports: auto-configurations are processed after every user configuration,
 * so {@code @ConditionalOnMissingBean} sees a {@link RateLimitExceededExceptionHandler} the
 * application declares anywhere, and {@code @ConditionalOnBean} sees the aspect that {@code
 * EnableFluxgateAspect} registered. An imported configuration is evaluated while the user's
 * configuration is still being parsed, so both conditions depended on declaration order there.
 *
 * @since 0.4.0
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(
    name = {
      "org.springframework.web.servlet.DispatcherServlet",
      "org.aspectj.lang.annotation.Aspect"
    })
@ConditionalOnBean(type = "org.fluxgate.spring.aop.RateLimitAspect")
@EnableConfigurationProperties(FluxgateProperties.class)
public class FluxgateAopExceptionHandlerAutoConfiguration {

  /**
   * Creates the {@code @RestControllerAdvice} answering {@link RateLimitExceededException} with 429
   * or 503, ordered at {@link RateLimitExceededExceptionHandler#ORDER}.
   *
   * @param responseWriterProvider provider for a custom body writer
   * @param properties the FluxGate properties
   * @return the exception handler
   */
  @Bean
  @ConditionalOnMissingBean
  public RateLimitExceededExceptionHandler rateLimitExceededExceptionHandler(
      ObjectProvider<RateLimitResponseWriter> responseWriterProvider,
      FluxgateProperties properties) {
    RateLimitProperties rateLimit = properties.getRatelimit();
    return new RateLimitExceededExceptionHandler(
        FluxgateAopAutoConfiguration.headerWriter(rateLimit),
        responseWriterProvider.getIfAvailable(
            () -> FluxgateAopAutoConfiguration.defaultResponseWriter(rateLimit)));
  }
}
