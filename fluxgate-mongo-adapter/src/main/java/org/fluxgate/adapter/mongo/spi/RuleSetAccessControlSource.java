package org.fluxgate.adapter.mongo.spi;

import org.fluxgate.core.config.AccessControl;

/**
 * Supplies the rule-set-level {@link AccessControl} that {@code MongoRuleSetProvider} attaches to
 * the rule sets it loads.
 *
 * <p>{@code MongoRateLimitRuleRepository} implements it. A repository decorator (caching, metrics,
 * multi-tenant routing) implements it too - or the provider is given an explicit source - so the
 * access control is not lost just because the repository is no longer the concrete Mongo class.
 *
 * @since 0.4.0
 */
@FunctionalInterface
public interface RuleSetAccessControlSource {

  /**
   * Returns the access control of a rule set.
   *
   * @param ruleSetId the rule set id
   * @return the access control, {@link AccessControl#EMPTY} when the rule set has none (never null)
   * @throws IllegalArgumentException when the stored access control is invalid (e.g. a bad CIDR)
   */
  AccessControl findAccessControlByRuleSetId(String ruleSetId);
}
