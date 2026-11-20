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

  /**
   * Whether every rule comes from authoritative published snapshots carrying counter revision and
   * epoch metadata. This capability is not a global stale-writer barrier. Implementations must also
   * require fresh reads, attach a consistent published revision and epoch to every returned rule,
   * and never fall back to mutable drafts or legacy providers after a missing snapshot or read
   * failure. Ordinary mutable or mixed providers must retain the default false.
   */
  default boolean usesPublishedCounterEpochs() {
    return false;
  }

  /** Whether every read must consult the authoritative policy revision rather than a TTL cache. */
  default boolean requiresFreshRead() {
    return false;
  }
}
