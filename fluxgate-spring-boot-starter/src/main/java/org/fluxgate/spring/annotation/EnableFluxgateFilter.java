package org.fluxgate.spring.annotation;

import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.spring.autoconfigure.FluxgateFilterAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables FluxGate rate limiting filter for the application.
 * <p>
 * Add this annotation to your main application class or any {@code @Configuration} class
 * to enable automatic rate limiting via HTTP filter.
 * <p>
 * Example usage with custom handler:
 * <pre>
 * {@code @SpringBootApplication}
 * {@code @EnableFluxgateFilter(handler = MyRateLimitHandler.class, ruleSetId = "api-limits")}
 * public class MyApplication {
 *     public static void main(String[] args) {
 *         SpringApplication.run(MyApplication.class, args);
 *     }
 * }
 * </pre>
 * <p>
 * The handler implementation decides how rate limiting is performed:
 * <ul>
 *   <li>API-based: Call external FluxGate API server</li>
 *   <li>Redis direct: Use Redis rate limiter directly</li>
 *   <li>Standalone: In-memory rate limiting (for testing)</li>
 * </ul>
 * <p>
 * Additional configuration options in application.yml:
 * <pre>
 * fluxgate:
 *   ratelimit:
 *     include-patterns:
 *       - /api/*
 *     exclude-patterns:
 *       - /health
 *       - /actuator/*
 *     filter-order: 1
 * </pre>
 *
 * @see FluxgateRateLimitHandler
 * @see FluxgateFilterAutoConfiguration
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(FluxgateFilterAutoConfiguration.class)
public @interface EnableFluxgateFilter {

    /**
     * The handler class that implements rate limiting logic.
     * <p>
     * The handler must:
     * <ul>
     *   <li>Implement {@link FluxgateRateLimitHandler}</li>
     *   <li>Be a Spring bean (annotated with {@code @Component} or defined as {@code @Bean})</li>
     * </ul>
     * <p>
     * If not specified, the filter will look for a {@link FluxgateRateLimitHandler} bean
     * in the application context.
     *
     * @return Handler class
     */
    Class<? extends FluxgateRateLimitHandler> handler() default FluxgateRateLimitHandler.class;

    /**
     * Default rule set ID to apply for rate limiting.
     * <p>
     * This can be overridden per-request via request attributes or headers.
     *
     * @return Rule set ID
     */
    String ruleSetId() default "";

    /**
     * URL patterns to include in rate limiting.
     * <p>
     * Supports Ant-style patterns (e.g., "/api/**", "/v1/*").
     * Defaults to all paths if not specified.
     *
     * @return Include patterns
     */
    String[] includePatterns() default {};

    /**
     * URL patterns to exclude from rate limiting.
     * <p>
     * Supports Ant-style patterns (e.g., "/health", "/actuator/**").
     *
     * @return Exclude patterns
     */
    String[] excludePatterns() default {};

    /**
     * Filter order (lower values have higher priority).
     * <p>
     * Default is 1, which runs early in the filter chain.
     * Set to {@code Integer.MIN_VALUE + 100} for highest priority.
     *
     * @return Filter order
     */
    int filterOrder() default 1;
}
