package org.fluxgate.sample.standalone.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import org.bson.Document;
import org.fluxgate.adapter.mongo.event.MongoRateLimitMetricsRecorder;
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.adapter.mongo.rule.MongoRuleSetProvider;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.redis.RedisRateLimiter;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

  @Value("${fluxgate.mongodb.rule-collection:rate_limit_rules}")
  private String ruleCollection;

  @Value("${fluxgate.mongodb.event-collection:#{null}}")
  private String eventCollection;

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
    log.info("Using rule collection: {}", ruleCollection);
    return database.getCollection(ruleCollection);
  }

  /**
   * Creates the event collection for rate limit event logging.
   *
   * <p>Only created when fluxgate.mongodb.event-collection is configured.
   */
  @Bean
  @ConditionalOnProperty("fluxgate.mongodb.event-collection")
  public MongoCollection<Document> rateLimitEventsCollection(MongoDatabase database) {
    log.info("Using event collection: {}", eventCollection);
    return database.getCollection(eventCollection);
  }

  /**
   * Creates the RateLimitMetricsRecorder for logging rate limit events to MongoDB.
   *
   * <p>Only created when event-collection is configured. This recorder stores all rate limit
   * decisions (allowed/denied) to MongoDB for auditing and analytics purposes.
   */
  @Bean
  @ConditionalOnProperty("fluxgate.mongodb.event-collection")
  public RateLimitMetricsRecorder rateLimitMetricsRecorder(
      @Qualifier("rateLimitEventsCollection") MongoCollection<Document> eventsCollection) {
    log.info("Creating MongoRateLimitMetricsRecorder for event logging");
    return new MongoRateLimitMetricsRecorder(eventsCollection);
  }

  @Bean
  public RateLimitRuleRepository ruleRepository(
      @Qualifier("rateLimitRulesCollection") MongoCollection<Document> rulesCollection) {
    log.info("Creating MongoDB RuleRepository");
    return new MongoRateLimitRuleRepository(rulesCollection);
  }

  @Bean
  public KeyResolver keyResolver() {
    // Simple IP-based key resolver
    return context -> new RateLimitKey(context.getClientIp());
  }

  @Bean
  public RateLimitRuleSetProvider ruleSetProvider(
      RateLimitRuleRepository ruleRepository,
      KeyResolver keyResolver,
      @Autowired(required = false) RateLimitMetricsRecorder metricsRecorder) {
    if (metricsRecorder != null) {
      log.info("Creating MongoDB RuleSetProvider with metrics recording enabled");
      return new MongoRuleSetProvider(ruleRepository, keyResolver, metricsRecorder);
    }
    log.info("Creating MongoDB RuleSetProvider without metrics recording");
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
