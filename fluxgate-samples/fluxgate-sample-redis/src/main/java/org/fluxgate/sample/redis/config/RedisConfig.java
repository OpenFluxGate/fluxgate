package org.fluxgate.sample.redis.config;

import java.io.IOException;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.redis.RedisRateLimiter;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.store.RedisRuleSetStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Redis configuration for Data-plane. Creates RateLimiter and RuleSetStore beans. */
@Configuration
public class RedisConfig {

  private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

  @Value("${fluxgate.redis.uri:redis://localhost:6379}")
  private String redisUri;

  @Bean
  public RedisRateLimiterConfig redisRateLimiterConfig() throws IOException {
    log.info("Creating RedisRateLimiterConfig with URI: {}", redisUri);
    return new RedisRateLimiterConfig(redisUri);
  }

  @Bean
  public RateLimiter rateLimiter(RedisRateLimiterConfig config) {
    log.info("Creating RedisRateLimiter");
    return new RedisRateLimiter(config.getTokenBucketStore());
  }

  @Bean
  public RedisRuleSetStore redisRuleSetStore(RedisRateLimiterConfig config) {
    log.info("Creating RedisRuleSetStore");
    return config.getRuleSetStore();
  }
}
