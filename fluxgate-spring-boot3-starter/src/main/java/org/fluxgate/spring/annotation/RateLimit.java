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
   * <p>Only applies when {@link #waitForRefill()} is true.
   *
   * @return maximum wait time in milliseconds
   */
  long maxWaitTimeMs() default 5000;

  /**
   * Maximum number of concurrent requests that can wait for refill.
   *
   * <p>Only applies when {@link #waitForRefill()} is true. Prevents resource exhaustion from too
   * many waiting requests.
   *
   * @return maximum concurrent waiting requests
   */
  int maxConcurrentWaits() default 100;
}
