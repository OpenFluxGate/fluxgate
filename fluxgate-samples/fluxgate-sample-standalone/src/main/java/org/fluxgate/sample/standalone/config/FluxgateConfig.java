package org.fluxgate.sample.standalone.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import org.bson.Document;
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.adapter.mongo.rule.MongoRuleSetProvider;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.redis.RedisRateLimiter;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for FluxGate components.
 *
 * <p>Sets up: - MongoDB connection for rule storage - Redis connection for rate limiting - FluxGate
 * components (RateLimiter, RuleSetProvider)
 */
@Configuration
public class FluxgateConfig {

  private static final Logger log = LoggerFactory.getLogger(FluxgateConfig.class);

  @Value(
      "${fluxgate.mongodb.uri:mongodb://fluxgate:fluxgate123@localhost:27017/fluxgate?authSource=admin}")
  private String mongoUri;

  @Value("${fluxgate.mongodb.database:fluxgate}")
  private String mongoDatabase;

  @Value("${fluxgate.redis.uri:redis://localhost:6379}")
  private String redisUri;

  private MongoClient mongoClient;
  private RedisRateLimiterConfig redisConfig;

  @Bean
  public MongoClient mongoClient() {
    log.info("Connecting to MongoDB: {}", mongoUri);
    this.mongoClient = MongoClients.create(mongoUri);
    return this.mongoClient;
  }

  @Bean
  public MongoDatabase mongoDatabase(MongoClient mongoClient) {
    log.info("Using MongoDB database: {}", mongoDatabase);
    return mongoClient.getDatabase(mongoDatabase);
  }

  @Bean
  public MongoCollection<Document> rateLimitRulesCollection(MongoDatabase database) {
    return database.getCollection("rate_limit_rules");
  }

  @Bean
  public RateLimitRuleRepository ruleRepository(
      MongoCollection<Document> rateLimitRulesCollection) {
    log.info("Creating MongoDB RuleRepository");
    return new MongoRateLimitRuleRepository(rateLimitRulesCollection);
  }

  @Bean
  public KeyResolver keyResolver() {
    // Simple IP-based key resolver
    return context -> new RateLimitKey(context.getClientIp());
  }

  @Bean
  public RateLimitRuleSetProvider ruleSetProvider(
      RateLimitRuleRepository ruleRepository, KeyResolver keyResolver) {
    log.info("Creating MongoDB RuleSetProvider");
    return new MongoRuleSetProvider(ruleRepository, keyResolver);
  }

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
    if (mongoClient != null) {
      mongoClient.close();
    }
  }
}
