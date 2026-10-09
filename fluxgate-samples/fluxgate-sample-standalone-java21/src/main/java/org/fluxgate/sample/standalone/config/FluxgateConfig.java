package org.fluxgate.sample.standalone.config;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import org.fluxgate.redis.RedisRateLimiter;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for FluxGate Redis components.
 *
 * <p>MongoDB configuration is handled by FluxgateMongoAutoConfiguration via fluxgate.mongo.*
 * properties.
 *
 * <p>This class only sets up Redis connection for rate limiting.
 */
@Configuration
public class FluxgateConfig {

  private static final Logger log = LoggerFactory.getLogger(FluxgateConfig.class);

  @Value("${fluxgate.redis.uri:redis://localhost:6379}")
  private String redisUri;

  private RedisRateLimiterConfig redisConfig;

  @Bean
  public RedisRateLimiterConfig redisRateLimiterConfig() throws IOException {
    log.info("Connecting to Redis: {}", redisUri);
    this.redisConfig = new RedisRateLimiterConfig(redisUri);
    return this.redisConfig;
  }

  @Bean
  public RedisTokenBucketStore redisTokenBucketStore(RedisRateLimiterConfig config) {
    return config.getTokenBucketStore();
  }

  @Bean
  public RedisRateLimiter redisRateLimiter(RedisTokenBucketStore tokenBucketStore) {
    log.info("Creating Redis RateLimiter");
    return new RedisRateLimiter(tokenBucketStore);
  }

  @PreDestroy
  public void cleanup() {
    log.info("Cleaning up FluxGate resources...");
    if (redisConfig != null) {
      redisConfig.close();
    }
  }
}
