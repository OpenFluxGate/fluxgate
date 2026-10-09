package org.fluxgate.spring.autoconfigure;

import java.util.Map;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.annotation.EnableFluxgateFilter;
import org.fluxgate.spring.filter.FluxgateRateLimitFilter;
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
import org.fluxgate.spring.properties.FluxgateProperties.WaitForRefillProperties;
import org.fluxgate.spring.util.TrustedProxies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportAware;
import org.springframework.context.annotation.Role;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.StringUtils;

/**
 * Configuration for FluxGate HTTP Filter.
 *
 * <p>This configuration is enabled when {@link EnableFluxgateFilter} annotation is used, and can be
 * switched off entirely with {@code fluxgate.ratelimit.enabled=false}.
 *
 * <p><b>Precedence</b>: {@code application.yml} wins. Every setting is read from {@link
 * FluxgateProperties} first and only falls back to the matching {@link EnableFluxgateFilter}
 * attribute when the property is not set. The annotation therefore configures defaults for code
 * that ships without a yml, and operators can always override them.
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
@ConditionalOnClass(name = "javax.servlet.Filter")
@ConditionalOnProperty(name = "fluxgate.ratelimit.enabled", matchIfMissing = true)
@EnableConfigurationProperties(FluxgateProperties.class)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class FluxgateFilterAutoConfiguration implements ImportAware {

  private static final Logger log = LoggerFactory.getLogger(FluxgateFilterAutoConfiguration.class);

  /** Effective include patterns when neither the property nor the annotation names any. */
  private static final String[] ALL_PATHS = {"/**"};

  /** Filter order used when neither the property nor the annotation sets one. */
  private static final int DEFAULT_FILTER_ORDER = 1;

  /**
   * Attributes of the {@code @EnableFluxgateFilter} that imported this configuration.
   *
   * <p>H-19: they used to be found with {@code context.getBeansWithAnnotation(...)} inside a
   * {@code @Bean} method, which instantiates every non-lazy singleton before the bean post
   * processors are ready and silently strips {@code @Transactional} and {@code @Async} proxies from
   * user beans. {@link ImportAware} hands us the same metadata for free.
   */
  private AnnotationAttributes enableAttributes;

  @Override
  public void setImportMetadata(AnnotationMetadata importMetadata) {
    Map<String, Object> attributes =
        importMetadata.getAnnotationAttributes(EnableFluxgateFilter.class.getName());
    this.enableAttributes = attributes != null ? AnnotationAttributes.fromMap(attributes) : null;
    if (this.enableAttributes == null) {
      log.debug(
          "FluxgateFilterAutoConfiguration imported without @EnableFluxgateFilter, "
              + "using properties only");
    }
  }

  /**
   * Creates the FluxgateRateLimitFilter.
   *
   * @param applicationContext the application context, used only to look up an explicit handler
   *     bean
   * @param handlerProvider provider for the rate limit handler
   * @param customizerProvider providers for request context customizers
   * @param responseWriterProvider provider for a custom 429 body writer
   * @param durationRecorderProvider provider for the request duration recorder
   * @param properties the FluxGate properties
   * @return the configured filter
   */
  @Bean
  @ConditionalOnMissingBean(FluxgateRateLimitFilter.class)
  public FluxgateRateLimitFilter fluxgateRateLimitFilter(
      ApplicationContext applicationContext,
      ObjectProvider<FluxgateRateLimitHandler> handlerProvider,
      ObjectProvider<RateLimiter> rateLimiterProvider,
      ObjectProvider<RateLimitRuleSetProvider> ruleSetProviderProvider,
      ObjectProvider<RequestContextCustomizer> customizerProvider,
      ObjectProvider<RateLimitResponseWriter> responseWriterProvider,
      ObjectProvider<RateLimitDurationRecorder> durationRecorderProvider,
      FluxgateProperties properties) {

    RateLimitProperties rateLimit = properties.getRatelimit();
    FluxgateRateLimitHandler handler =
        resolveHandler(
            applicationContext,
            handlerProvider,
            rateLimiterProvider,
            ruleSetProviderProvider,
            properties);
    TrustedProxies trustedProxies = TrustedProxies.of(rateLimit.getTrustedProxies());

    warnAboutUnverifiableForwardingHeader(rateLimit, trustedProxies);

    WaitForRefillProperties waitConfig = rateLimit.getWaitForRefill();
    String ruleSetId = resolveRuleSetId(rateLimit);
    String[] includePatterns = resolveIncludePatterns(rateLimit);
    String[] excludePatterns = resolveExcludePatterns(rateLimit);

    IdentityProperties identity = rateLimit.getIdentity();
    IdentitySource identitySource =
        RequestContextFactory.resolveIdentitySource(identity.getSource());

    log.info("Creating FluxgateRateLimitFilter");
    log.info("  Handler: {}", handler.getClass().getSimpleName());
    log.info("  Rule set ID: {}", StringUtils.hasText(ruleSetId) ? ruleSetId : "(not set)");
    log.info(
        "  Identity source: {}{}",
        identitySource,
        identity.getSource() == null ? " (default)" : "");

    return new FluxgateRateLimitFilter(
        handler,
        ruleSetId,
        includePatterns,
        excludePatterns,
        waitConfig.isEnabled(),
        waitConfig.getMaxWaitTimeMs(),
        waitConfig.getMaxConcurrentWaits(),
        rateLimit.isAllowWhenLimiterFails(),
        rateLimit.isDenyWhenRuleMissing(),
        rateLimit.isCaseSensitivePatterns(),
        rateLimit.isLogQueryString(),
        rateLimit.getCostHeader(),
        rateLimit.getMaxCost(),
        new RequestContextFactory(
            rateLimit.getClientIpHeader(),
            rateLimit.isTrustClientIpHeader(),
            trustedProxies,
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

  /** Registers the FluxgateRateLimitFilter with the servlet container. */
  @Bean
  @ConditionalOnMissingBean(name = "fluxgateRateLimitFilterRegistration")
  public FilterRegistrationBean<FluxgateRateLimitFilter> fluxgateRateLimitFilterRegistration(
      FluxgateRateLimitFilter filter, FluxgateProperties properties) {

    FilterRegistrationBean<FluxgateRateLimitFilter> registration =
        new FilterRegistrationBean<>(filter);

    registration.setName("fluxgateRateLimitFilter");
    registration.setOrder(resolveFilterOrder(properties.getRatelimit()));

    // Always use /* for servlet filter registration
    // The actual path matching (with Ant patterns like /api/**) is done inside the filter
    // because Servlet spec only supports simple wildcard patterns (/*), not Ant patterns (**)
    registration.addUrlPatterns("/*");

    log.info("Registered FluxgateRateLimitFilter with order: {}", registration.getOrder());

    return registration;
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

  /**
   * C5/C-3: a forwarding header is only trustworthy when the hop that set it can be identified. Say
   * so once at startup rather than letting the deployment believe it is protected.
   */
  private void warnAboutUnverifiableForwardingHeader(
      RateLimitProperties rateLimit, TrustedProxies trustedProxies) {
    if (rateLimit.isTrustClientIpHeader() && trustedProxies.isEmpty()) {
      log.warn(
          "fluxgate.ratelimit.trust-client-ip-header=true with no trusted-proxies configured:"
              + " using the right-most X-Forwarded-For hop; configure trusted-proxies for"
              + " multi-hop setups. Without it every PER_IP limit can be bypassed by forging the"
              + " {} header.",
          rateLimit.getClientIpHeader());
    }
  }

  private String resolveRuleSetId(RateLimitProperties rateLimit) {
    String fromProperties = rateLimit.getDefaultRuleSetId();
    if (StringUtils.hasText(fromProperties)) {
      return fromProperties;
    }
    String fromAnnotation =
        enableAttributes != null ? enableAttributes.getString("ruleSetId") : null;
    return StringUtils.hasText(fromAnnotation) ? fromAnnotation : "";
  }

  private String[] resolveIncludePatterns(RateLimitProperties rateLimit) {
    String[] resolved =
        firstNonEmpty(rateLimit.getIncludePatterns(), annotationPatterns("includePatterns"));
    return resolved.length > 0 ? resolved : ALL_PATHS;
  }

  private String[] resolveExcludePatterns(RateLimitProperties rateLimit) {
    return firstNonEmpty(rateLimit.getExcludePatterns(), annotationPatterns("excludePatterns"));
  }

  private int resolveFilterOrder(RateLimitProperties rateLimit) {
    if (rateLimit.getFilterOrder() != null) {
      return rateLimit.getFilterOrder();
    }
    return enableAttributes != null
        ? enableAttributes.getNumber("filterOrder").intValue()
        : DEFAULT_FILTER_ORDER;
  }

  private String[] annotationPatterns(String attribute) {
    return enableAttributes != null ? enableAttributes.getStringArray(attribute) : new String[0];
  }

  private static String[] firstNonEmpty(String[] preferred, String[] fallback) {
    if (preferred != null && preferred.length > 0) {
      return preferred;
    }
    return fallback != null ? fallback : new String[0];
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

  /** Resolves the handler from the annotation's {@code handler} attribute or from the context. */
  private FluxgateRateLimitHandler resolveHandler(
      ApplicationContext context,
      ObjectProvider<FluxgateRateLimitHandler> handlerProvider,
      ObjectProvider<RateLimiter> rateLimiterProvider,
      ObjectProvider<RateLimitRuleSetProvider> ruleSetProviderProvider,
      FluxgateProperties properties) {

    Class<?> handlerClass =
        enableAttributes != null
            ? enableAttributes.getClass("handler")
            : FluxgateRateLimitHandler.class;

    // If handler class is specified and not the interface itself
    if (handlerClass != null && handlerClass != FluxgateRateLimitHandler.class) {
      try {
        return (FluxgateRateLimitHandler) context.getBean(handlerClass);
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

    // A default handler is registered whenever a RateLimiter and a rule set provider exist, so
    // reaching here means nothing can enforce a limit at all. Log the precise cause.
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

    if (properties.getRatelimit().isAllowWhenLimiterFails()) {
      log.warn(
          "No FluxgateRateLimitHandler available ({}). {} All requests pass through unlimited.",
          cause,
          remedy);
      return FluxgateRateLimitHandler.ALLOW_ALL;
    }

    log.warn(
        "No FluxgateRateLimitHandler available ({}). {} Every request is rejected with 429"
            + " because fluxgate.ratelimit.failure-behavior=DENY.",
        cause,
        remedy);
    return (requestContext, ruleSetId) -> RateLimitResponse.rejected(0);
  }
}
