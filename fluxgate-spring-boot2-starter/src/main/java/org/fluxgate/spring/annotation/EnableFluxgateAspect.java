package org.fluxgate.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.fluxgate.spring.autoconfigure.FluxgateAopAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Enables FluxGate AOP-based rate limiting for the application.
 *
 * <p>Add this annotation to your main application class or any {@code @Configuration} class to
 * enable rate limiting via AOP on methods annotated with {@link RateLimit}.
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
 * <p>Prerequisites:
 *
 * <ul>
 *   <li>A {@link org.fluxgate.core.handler.FluxgateRateLimitHandler} bean must be registered
 *   <li>Optionally, a {@link org.fluxgate.spring.filter.RequestContextCustomizer} bean for context
 *       customization
 * </ul>
 *
 * @see RateLimit
 * @see FluxgateAopAutoConfiguration
 * @see org.fluxgate.spring.aop.RateLimitAspect
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(FluxgateAopAutoConfiguration.class)
public @interface EnableFluxgateAspect {}
