package org.fluxgate.sample.filter.config;

// =============================================================================
// NOTE: This config is for Redis direct mode (RedisRateLimitHandler).
//       Currently using HTTP API mode (HttpRateLimitHandler).
//
// To enable this config:
//   1. Uncomment this class
//   2. Add fluxgate-redis-ratelimiter dependency to pom.xml
//   3. Set fluxgate.redis.enabled=true in application.yml
//   4. Change @EnableFluxgateFilter handler to RedisRateLimitHandler.class
// =============================================================================

/*
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Configuration
public class RuleSetConfig {

    private static final Logger log = LoggerFactory.getLogger(RuleSetConfig.class);

    @Bean
    public RateLimitRuleSetProvider ruleSetProvider(RedisRuleSetStore redisRuleSetStore) {
        log.info("Creating Redis-backed RuleSetProvider for filter sample");

        // Register default rule set if not exists
        if (!redisRuleSetStore.exists("api-limits")) {
            RuleSetData defaultData = new RuleSetData(
                    "api-limits",
                    10,  // 10 requests
                    60,  // per 60 seconds (1 minute)
                    "clientIp"
            );
            redisRuleSetStore.save(defaultData);
            log.info("Registered default RuleSet 'api-limits' in Redis: 10 req/min");
        }

        // KeyResolver that extracts client IP
        KeyResolver ipKeyResolver = context -> {
            String ip = context.getClientIp();
            return new RateLimitKey(ip != null ? ip : "unknown");
        };

        return ruleSetId -> {
            Optional<RuleSetData> dataOpt = redisRuleSetStore.findById(ruleSetId);

            if (dataOpt.isEmpty()) {
                log.warn("RuleSet not found in Redis: {}", ruleSetId);
                return Optional.empty();
            }

            RuleSetData data = dataOpt.get();
            RateLimitRuleSet ruleSet = buildRuleSet(data, ipKeyResolver);

            log.debug("Loaded RuleSet from Redis: {} ({} req per {}s)",
                    data.getRuleSetId(), data.getCapacity(), data.getWindowSeconds());
            return Optional.of(ruleSet);
        };
    }

    private RateLimitRuleSet buildRuleSet(RuleSetData data, KeyResolver keyResolver) {
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

        return RateLimitRuleSet.builder(data.getRuleSetId())
                .rules(List.of(rule))
                .keyResolver(keyResolver)
                .build();
    }
}
*/
