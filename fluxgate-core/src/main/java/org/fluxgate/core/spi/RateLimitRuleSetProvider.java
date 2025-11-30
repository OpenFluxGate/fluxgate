package org.fluxgate.core.spi;

import org.fluxgate.core.ratelimiter.RateLimitRuleSet;

import java.util.Optional;

public interface RateLimitRuleSetProvider {

    /**
     * Returns a RateLimitRuleSet for the given id.
     * Implementations may load from Mongo, YAML, DB, etc.
     */
    Optional<RateLimitRuleSet> findById(String ruleSetId);
}