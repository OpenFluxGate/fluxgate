package org.fluxgate.core.spi;

import java.util.Optional;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;

/**
 * Service Provider Interface for loading RateLimitRuleSet instances.
 *
 * <p>Implementations may load rule sets from various sources such as MongoDB, YAML files,
 * databases, or in-memory configurations.
 */
public interface RateLimitRuleSetProvider {

  /**
   * Returns a RateLimitRuleSet for the given id. Implementations may load from Mongo, YAML, DB,
   * etc.
   *
   * @param ruleSetId the unique identifier for the rule set
   * @return an Optional containing the RateLimitRuleSet if found, or empty if not found
   */
  Optional<RateLimitRuleSet> findById(String ruleSetId);
}
