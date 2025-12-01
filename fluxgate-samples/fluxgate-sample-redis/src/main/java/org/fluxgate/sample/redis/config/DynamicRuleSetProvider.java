package org.fluxgate.sample.redis.config;

import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.redis.store.RedisRuleSetStore;
import org.fluxgate.redis.store.RuleSetData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Dynamic RuleSetProvider backed by Redis.
 * RuleSets are stored in Redis and persist across app restarts.
 */
@Component
public class DynamicRuleSetProvider implements RateLimitRuleSetProvider {

    private static final Logger log = LoggerFactory.getLogger(DynamicRuleSetProvider.class);

    private final RedisRuleSetStore ruleSetStore;

    public DynamicRuleSetProvider(RedisRuleSetStore ruleSetStore) {
        this.ruleSetStore = ruleSetStore;
        log.info("DynamicRuleSetProvider initialized with Redis backing store");

        // Register default rule set if not exists
        if (!ruleSetStore.exists("api-limits")) {
            registerDefaultRuleSet();
        }
    }

    private void registerDefaultRuleSet() {
        RuleSetData defaultData = new RuleSetData(
                "api-limits",
                10,  // 10 requests
                60,  // per 60 seconds
                "clientIp"
        );
        ruleSetStore.save(defaultData);
        log.info("Registered default RuleSet 'api-limits' in Redis");
    }

    @Override
    public Optional<RateLimitRuleSet> findById(String ruleSetId) {
        Optional<RuleSetData> dataOpt = ruleSetStore.findById(ruleSetId);

        if (dataOpt.isEmpty()) {
            log.warn("RuleSet not found in Redis: {}", ruleSetId);
            return Optional.empty();
        }

        RuleSetData data = dataOpt.get();
        RateLimitRuleSet ruleSet = buildRuleSet(data);

        log.debug("Loaded RuleSet from Redis: {}", data);
        return Optional.of(ruleSet);
    }

    /**
     * Register a new RuleSet in Redis.
     */
    public void registerRuleSet(String ruleSetId, long capacity, long windowSeconds) {
        RuleSetData data = new RuleSetData(ruleSetId, capacity, windowSeconds, "clientIp");
        ruleSetStore.save(data);
        log.info("Registered RuleSet in Redis: {} ({} requests per {} seconds)", ruleSetId, capacity, windowSeconds);
    }

    /**
     * Remove a RuleSet from Redis.
     */
    public boolean removeRuleSet(String ruleSetId) {
        boolean removed = ruleSetStore.delete(ruleSetId);
        if (removed) {
            log.info("Removed RuleSet from Redis: {}", ruleSetId);
        }
        return removed;
    }

    /**
     * List all registered RuleSet IDs from Redis.
     */
    public List<String> listRuleSetIds() {
        return ruleSetStore.listAllIds();
    }

    /**
     * Clear all RuleSets from Redis and restore default.
     */
    public void clearAll() {
        ruleSetStore.clearAll();
        registerDefaultRuleSet();
        log.info("Cleared all RuleSets from Redis, restored default");
    }

    /**
     * Build a RateLimitRuleSet from RuleSetData.
     */
    private RateLimitRuleSet buildRuleSet(RuleSetData data) {
        RateLimitRule rule = RateLimitRule.builder(data.getRuleSetId() + "-rule")
                .name("Dynamic Rule - " + data.getCapacity() + " per " + data.getWindowSeconds() + "s")
                .enabled(true)
                .ruleSetId(data.getRuleSetId())
                .scope(LimitScope.PER_IP)
                .keyStrategyId(data.getKeyStrategyId())
                .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
                .addBand(RateLimitBand.builder(Duration.ofSeconds(data.getWindowSeconds()), data.getCapacity())
                        .label(data.getCapacity() + "-per-" + data.getWindowSeconds() + "s")
                        .build())
                .build();

        KeyResolver keyResolver = context -> {
            String ip = context.getClientIp();
            return new RateLimitKey(ip != null ? ip : "unknown");
        };

        return RateLimitRuleSet.builder(data.getRuleSetId())
                .rules(List.of(rule))
                .keyResolver(keyResolver)
                .build();
    }
}
