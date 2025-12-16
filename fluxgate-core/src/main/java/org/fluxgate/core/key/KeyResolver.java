package org.fluxgate.core.key;

import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;

/**
 * Resolves the rate limit key from the request context and rule configuration.
 *
 * <p>The key determines which bucket to use for rate limiting. The resolution is based on the
 * rule's {@link org.fluxgate.core.config.LimitScope}:
 *
 * <ul>
 *   <li>{@code GLOBAL} - Single bucket for all requests (uses ruleSetId)
 *   <li>{@code PER_IP} - One bucket per client IP address
 *   <li>{@code PER_USER} - One bucket per user ID
 *   <li>{@code PER_API_KEY} - One bucket per API key
 *   <li>{@code CUSTOM} - Custom resolution via attributes
 * </ul>
 *
 * <p>Users can customize the key resolution by providing their own {@link KeyResolver}
 * implementation and setting the appropriate values in {@link RequestContext} via a
 * RequestContextCustomizer (in fluxgate-spring-boot-starter module).
 */
public interface KeyResolver {

  /**
   * Resolves the rate limit key for the given request context and rule.
   *
   * @param context the request context containing client information (IP, userId, apiKey, etc.)
   * @param rule the rate limit rule containing the LimitScope
   * @return the resolved rate limit key
   */
  RateLimitKey resolve(RequestContext context, RateLimitRule rule);
}
