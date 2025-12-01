package org.fluxgate.sample.filter.handler;

// =============================================================================
// NOTE: This handler is for direct Redis access mode.
//       Currently using HttpRateLimitHandler for HTTP API mode.
//
// To enable this handler:
//   1. Uncomment this class
//   2. Add fluxgate-redis-ratelimiter dependency to pom.xml
//   3. Set fluxgate.redis.enabled=true in application.yml
//   4. Change @EnableFluxgateFilter handler to RedisRateLimitHandler.class
// =============================================================================

/*
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class RedisRateLimitHandler implements FluxgateRateLimitHandler {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitHandler.class);

    private final RateLimiter rateLimiter;
    private final RateLimitRuleSetProvider ruleSetProvider;

    public RedisRateLimitHandler(
            RateLimiter rateLimiter,
            RateLimitRuleSetProvider ruleSetProvider) {
        this.rateLimiter = rateLimiter;
        this.ruleSetProvider = ruleSetProvider;
        log.info("RedisRateLimitHandler initialized with direct Redis access");
    }

    @Override
    public RateLimitResponse tryConsume(RequestContext context, String ruleSetId) {
        // Load rule set
        Optional<RateLimitRuleSet> ruleSetOpt = ruleSetProvider.findById(ruleSetId);

        if (ruleSetOpt.isEmpty()) {
            log.warn("RuleSet not found: {}, allowing request", ruleSetId);
            return RateLimitResponse.allowed(-1, 0);
        }

        RateLimitRuleSet ruleSet = ruleSetOpt.get();

        // Execute rate limiting
        RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

        // Convert to RateLimitResponse
        if (result.isAllowed()) {
            return RateLimitResponse.allowed(
                    result.getRemainingTokens(),
                    result.getNanosToWaitForRefill() / 1_000_000
            );
        } else {
            return RateLimitResponse.rejected(
                    result.getNanosToWaitForRefill() / 1_000_000
            );
        }
    }
}
*/
