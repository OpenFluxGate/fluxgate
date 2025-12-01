package org.fluxgate.adapter.mongo.rule;

import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB-backed RuleSet provider.
 * <p>
 * Uses {@link RateLimitRuleRepository} interface, allowing for different
 * storage implementations (MongoDB, JDBC, etc.).
 */
public class MongoRuleSetProvider implements RateLimitRuleSetProvider {

    private final RateLimitRuleRepository ruleRepository;
    private final KeyResolver keyResolver;

    public MongoRuleSetProvider(RateLimitRuleRepository ruleRepository,
                                KeyResolver keyResolver) {
        this.ruleRepository = ruleRepository;
        this.keyResolver = keyResolver;
    }

    @Override
    public Optional<RateLimitRuleSet> findById(String ruleSetId) {
        List<RateLimitRule> rules = ruleRepository.findByRuleSetId(ruleSetId);

        if (rules.isEmpty()) {
            return Optional.empty();
        }

        RateLimitRuleSet ruleSet = RateLimitRuleSet.builder(ruleSetId)
                .keyResolver(keyResolver)
                .rules(rules)
                .build();

        return Optional.of(ruleSet);
    }
}
