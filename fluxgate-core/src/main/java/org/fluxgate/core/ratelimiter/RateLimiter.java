package org.fluxgate.core.ratelimiter;

import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.match.PathPatternMatcher;

/**
 * Central abstraction for rate limiting engine.
 *
 * <p>Implementations are expected to be thread-safe.
 */
public interface RateLimiter {

  /**
   * Try to consume a single permit for the given context and rule set.
   *
   * @param context request-scoped information (IP, userId, path, etc.)
   * @param ruleSet rule set to apply
   * @return the rate limit result
   */
  default RateLimitResult tryConsume(RequestContext context, RateLimitRuleSet ruleSet) {
    return tryConsume(context, ruleSet, 1L);
  }

  /**
   * Try to consume the specified number of permits.
   *
   * @param context request-scoped information (IP, userId, path, etc.)
   * @param ruleSet rule set to apply
   * @param permits number of permits to consume
   * @return the rate limit result
   */
  RateLimitResult tryConsume(RequestContext context, RateLimitRuleSet ruleSet, long permits);

  /**
   * Try to consume the specified number of permits, using the supplied path matcher to filter
   * applicable rules via {@link RateLimitRuleSet#getMatchingRules}.
   *
   * <p>Implementations may override this method to honour the matcher directly (for example to
   * iterate only the rules returned by {@code ruleSet.getMatchingRules(context, pathMatcher)}). The
   * default delegates to {@link #tryConsume(RequestContext, RateLimitRuleSet, long)}, which ignores
   * the matcher.
   *
   * @param context request-scoped information (IP, userId, path, etc.)
   * @param ruleSet rule set to apply
   * @param permits number of permits to consume
   * @param pathMatcher the path pattern matcher used to filter rules
   * @return the rate limit result
   * @since 0.4.0
   */
  default RateLimitResult tryConsume(
      RequestContext context,
      RateLimitRuleSet ruleSet,
      long permits,
      PathPatternMatcher pathMatcher) {
    return tryConsume(context, ruleSet, permits);
  }
}
