package org.fluxgate.spring.autoconfigure;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.fluxgate.adapter.mongo.event.MongoRateLimitMetricsRecorder;
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.adapter.mongo.rule.MongoRuleSetProvider;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for FluxGate MongoDB integration.
 *
 * <p>This configuration is enabled only when:
 *
 * <ul>
 *   <li>{@code fluxgate.mongo.enabled=true}
 *   <li>MongoDB driver classes are on the classpath
 * </ul>
 *
 * <p>Creates beans for:
 *
 * <ul>
 *   <li>{@link MongoClient} - MongoDB connection
 *   <li>{@link MongoDatabase} - FluxGate database
 *   <li>{@link MongoRateLimitRuleRepository} - Rule CRUD operations
 *   <li>{@link MongoRuleSetProvider} - Rule set loading
 * </ul>
 *
 * <p>This configuration does NOT create Redis or Filter beans. It can run independently for
 * control-plane deployments.
 *
 * @see FluxgateRedisAutoConfiguration
 * @see FluxgateFilterAutoConfiguration
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "fluxgate.mongo", name = "enabled", havingValue = "true")
@ConditionalOnClass(name = "com.mongodb.client.MongoClient")
@EnableConfigurationProperties(FluxgateProperties.class)
public class FluxgateMongoAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(FluxgateMongoAutoConfiguration.class);

  private final FluxgateProperties properties;

  public FluxgateMongoAutoConfiguration(FluxgateProperties properties) {
    this.properties = properties;
  }

  /**
   * Creates a MongoClient for connecting to MongoDB.
   *
   * <p>Only created if no existing MongoClient bean is present.
   */
  @Bean(name = "fluxgateMongoClient", destroyMethod = "close")
  @ConditionalOnMissingBean(name = "fluxgateMongoClient")
  public MongoClient fluxgateMongoClient() {
    String uri = properties.getMongo().getUri();
    log.info("Creating FluxGate MongoClient with URI: {}", maskUri(uri));
    return MongoClients.create(uri);
  }

  /** Creates a MongoDatabase for FluxGate collections. */
  @Bean(name = "fluxgateMongoDatabase")
  @ConditionalOnMissingBean(name = "fluxgateMongoDatabase")
  public MongoDatabase fluxgateMongoDatabase(MongoClient fluxgateMongoClient) {
    String database = properties.getMongo().getDatabase();
    log.info("Using FluxGate database: {}", database);
    return fluxgateMongoClient.getDatabase(database);
  }

  /** Creates the rate limit rules collection. */
  @Bean(name = "fluxgateRuleCollection")
  @ConditionalOnMissingBean(name = "fluxgateRuleCollection")
  public MongoCollection<Document> fluxgateRuleCollection(MongoDatabase fluxgateMongoDatabase) {
    String collectionName = properties.getMongo().getRuleCollection();
    log.info("Using FluxGate rule collection: {}", collectionName);
    return fluxgateMongoDatabase.getCollection(collectionName);
  }

  /**
   * Creates the RateLimitRuleRepository for rule CRUD operations.
   *
   * <p>Uses MongoDB implementation. Users can provide their own implementation by defining a bean
   * of type {@link RateLimitRuleRepository}.
   */
  @Bean
  @ConditionalOnMissingBean(RateLimitRuleRepository.class)
  public RateLimitRuleRepository rateLimitRuleRepository(
      @Qualifier("fluxgateRuleCollection") MongoCollection<Document> fluxgateRuleCollection) {
    log.info("Creating MongoRateLimitRuleRepository (implements RateLimitRuleRepository)");
    return new MongoRateLimitRuleRepository(fluxgateRuleCollection);
  }

  /**
   * Creates a default KeyResolver that extracts client IP.
   *
   * <p>Users can override this bean with a custom KeyResolver.
   */
  @Bean(name = "fluxgateKeyResolver")
  @ConditionalOnMissingBean(KeyResolver.class)
  public KeyResolver fluxgateKeyResolver() {
    log.info("Creating default KeyResolver (extracts client IP)");
    return context -> new RateLimitKey(context.getClientIp());
  }

  /** Creates the MongoRuleSetProvider for loading rule sets from MongoDB. */
  @Bean
  @ConditionalOnMissingBean(RateLimitRuleSetProvider.class)
  public RateLimitRuleSetProvider mongoRuleSetProvider(
      RateLimitRuleRepository repository, KeyResolver fluxgateKeyResolver) {
    log.info("Creating MongoRuleSetProvider");
    return new MongoRuleSetProvider(repository, fluxgateKeyResolver);
  }

  /**
   * Creates the event collection for rate limit event logging.
   *
   * <p>Uses the collection name from {@code fluxgate.mongo.event-collection}.
   */
  @Bean(name = "fluxgateEventCollection")
  @ConditionalOnMissingBean(name = "fluxgateEventCollection")
  public MongoCollection<Document> fluxgateEventCollection(MongoDatabase fluxgateMongoDatabase) {
    String collectionName = properties.getMongo().getEventCollection();
    log.info("Using FluxGate event collection: {}", collectionName);
    return fluxgateMongoDatabase.getCollection(collectionName);
  }

  /**
   * Creates the RateLimitMetricsRecorder for logging rate limit events to MongoDB.
   *
   * <p>This recorder stores all rate limit decisions (allowed/denied) to MongoDB for auditing and
   * analytics purposes.
   */
  @Bean
  @ConditionalOnMissingBean(RateLimitMetricsRecorder.class)
  public RateLimitMetricsRecorder rateLimitMetricsRecorder(
      @Qualifier("fluxgateEventCollection") MongoCollection<Document> fluxgateEventCollection) {
    log.info("Creating MongoRateLimitMetricsRecorder for event logging");
    return new MongoRateLimitMetricsRecorder(fluxgateEventCollection);
  }

  /** Masks sensitive parts of the URI for logging. */
  private String maskUri(String uri) {
    if (uri == null) {
      return "null";
    }
    // Simple masking: hide password
    return uri.replaceAll("://([^:]+):([^@]+)@", "://$1:****@");
  }
}
