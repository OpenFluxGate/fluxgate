package org.fluxgate.spring.autoconfigure;

import org.aspectj.lang.annotation.Aspect;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.annotation.EnableFluxgateAspect;
import org.fluxgate.spring.aop.RateLimitAspect;
import org.fluxgate.spring.filter.IdentitySource;
import org.fluxgate.spring.filter.ProblemDetailRateLimitResponseWriter;
import org.fluxgate.spring.filter.RateLimitDurationRecorder;
import org.fluxgate.spring.filter.RateLimitHeaderWriter;
import org.fluxgate.spring.filter.RateLimitResponseWriter;
import org.fluxgate.spring.filter.RequestContextCustomizer;
import org.fluxgate.spring.filter.RequestContextFactory;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.fluxgate.spring.properties.FluxgateProperties.IdentityProperties;
import org.fluxgate.spring.properties.FluxgateProperties.RateLimitProperties;
import org.fluxgate.spring.properties.FluxgateProperties.ResponseProperties;
import org.fluxgate.spring.util.TrustedProxies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Role;
import org.springframework.util.StringUtils;

/**
 * Auto-configuration for FluxGate AOP-based rate limiting.
 *
 * <p>This configuration is enabled when {@link EnableFluxgateAspect} annotation is used, and can be
 * switched off entirely with {@code fluxgate.ratelimit.enabled=false}.
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
 * <p>The aspect is not restricted to servlet applications: a {@code @RateLimit} method invoked
 * without an HTTP request (a scheduled task, a message listener) is still limited, and a rejection
 * is reported by throwing {@link org.fluxgate.spring.aop.RateLimitExceededException} instead of
 * writing a response.
 *
 * @see EnableFluxgateAspect
 * @see RateLimitAspect
 * @see org.fluxgate.spring.annotation.RateLimit
 */
