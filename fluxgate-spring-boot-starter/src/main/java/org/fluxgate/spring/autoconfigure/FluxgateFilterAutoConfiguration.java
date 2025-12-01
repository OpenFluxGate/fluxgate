package org.fluxgate.spring.autoconfigure;

import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.spring.filter.FluxgateRateLimitFilter;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for FluxGate HTTP Filter.
 * <p>
 * This configuration is enabled only when:
 * <ul>
 *   <li>{@code fluxgate.ratelimit.filter-enabled=true}</li>
 *   <li>A {@link RateLimiter} bean exists (typically from Redis auto-config)</li>
 *   <li>Running in a web application context</li>
 * </ul>
 * <p>
 * Creates beans for:
 * <ul>
 *   <li>{@link FluxgateRateLimitFilter} - The rate limiting filter</li>
 *   <li>{@link FilterRegistrationBean} - Servlet filter registration</li>
 * </ul>
 * <p>
 * This configuration does NOT require MongoDB directly.
 * It only requires a RateLimiter bean and optionally a RuleSetProvider.
 * <p>
 * The RuleSetProvider can come from:
 * <ul>
 *   <li>MongoDB auto-config (if enabled)</li>
 *   <li>Custom bean provided by the application</li>
 *   <li>Or be null (filter will log warning and allow requests)</li>
 * </ul>
 *
 * @see FluxgateMongoAutoConfiguration
 * @see FluxgateRedisAutoConfiguration
 */
@AutoConfiguration(after = {FluxgateRedisAutoConfiguration.class, FluxgateMongoAutoConfiguration.class})
@ConditionalOnProperty(prefix = "fluxgate.ratelimit", name = "filter-enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "jakarta.servlet.Filter")
@ConditionalOnBean(RateLimiter.class)
@EnableConfigurationProperties(FluxgateProperties.class)
public class FluxgateFilterAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FluxgateFilterAutoConfiguration.class);

    private final FluxgateProperties properties;

    public FluxgateFilterAutoConfiguration(FluxgateProperties properties) {
        this.properties = properties;
    }

    /**
     * Creates the FluxgateRateLimitFilter.
     * <p>
     * The filter requires:
     * <ul>
     *   <li>RateLimiter - Required (typically RedisRateLimiter)</li>
     *   <li>RateLimitRuleSetProvider - Optional (can be null)</li>
     * </ul>
     * <p>
     * If no RuleSetProvider is available, the filter will log warnings
     * and allow requests through (fail-open behavior).
     */
    @Bean
    @ConditionalOnMissingBean(FluxgateRateLimitFilter.class)
    public FluxgateRateLimitFilter fluxgateRateLimitFilter(
            RateLimiter rateLimiter,
            ObjectProvider<RateLimitRuleSetProvider> ruleSetProviderProvider) {

        RateLimitRuleSetProvider ruleSetProvider = ruleSetProviderProvider.getIfAvailable();

        if (ruleSetProvider == null) {
            log.warn("No RateLimitRuleSetProvider bean found. " +
                    "Rate limiting will be skipped unless a provider is configured.");
            log.warn("To fix this, either:");
            log.warn("  1. Enable MongoDB: fluxgate.mongo.enabled=true");
            log.warn("  2. Provide a custom RateLimitRuleSetProvider bean");
        } else {
            log.info("FluxgateRateLimitFilter using RuleSetProvider: {}",
                    ruleSetProvider.getClass().getSimpleName());
        }

        log.info("Creating FluxgateRateLimitFilter");
        return new FluxgateRateLimitFilter(
                rateLimiter,
                ruleSetProvider,
                properties.getRatelimit());
    }

    /**
     * Registers the FluxgateRateLimitFilter with the servlet container.
     * <p>
     * Configuration:
     * <ul>
     *   <li>Order: Configurable via {@code fluxgate.ratelimit.filter-order}</li>
     *   <li>URL patterns: Configurable via {@code fluxgate.ratelimit.include-patterns}</li>
     * </ul>
     */
    @Bean
    @ConditionalOnMissingBean(name = "fluxgateRateLimitFilterRegistration")
    public FilterRegistrationBean<FluxgateRateLimitFilter> fluxgateRateLimitFilterRegistration(
            FluxgateRateLimitFilter filter) {

        FilterRegistrationBean<FluxgateRateLimitFilter> registration =
                new FilterRegistrationBean<>(filter);

        registration.setName("fluxgateRateLimitFilter");
        registration.setOrder(properties.getRatelimit().getFilterOrder());
        registration.addUrlPatterns(properties.getRatelimit().getIncludePatterns());

        log.info("Registered FluxgateRateLimitFilter with order: {}",
                properties.getRatelimit().getFilterOrder());
        log.info("URL patterns: {}",
                String.join(", ", properties.getRatelimit().getIncludePatterns()));

        return registration;
    }
}
