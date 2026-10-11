package org.fluxgate.spring.rule;

import java.util.Optional;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RateLimitRuleSetProvider} that composes a properties-defined provider with a MongoDB
 * fallback.
 *
 * <p>Lookup order:
 *
 * <ol>
 *   <li>The <em>primary</em> provider (typically {@link PropertiesRuleSetProvider}) is tried first.
 *   <li>If the primary returns empty the <em>delegate</em> provider (typically the MongoDB-backed
 *       one) is consulted.
 * </ol>
 *
 * <p>When rule sets are declared under {@code fluxgate.ratelimit.rule-sets}, the starter replaces
 * the {@code delegateRuleSetProvider} bean (from the MongoDB auto-configuration or the application)
 * with this composite, so it is the single provider bean and hot reload wraps it.
 *
 * @since 0.4.0
 */
public final class CompositeRuleSetProvider implements RateLimitRuleSetProvider {

  private static final Logger log = LoggerFactory.getLogger(CompositeRuleSetProvider.class);

  private final RateLimitRuleSetProvider primary;
  private final RateLimitRuleSetProvider delegate;

  /**
   * Creates a composite provider.
   *
   * @param primary the primary (properties-based) provider consulted first
   * @param delegate the fallback (MongoDB-backed) provider
   */
  public CompositeRuleSetProvider(
      RateLimitRuleSetProvider primary, RateLimitRuleSetProvider delegate) {
    this.primary = primary;
    this.delegate = delegate;
  }

  @Override
  public boolean requiresFreshRead() {
    return primary.requiresFreshRead() || delegate.requiresFreshRead();
  }

  @Override
  public Optional<RateLimitRuleSet> findById(String ruleSetId) {
    Optional<RateLimitRuleSet> result = primary.findById(ruleSetId);
    if (result.isPresent()) {
      log.debug("Rule set '{}' resolved from properties", ruleSetId);
      return result;
    }
    log.debug("Rule set '{}' not in properties — falling back to delegate provider", ruleSetId);
    return delegate.findById(ruleSetId);
  }
}
