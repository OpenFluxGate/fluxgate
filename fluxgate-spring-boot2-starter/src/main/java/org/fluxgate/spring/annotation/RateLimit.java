package org.fluxgate.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables rate limiting on methods or classes using AOP.
 *
 * <p>When applied to a method, rate limiting is enforced before method execution. When applied to a
 * class, all public methods are rate limited with the specified configuration.
 *
 * <p>Example usage:
 *
 * <pre>
 * &#64;RestController
 * public class ApiController {
 *
 *     &#64;RateLimit(ruleSetId = "api-rules")
 *     &#64;GetMapping("/search")
 *     public List&lt;Result&gt; search() {
 *         return searchService.search();
 *     }
 *
 *     &#64;RateLimit(ruleSetId = "premium-rules", waitForRefill = true)
 *     &#64;PostMapping("/submit")
 *     public Response submit(&#64;RequestParam String userId) {
 *         return processService.process(userId);
 *     }
 * }
 * </pre>
 *
 * @see org.fluxgate.spring.aop.RateLimitAspect
 * @see org.fluxgate.spring.annotation.EnableFluxgateAspect
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

  /**
   * The rule set ID to apply for rate limiting.
   *
   * @return rule set ID
   */
  String ruleSetId() default "";

  /**
   * Whether to wait for token refill when rate limit is exceeded.
   *
   * <p>When enabled, requests will wait up to {@link #maxWaitTimeMs()} for tokens to become
   * available instead of failing immediately.
   *
   * @return true to wait for refill, false to fail immediately
   */
  boolean waitForRefill() default false;

  /**
   * Maximum time to wait for token refill in milliseconds.
   *
   * <p>Only applies when {@link #waitForRefill()} is true. Capped by {@code
   * fluxgate.ratelimit.wait-for-refill.max-wait-time-ms}: the effective limit is the smaller of the
   * two. The wait blocks the calling thread.
   *
   * @return maximum wait time in milliseconds
   */
  long maxWaitTimeMs() default 5000;

  /**
   * Maximum number of concurrent requests that can wait for refill.
   *
   * <p><b>Ignored since 0.4.0.</b> A semaphore created per invocation limited nothing at all, so
   * the wait permits are now one application-wide {@code FluxgateWaitPermits} bean shared by the
   * filter and the aspect; this attribute is read nowhere. Configure {@code
   * fluxgate.ratelimit.wait-for-refill.max-concurrent-waits} instead.
   *
   * @return maximum concurrent waiting requests (not honoured)
   * @deprecated ignored since 0.4.0; configure {@code
   *     fluxgate.ratelimit.wait-for-refill.max-concurrent-waits}
   */
  @Deprecated
  int maxConcurrentWaits() default 100;

  /**
   * Number of permits this invocation consumes.
   *
   * <p>Use a value above 1 for expensive operations so a single call counts as several against the
   * quota, for example a bulk export that costs as much as 10 ordinary reads. Values below 1 are
   * treated as 1.
   *
   * <p>The configured {@link org.fluxgate.core.handler.FluxgateRateLimitHandler} must support
   * weighted permits; the default single-permit path is used when {@code permits} is 1.
   *
   * @return the permit count, default 1
   */
  long permits() default 1;

  /**
   * Whether to throw {@link org.fluxgate.spring.aop.RateLimitExceededException} instead of writing
   * the 429 response directly.
   *
   * <p>Enable this to render the error with a {@code @ControllerAdvice} in the application's own
   * format. The exception is thrown regardless of this flag unless the annotated method is a Spring
   * MVC request handler running in a servlet request, for example for a service method, a scheduled
   * task or a message listener.
   *
   * @return true to throw on rejection, false to write the response
   */
  boolean throwOnReject() default false;
}
