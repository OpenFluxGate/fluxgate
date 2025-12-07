package org.fluxgate.core.spi;

import java.util.Optional;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;

public interface RateLimitRuleSetProvider {

  /**
   * Returns a RateLimitRuleSet for the given id. Implementations may load from Mongo, YAML, DB,
   * etc.
   */
  Optional<RateLimitRuleSet> findById(String ruleSetId);
}
