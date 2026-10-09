package org.fluxgate.spring.autoconfigure;

import com.mongodb.ConnectionString;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.fluxgate.adapter.mongo.event.MongoRateLimitMetricsRecorder;
import org.fluxgate.adapter.mongo.health.MongoHealthCheckerImpl;
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.adapter.mongo.rule.MongoRuleSetProvider;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.key.MissingKeyBehavior;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.actuator.FluxgateHealthIndicator.HealthStatus;
import org.fluxgate.spring.actuator.FluxgateHealthIndicator.MongoHealthChecker;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.fluxgate.spring.rule.CompositeRuleSetProvider;
import org.fluxgate.spring.rule.PropertiesRuleSetProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

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
    FluxgateProperties.DdlAuto ddlAuto = properties.getMongo().getDdlAuto();

    if (ddlAuto == FluxgateProperties.DdlAuto.CREATE) {
      createCollectionIfNotExists(fluxgateMongoDatabase, collectionName);
    } else {
      validateCollectionExists(fluxgateMongoDatabase, collectionName);
    }

    log.info("Using FluxGate rule collection: {}", collectionName);
    MongoCollection<Document> collection = fluxgateMongoDatabase.getCollection(collectionName);

    if (ddlAuto == FluxgateProperties.DdlAuto.CREATE) {
      createRuleIndexes(collection);
    }

    return collection;
  }

  /**
   * Creates the indexes the rule lookups depend on.
   *
   * <p>Every request resolves its rule set through {@code findByRuleSetId}, so without an index on
   * {@code ruleSetId} that lookup is a collection scan on the hot path. The compound {@code
   * {ruleSetId, id}} index is unique because a rule id must not appear twice inside one rule set.
   */
  private void createRuleIndexes(MongoCollection<Document> collection) {
    try {
      String ruleSetIdIndex = collection.createIndex(Indexes.ascending("ruleSetId"));
      String uniqueIndex =
          collection.createIndex(
              Indexes.ascending("ruleSetId", "id"),
              new IndexOptions().unique(true).name("ruleSetId_1_id_1_unique"));
      log.info("Ensured FluxGate rule indexes: {}, {}", ruleSetIdIndex, uniqueIndex);
    } catch (MongoException e) {
      // Duplicate rules or a conflicting existing index must not stop the application: the rules
      // still load, only more slowly.
      log.warn(
          "Could not create the FluxGate rule indexes. Rule lookups fall back to a collection "
              + "scan; create {{ruleSetId: 1}} manually. Cause: {}",
          e.getMessage());
    }
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
   * Creates a default KeyResolver that resolves keys based on the rule's LimitScope.
   *
   * <p>The default implementation {@link org.fluxgate.core.key.LimitScopeKeyResolver} uses:
   *
   * <ul>
   *   <li>GLOBAL - Single bucket for all requests
   *   <li>PER_IP - One bucket per client IP address
   *   <li>PER_USER - One bucket per user ID (from RequestContext.userId)
   *   <li>PER_API_KEY - One bucket per API key (from RequestContext.apiKey)
   *   <li>CUSTOM - Custom resolution via attributes
   * </ul>
   *
   * <p>The behavior when the value a scope needs is missing from the request comes from {@code
   * fluxgate.ratelimit.missing-key-behavior}: {@code FALLBACK_TO_IP} (default) limits by client IP
   * instead, {@code REJECT} rejects the request rather than limiting a broader key.
   *
   * <p>Users can override this bean with a custom KeyResolver.
   */
  @Bean(name = "fluxgateKeyResolver")
  @ConditionalOnMissingBean(KeyResolver.class)
  public KeyResolver fluxgateKeyResolver() {
    MissingKeyBehavior missingKeyBehavior = properties.getRatelimit().getMissingKeyBehavior();
    log.info("Creating default LimitScopeKeyResolver (missingKeyBehavior={})", missingKeyBehavior);
    return new LimitScopeKeyResolver(missingKeyBehavior);
  }

  /**
   * Creates the MongoRuleSetProvider for loading rule sets from MongoDB.
   *
   * <p>Uses lazy initialization via ObjectProvider to ensure the metricsRecorder (which may be a
   * CompositeMetricsRecorder) is fully initialized before being injected.
   *
   * <p>Available metrics recorder implementations:
   *
   * <ul>
   *   <li>{@code MicrometerMetricsRecorder} - Created when fluxgate.metrics.enabled=true
   *   <li>{@code MongoRateLimitMetricsRecorder} - Created when event-collection is configured
   *   <li>{@code CompositeMetricsRecorder} - Wraps multiple recorders when both are enabled
   * </ul>
   *
   * @param repository the rule repository for fetching rate limit rules
   * @param fluxgateKeyResolver the key resolver for generating rate limit keys
   * @param metricsRecorderProvider lazy provider for composite metrics recorder
   * @return configured MongoRuleSetProvider instance
   */
  @Bean(name = "delegateRuleSetProvider")
  @ConditionalOnMissingBean(name = "delegateRuleSetProvider")
  public RateLimitRuleSetProvider mongoRuleSetProvider(
      RateLimitRuleRepository repository,
      KeyResolver fluxgateKeyResolver,
      ObjectProvider<RateLimitMetricsRecorder> metricsRecorderProvider) {
    // Use a wrapper that lazily retrieves the recorder at runtime
    // This ensures CompositeMetricsRecorder is available even if created later
    log.info("Creating MongoRuleSetProvider with lazy metrics recorder injection");
    return new LazyMetricsMongoRuleSetProvider(
        repository, fluxgateKeyResolver, metricsRecorderProvider);
  }

  /**
   * Creates the event collection for rate limit event logging.
   *
   * <p>Only created when {@code fluxgate.mongo.event-collection} is configured.
   */
  @Bean(name = "fluxgateEventCollection")
  @ConditionalOnMissingBean(name = "fluxgateEventCollection")
  @ConditionalOnProperty(prefix = "fluxgate.mongo", name = "event-collection")
  public MongoCollection<Document> fluxgateEventCollection(MongoDatabase fluxgateMongoDatabase) {
    String collectionName = properties.getMongo().getEventCollection();
    FluxgateProperties.DdlAuto ddlAuto = properties.getMongo().getDdlAuto();

    if (ddlAuto == FluxgateProperties.DdlAuto.CREATE) {
      createCollectionIfNotExists(fluxgateMongoDatabase, collectionName);
    } else {
      validateCollectionExists(fluxgateMongoDatabase, collectionName);
    }

    log.info("Using FluxGate event collection: {}", collectionName);
    MongoCollection<Document> collection = fluxgateMongoDatabase.getCollection(collectionName);
    createEventRetentionIndex(collection);

    return collection;
  }

  /**
   * Creates the TTL index that expires recorded rate limit events.
   *
   * <p>Created regardless of {@code ddl-auto}: retention is a data protection control, not a schema
   * convenience. Every event document carries a client IP, a user id and an API key fingerprint, so
   * an event collection without a retention window turns any old backup into a lasting disclosure -
   * and it grows until the disk does not.
   *
   * <p>The index is on {@code createdAt}, the BSON date {@code MongoRateLimitMetricsRecorder}
   * writes; MongoDB's TTL monitor ignores an index on a string, so indexing {@code timestampIso}
   * would have looked configured and expired nothing.
   */
  private void createEventRetentionIndex(MongoCollection<Document> collection) {
    Duration retention = properties.getMongo().getEventRetention();
    if (retention == null || retention.isZero() || retention.isNegative()) {
      log.warn(
          "fluxgate.mongo.event-retention is disabled: the event collection grows without bound and "
              + "keeps client IPs, user ids and API key fingerprints forever. Expire the documents "
              + "yourself, or set a retention period.");
      return;
    }

    try {
      String indexName =
          collection.createIndex(
              Indexes.ascending("createdAt"),
              new IndexOptions()
                  .name("createdAt_ttl")
                  .expireAfter(retention.getSeconds(), TimeUnit.SECONDS));
      log.info("Ensured FluxGate event retention index {} (retention={})", indexName, retention);
    } catch (MongoException e) {
      // A conflicting index (usually one created with a different retention) must not stop the
      // application, but it does mean nothing is expiring.
      log.warn(
          "Could not create the FluxGate event retention index on 'createdAt'. Events are NOT being "
              + "expired; drop the conflicting index or expire the documents yourself. Cause: {}",
          e.getMessage());
    }
  }

  /**
   * Creates the MongoRateLimitMetricsRecorder for logging rate limit events to MongoDB.
   *
   * <p>Only created when event-collection is configured. This bean is named explicitly to allow
   * multiple RateLimitMetricsRecorder implementations to coexist. The CompositeMetricsRecorder will
   * collect all available recorders.
   *
   * @param fluxgateEventCollection the MongoDB collection for storing events
   * @return configured MongoRateLimitMetricsRecorder
   */
  @Bean(name = "mongoMetricsRecorder")
  @ConditionalOnProperty(prefix = "fluxgate.mongo", name = "event-collection")
  public MongoRateLimitMetricsRecorder mongoMetricsRecorder(
      @Qualifier("fluxgateEventCollection") MongoCollection<Document> fluxgateEventCollection) {
    log.info("Creating MongoRateLimitMetricsRecorder for MongoDB event logging");
    return new MongoRateLimitMetricsRecorder(fluxgateEventCollection);
  }

  /**
   * Promotes the properties-based provider to the primary {@link RateLimitRuleSetProvider} when
   * both a properties-backed and the Mongo-backed provider exist in the context.
   *
   * <p>The composite tries the properties provider first. If the rule set id is not found there it
   * falls back to the Mongo delegate. This lets operators define some rule sets in YAML (fast, no
   * round-trip to MongoDB) and leave other rule sets in MongoDB without any code changes.
   *
   * <p>Only registered when a {@link PropertiesRuleSetProvider} bean is present (which requires
   * {@code fluxgate.ratelimit.rule-sets} to be non-empty), so a pure-Mongo deployment is
   * unaffected.
   *
   * @param propertiesProvider the YAML-backed provider
   * @param delegateProvider the Mongo-backed delegate registered as {@code delegateRuleSetProvider}
   * @return the composite provider, which replaces the plain Mongo provider as the primary
   */
  @Bean
  @Primary
  @ConditionalOnBean(name = "propertiesRuleSetProvider")
  @ConditionalOnMissingBean(CompositeRuleSetProvider.class)
  public RateLimitRuleSetProvider compositeRuleSetProvider(
      @Qualifier("propertiesRuleSetProvider") PropertiesRuleSetProvider propertiesProvider,
      @Qualifier("delegateRuleSetProvider") RateLimitRuleSetProvider delegateProvider) {
    log.info(
        "Creating CompositeRuleSetProvider: properties={} rule set(s) + Mongo fallback",
        propertiesProvider.size());
    return new CompositeRuleSetProvider(propertiesProvider, delegateProvider);
  }

  /**
   * Creates the MongoHealthChecker for health endpoint integration.
   *
   * <p>Provides detailed health information including:
   *
   * <ul>
   *   <li>Connection status and latency
   *   <li>Database name and MongoDB version
   *   <li>Connection pool status
   *   <li>Replica set info (if applicable)
   * </ul>
   *
   * @param fluxgateMongoDatabase the MongoDB database
   * @return MongoHealthChecker for actuator health endpoint
   */
  @Bean
  @ConditionalOnMissingBean(MongoHealthChecker.class)
  public MongoHealthChecker mongoHealthChecker(MongoDatabase fluxgateMongoDatabase) {
    log.info("Creating FluxGate MongoHealthChecker");
    MongoHealthCheckerImpl impl = new MongoHealthCheckerImpl(fluxgateMongoDatabase);

    // Adapt MongoHealthCheckerImpl to MongoHealthChecker interface
    return () -> {
      MongoHealthCheckerImpl.HealthCheckResult result = impl.check();
      if (result.isHealthy()) {
        return HealthStatus.up(result.message(), result.details());
      } else {
        return HealthStatus.down(result.message(), result.details());
      }
    };
  }

  /**
   * Renders the hosts (and database) of a MongoDB URI for logging.
   *
   * <p>The driver's own parser is used rather than a regex over the URI: a password containing an
   * {@code @} or a {@code :} defeats the regex and leaves its tail in the log, and a URI the driver
   * cannot parse is not logged at all, because whatever is unparseable about it may still be a
   * credential.
   */
  private String maskUri(String uri) {
    if (uri == null) {
      return "null";
    }
    try {
      ConnectionString connectionString = new ConnectionString(uri);
      String hosts = String.join(",", connectionString.getHosts());
      String database = connectionString.getDatabase();
      return database != null ? hosts + "/" + database : hosts;
    } catch (IllegalArgumentException e) {
      return "<unparseable mongodb uri>";
    }
  }

  /** Creates collection if it doesn't exist (ddl-auto: create). */
  private void createCollectionIfNotExists(MongoDatabase database, String collectionName) {
    boolean exists = collectionExists(database, collectionName);
    if (!exists) {
      log.info("Creating MongoDB collection: {}", collectionName);
      database.createCollection(collectionName);
    }
  }

  /** Validates that collection exists (ddl-auto: validate). */
  private void validateCollectionExists(MongoDatabase database, String collectionName) {
    boolean exists = collectionExists(database, collectionName);
    if (!exists) {
      throw new IllegalStateException(
          String.format(
              "MongoDB collection '%s' does not exist. "
                  + "Create it manually or set fluxgate.mongo.ddl-auto=create",
              collectionName));
    }
  }

  /** Check if a collection exists in the database. */
  private boolean collectionExists(MongoDatabase database, String collectionName) {
    // Use into() to force immediate execution of the query
    for (String name : database.listCollectionNames().into(new java.util.ArrayList<>())) {
      if (name.equals(collectionName)) {
        return true;
      }
    }
    return false;
  }
}
