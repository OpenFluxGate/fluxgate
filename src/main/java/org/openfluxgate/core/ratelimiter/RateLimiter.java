package org.openfluxgate.core.ratelimiter;

import org.openfluxgate.core.context.RequestContext;

/**
 * Central abstraction for rate limiting engine.
 * <p>
 * Implementations are expected to be thread-safe.
 */
public interface RateLimiter {

    /**
     * Try to consume a single permit for the given context and rule set.
     */
    default RateLimitResult tryConsume(RequestContext context,
                                       RateLimitRuleSet ruleSet) {
        return tryConsume(context, ruleSet, 1L);
    }

    /**
     * Try to consume the specified number of permits.
     *
     * @param context request-scoped information (IP, userId, path, etc.)
     * @param ruleSet rule set to apply
     * @param permits number of permits to consume
     */
    RateLimitResult tryConsume(RequestContext context,
                               RateLimitRuleSet ruleSet,
                               long permits);
}