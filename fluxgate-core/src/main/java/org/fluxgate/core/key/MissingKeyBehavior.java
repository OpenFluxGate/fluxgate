package org.fluxgate.core.key;

import org.fluxgate.core.config.LimitScope;

/**
 * Behaviour applied by {@link LimitScopeKeyResolver} when the value required by a rule's {@link
 * LimitScope} is absent from the request context.
 *
 * <p>Starters expose this as {@code fluxgate.ratelimit.missing-key-behavior}.
 */
public enum MissingKeyBehavior {

  /**
   * Fall back to the client IP. The resulting key carries the {@code ip:} prefix, so an anonymous
   * request can never share a bucket with a user or API key whose identifier happens to look like
   * an IP address.
   *
   * <p>Note that the rule's limits still apply unchanged, so an anonymous caller inherits the quota
   * configured for the authenticated tier. Use {@link #REJECT} when that is not acceptable.
   */
  FALLBACK_TO_IP,

  /**
   * Reject the request. The resolver throws {@link
   * org.fluxgate.core.exception.MissingRateLimitKeyException}; rate limiter implementations catch
   * it and return a rejected result.
   */
  REJECT
}