@Configuration
@ConditionalOnClass({Aspect.class, FluxgateRateLimitHandler.class})
@ConditionalOnProperty(name = "fluxgate.ratelimit.enabled", matchIfMissing = true)
@EnableConfigurationProperties(FluxgateProperties.class)
@EnableAspectJAutoProxy
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class FluxgateAopAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(FluxgateAopAutoConfiguration.class);

  /**
   * Creates the RateLimitAspect bean.
   *
   * <p>H-18: the handler is looked up through an {@link ObjectProvider} rather than gated with
   * {@code @ConditionalOnBean}. This class is loaded by the {@code @Import} on
   * {@code @EnableFluxgateAspect}, which is evaluated while the user's own configuration is still
   * being parsed, so a {@code @ConditionalOnBean} on the handler evaluated to false and the aspect
   * was never registered, silently ignoring every {@code @RateLimit}.
   *
   * @param handlerProvider provider for the rate limit handler
   * @param customizerProvider optional request context customizers
   * @param responseWriterProvider provider for a custom 429 body writer
   * @param durationRecorderProvider provider for the invocation duration recorder
   * @param properties the FluxGate properties
   * @return the rate limit aspect
   */
  @Bean
  @ConditionalOnMissingBean
  public RateLimitAspect rateLimitAspect(
      ObjectProvider<FluxgateRateLimitHandler> handlerProvider,
      ObjectProvider<RateLimiter> rateLimiterProvider,
      ObjectProvider<RateLimitRuleSetProvider> ruleSetProviderProvider,
      ObjectProvider<RequestContextCustomizer> customizerProvider,
      ObjectProvider<RateLimitResponseWriter> responseWriterProvider,
      ObjectProvider<RateLimitDurationRecorder> durationRecorderProvider,
      FluxgateProperties properties) {

    RateLimitProperties rateLimit = properties.getRatelimit();
    FluxgateRateLimitHandler handler =
        resolveHandler(handlerProvider, rateLimiterProvider, ruleSetProviderProvider, rateLimit);

    IdentityProperties identity = rateLimit.getIdentity();
    IdentitySource identitySource =
        RequestContextFactory.resolveIdentitySource(identity.getSource());

    log.info("Creating RateLimitAspect");
    log.info("  Handler: {}", handler.getClass().getSimpleName());
    log.info(
        "  Identity source: {}{}",
        identitySource,
        identity.getSource() == null ? " (derived from the classpath)" : "");

    return new RateLimitAspect(
        handler,
        defaultRuleSetId(rateLimit),
        rateLimit.isAllowWhenLimiterFails(),
        rateLimit.isDenyWhenRuleMissing(),
        rateLimit.getWaitForRefill().isEnabled(),
        rateLimit.getWaitForRefill().getMaxConcurrentWaits(),
        new RequestContextFactory(
            rateLimit.getClientIpHeader(),
            rateLimit.isTrustClientIpHeader(),
            TrustedProxies.of(rateLimit.getTrustedProxies()),
            rateLimit.isCollectHeaders(),
            rateLimit.getHeaderAllowlist(),
            resolveContextCustomizer(customizerProvider),
            identitySource,
            identity.getUserIdHeader(),
            identity.getApiKeyHeader()),
        headerWriter(rateLimit),
        responseWriterProvider.getIfAvailable(() -> defaultResponseWriter(rateLimit)),
        durationRecorderProvider.getIfUnique());
  }

  private FluxgateRateLimitHandler resolveHandler(
      ObjectProvider<FluxgateRateLimitHandler> handlerProvider,
      ObjectProvider<RateLimiter> rateLimiterProvider,
      ObjectProvider<RateLimitRuleSetProvider> ruleSetProviderProvider,
      RateLimitProperties rateLimit) {
    FluxgateRateLimitHandler handler = handlerProvider.getIfAvailable();
    if (handler != null) {
      return handler;
    }
    boolean hasLimiter = rateLimiterProvider.getIfAvailable() != null;
    boolean hasRuleSetProvider = ruleSetProviderProvider.getIfAvailable() != null;
    String cause;
    String remedy;
    if (!hasLimiter && !hasRuleSetProvider) {
      cause = "neither a RateLimiter nor a RateLimitRuleSetProvider bean exists";
      remedy =
          "Set fluxgate.redis.enabled=true (or fluxgate.ratelimit.mode=IN_MEMORY) and supply"
              + " a RateLimitRuleSetProvider (e.g. enable fluxgate.mongo), or define a"
              + " FluxgateRateLimitHandler bean directly.";
    } else if (!hasLimiter) {
      cause = "no RateLimiter bean exists";
      remedy =
          "Set fluxgate.redis.enabled=true, or fluxgate.ratelimit.mode=IN_MEMORY for a"
              + " single-instance limiter, or define a FluxgateRateLimitHandler bean.";
    } else {
      cause = "a RateLimiter bean exists but no RateLimitRuleSetProvider";
      remedy =
          "Enable fluxgate.mongo, or define a RateLimitRuleSetProvider bean named"
              + " 'delegateRuleSetProvider', or supply your own FluxgateRateLimitHandler.";
    }
    if (rateLimit.isAllowWhenLimiterFails()) {
      log.warn(
          "No FluxgateRateLimitHandler available ({}). {} Invocations pass through unlimited.",
          cause,
          remedy);
      return FluxgateRateLimitHandler.ALLOW_ALL;
    }
    log.warn(
        "No FluxgateRateLimitHandler available ({}). {} Every invocation is rejected because"
            + " fluxgate.ratelimit.failure-behavior=DENY.",
        cause,
        remedy);
    return (requestContext, ruleSetId) -> RateLimitResponse.rejected(0);
  }

  private RateLimitHeaderWriter headerWriter(RateLimitProperties rateLimit) {
    ResponseProperties response = rateLimit.getResponse();
    boolean headersEnabled = rateLimit.isIncludeHeaders();
    return new RateLimitHeaderWriter(
        headersEnabled && response.isIncludeLegacyHeaders(),
        headersEnabled && response.isIncludeStandardHeaders());
  }

  private RateLimitResponseWriter defaultResponseWriter(RateLimitProperties rateLimit) {
    ResponseProperties response = rateLimit.getResponse();
    return new ProblemDetailRateLimitResponseWriter(
        response.getContentType(), response.getBodyTemplate());
  }

  private String defaultRuleSetId(RateLimitProperties rateLimit) {
    String ruleSetId = rateLimit.getDefaultRuleSetId();
    return StringUtils.hasText(ruleSetId) ? ruleSetId : "";
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
