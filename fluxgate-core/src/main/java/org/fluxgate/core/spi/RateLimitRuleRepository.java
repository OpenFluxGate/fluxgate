package org.fluxgate.core.spi;

import org.fluxgate.core.config.RateLimitRule;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for storing and retrieving rate limit rules.
 * <p>
 * Implementations can use various storage backends:
 * - MongoDB (fluxgate-mongo-adapter)
 * - JDBC/JPA (custom implementation)
 * - In-memory (for testing)
 * - File-based (YAML/JSON)
 * <p>
 * This interface uses core domain objects ({@link RateLimitRule}),
 * not storage-specific documents.
 */
public interface RateLimitRuleRepository {

    /**
     * Find all rules belonging to a specific rule set.
     *
     * @param ruleSetId the rule set identifier
     * @return list of rules (empty if none found)
     */
    List<RateLimitRule> findByRuleSetId(String ruleSetId);

    /**
     * Find a rule by its ID.
     *
     * @param id the rule identifier
     * @return the rule if found
     */
    Optional<RateLimitRule> findById(String id);

    /**
     * Save or update a rule.
     * If a rule with the same ID exists, it will be replaced.
     *
     * @param rule the rule to save
     */
    void save(RateLimitRule rule);

    /**
     * Delete a rule by its ID.
     *
     * @param id the rule identifier
     * @return true if the rule was deleted, false if not found
     */
    boolean deleteById(String id);

    /**
     * Check if a rule exists.
     *
     * @param id the rule identifier
     * @return true if exists
     */
    default boolean existsById(String id) {
        return findById(id).isPresent();
    }

    /**
     * Find all rules.
     *
     * @return list of all rules
     */
    List<RateLimitRule> findAll();

    /**
     * Delete all rules in a rule set.
     *
     * @param ruleSetId the rule set identifier
     * @return number of rules deleted
     */
    int deleteByRuleSetId(String ruleSetId);
}
