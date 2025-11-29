package org.openfluxgate.core.spi;

import org.openfluxgate.core.ratelimiter.RateLimitRuleSet;

import java.util.Optional;

public interface RateLimitRuleSetProvider {

    /**
     * Returns a RateLimitRuleSet for the given id.
     * Implementations may load from Mongo, YAML, DB, etc.
     */
    Optional<RateLimitRuleSet> findById(String ruleSetId);
}