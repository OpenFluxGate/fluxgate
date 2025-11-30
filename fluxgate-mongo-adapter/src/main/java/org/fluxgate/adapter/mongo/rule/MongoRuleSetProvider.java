package org.fluxgate.adapter.mongo.rule;

import org.fluxgate.adapter.mongo.converter.RateLimitRuleMongoConverter;
import org.fluxgate.adapter.mongo.model.RateLimitRuleDocument;
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class MongoRuleSetProvider implements RateLimitRuleSetProvider {

    private final MongoRateLimitRuleRepository ruleRepository;
    private final KeyResolver keyResolver;

    public MongoRuleSetProvider(MongoRateLimitRuleRepository ruleRepository,
                                KeyResolver keyResolver) {
        this.ruleRepository = ruleRepository;
        this.keyResolver = keyResolver;
    }

    @Override
    public Optional<RateLimitRuleSet> findById(String ruleSetId) {
        List<RateLimitRuleDocument> docs = ruleRepository.findByRuleSetId(ruleSetId);

        if (docs.isEmpty()) {
            return Optional.empty();
        }

        List<RateLimitRule> rules = new ArrayList<>();
        for (RateLimitRuleDocument doc : docs) {
            rules.add(RateLimitRuleMongoConverter.toDomain(doc));
        }

        RateLimitRuleSet ruleSet = RateLimitRuleSet.builder(ruleSetId)
                .keyResolver(keyResolver)
                .rules(rules)
                .build();

        return Optional.of(ruleSet);
    }
}
