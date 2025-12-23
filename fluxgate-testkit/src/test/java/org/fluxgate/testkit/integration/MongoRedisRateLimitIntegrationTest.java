package org.fluxgate.testkit.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.fluxgate.adapter.mongo.converter.RateLimitRuleMongoConverter;
import org.fluxgate.adapter.mongo.model.RateLimitRuleDocument;
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.adapter.mongo.rule.MongoRuleSetProvider;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.redis.RedisRateLimiter;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.junit.jupiter.api.*;

/**
 * End-to-end integration test combining MongoDB rule storage with Redis rate limiting.
 *
 * <p>This test demonstrates the full FluxGate architecture: 1. Rules are stored in MongoDB (via
 * fluxgate-mongo-adapter) 2. Rules are loaded using MongoRuleSetProvider 3. Rate limiting is
 * enforced by RedisRateLimiter (via fluxgate-redis-ratelimiter) 4. Token buckets are stored in
 * Redis for distributed enforcement
 *
 * <p>Prerequisites: - MongoDB running on localhost:27017 (or FLUXGATE_MONGO_URI) - Redis running on
 * localhost:6379 (or FLUXGATE_REDIS_URI)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MongoRedisRateLimitIntegrationTest {

  // MongoDB configuration
  private static final String MONGO_URI =
      System.getProperty(
          "fluxgate.mongo.uri",
          System.getenv()
              .getOrDefault(
                  "FLUXGATE_MONGO_URI",
                  "mongodb://fluxgate:fluxgate123%23%24@localhost:27017/fluxgate?authSource=admin"));

  private static final String MONGO_DB =
      System.getProperty(
          "fluxgate.mongo.db", System.getenv().getOrDefault("FLUXGATE_MONGO_DB", "fluxgate"));

  // Redis configuration
  private static final String REDIS_URI =
      System.getProperty(
          "fluxgate.redis.uri",
          System.getenv().getOrDefault("FLUXGATE_REDIS_URI", "redis://localhost:6379"));

  // Test constants
  private static final String RULE_SET_ID = "e2e-test-ruleset";
  private static final String RULE_ID = "per-ip-100-per-minute";
  private static final String TEST_IP = "203.0.113.10";
  private static final String RULE_COLLECTION = "rate_limit_rules";

  // MongoDB components
  private MongoClient mongoClient;
  private MongoDatabase mongoDatabase;
  private MongoRateLimitRuleRepository ruleRepository;
  private MongoRuleSetProvider ruleSetProvider;

  // Redis components
  private RedisRateLimiterConfig redisConfig;
  private RedisConnectionProvider connectionProvider;
  private RedisRateLimiter redisRateLimiter;

  @BeforeEach
  void setUp() throws IOException {
    System.out.println("\n=== Setting up MongoDB and Redis ===");

    // 1. Setup MongoDB
    System.out.println("Connecting to MongoDB: " + MONGO_URI);
    mongoClient = MongoClients.create(MONGO_URI);
    mongoDatabase = mongoClient.getDatabase(MONGO_DB);

    MongoCollection<Document> ruleCollection = mongoDatabase.getCollection(RULE_COLLECTION);

    // Clean MongoDB state
    System.out.println("Cleaning MongoDB collection: " + RULE_COLLECTION);
    ruleCollection.drop();

    ruleRepository = new MongoRateLimitRuleRepository(ruleCollection);

    // KeyResolver: uses LimitScopeKeyResolver for scope-based key resolution
    ruleSetProvider = new MongoRuleSetProvider(ruleRepository, new LimitScopeKeyResolver());

    // 2. Setup Redis
    System.out.println("Connecting to Redis: " + REDIS_URI);
    redisConfig = new RedisRateLimiterConfig(REDIS_URI);
    connectionProvider = redisConfig.getConnectionProvider();
    redisRateLimiter = new RedisRateLimiter(redisConfig.getTokenBucketStore());

    // Clean Redis state (flush all keys)
    System.out.println("Cleaning Redis database");
    connectionProvider.flushdb();

    System.out.println("✓ Setup complete\n");
  }

  @AfterEach
  void tearDown() {
    System.out.println("\n=== Cleaning up ===");

    if (redisConfig != null) {
      redisConfig.close();
    }

    if (mongoClient != null) {
      mongoClient.close();
    }

    System.out.println("✓ Cleanup complete\n");
  }

  @Test
  @Order(1)
  @DisplayName(
      "End-to-End: MongoDB rule storage → Redis rate limiting (100 allowed, 101st rejected)")
  void shouldEnforceRateLimitFromMongoRuleStoredInRedis() {
    System.out.println("=== Test: MongoDB Rule Storage → Redis Enforcement ===\n");

    // Step 1: Create and store rate limit rule in MongoDB
    System.out.println("STEP 1: Storing rule in MongoDB");
    System.out.println("  Rule: PER_IP, 100 requests per minute");

    RateLimitBand band =
        RateLimitBand.builder(Duration.ofMinutes(1), 100).label("100-per-minute").build();

    RateLimitRule rule =
        RateLimitRule.builder(RULE_ID)
            .name("E2E Test: 100 requests per minute per IP")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .keyStrategyId("clientIp")
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(band)
            .ruleSetId(RULE_SET_ID)
            .build();

    // Convert to DTO and store in MongoDB
    RateLimitRuleDocument ruleDocument = RateLimitRuleMongoConverter.toDto(rule);
    ruleRepository.upsert(ruleDocument);

    System.out.println("  ✓ Rule stored in MongoDB with ID: " + RULE_ID + "\n");

    // Step 2: Load rule from MongoDB using MongoRuleSetProvider
    System.out.println("STEP 2: Loading rule from MongoDB");

    Optional<RateLimitRuleSet> ruleSetOpt = ruleSetProvider.findById(RULE_SET_ID);
    assertTrue(ruleSetOpt.isPresent(), "RuleSet should be loaded from MongoDB");

    RateLimitRuleSet ruleSet = ruleSetOpt.get();
    System.out.println("  ✓ RuleSet loaded: " + ruleSet.getId());
    System.out.println("  ✓ Rules count: " + ruleSet.getRules().size() + "\n");

    // Step 3: Create RequestContext with fixed IP
    System.out.println("STEP 3: Creating RequestContext");
    System.out.println("  Client IP: " + TEST_IP);

    RequestContext context =
        RequestContext.builder().clientIp(TEST_IP).endpoint("/api/test").method("GET").build();

    System.out.println("  ✓ RequestContext created\n");

    // Step 4: Make 100 requests - all should be ALLOWED
    System.out.println("STEP 4: Making 100 requests (should all be allowed)");

    for (int i = 1; i <= 100; i++) {
      RateLimitResult result = redisRateLimiter.tryConsume(context, ruleSet, 1);

      assertTrue(result.isAllowed(), String.format("Request #%d should be allowed", i));

      // Print progress every 20 requests
      if (i % 20 == 0 || i == 1) {
        System.out.printf(
            "  Request #%d: ALLOWED (remaining: %d tokens)%n", i, result.getRemainingTokens());
      }
    }

    System.out.println("  ✓ All 100 requests were allowed\n");

    // Step 5: Make 101st request - should be REJECTED
    System.out.println("STEP 5: Making 101st request (should be rejected)");

    RateLimitResult rejectedResult = redisRateLimiter.tryConsume(context, ruleSet, 1);

    assertFalse(
        rejectedResult.isAllowed(), "Request #101 should be rejected (rate limit exceeded)");
    assertEquals(0, rejectedResult.getRemainingTokens(), "Remaining tokens should be 0");
    assertTrue(rejectedResult.getNanosToWaitForRefill() > 0, "Should have wait time > 0");

    long waitMs = rejectedResult.getNanosToWaitForRefill() / 1_000_000;
    System.out.printf("  Request #101: REJECTED (wait: %d ms)%n", waitMs);
    System.out.println("  ✓ Rate limit correctly enforced\n");

    // Step 6: Verify the rejection details
    System.out.println("STEP 6: Verifying rejection details");
    assertNotNull(rejectedResult.getMatchedRule(), "Rejected result should have matched rule");
    assertEquals(
        RULE_ID, rejectedResult.getMatchedRule().getId(), "Matched rule ID should be correct");

    System.out.println("  ✓ Matched rule: " + rejectedResult.getMatchedRule().getName());
    System.out.println("  ✓ Wait time: " + waitMs + " ms");

    System.out.println("\n=== Test PASSED ===");
  }

  @Test
  @Order(2)
  @DisplayName("Different IPs should have independent rate limits")
  void shouldIsolateRateLimitsByIp() {
    System.out.println("=== Test: IP Isolation ===\n");

    // Setup: Store rule in MongoDB
    RateLimitBand band =
        RateLimitBand.builder(Duration.ofMinutes(1), 5).label("5-per-minute").build();

    RateLimitRule rule =
        RateLimitRule.builder("isolation-test")
            .name("IP Isolation Test")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .keyStrategyId("clientIp")
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(band)
            .ruleSetId("isolation-ruleset")
            .build();

    ruleRepository.upsert(RateLimitRuleMongoConverter.toDto(rule));

    Optional<RateLimitRuleSet> ruleSetOpt = ruleSetProvider.findById("isolation-ruleset");
    assertTrue(ruleSetOpt.isPresent());
    RateLimitRuleSet ruleSet = ruleSetOpt.get();

    // Test: Exhaust limit for IP1
    System.out.println("Exhausting limit for IP 10.0.0.1");
    RequestContext context1 =
        RequestContext.builder().clientIp("10.0.0.1").endpoint("/api/test").method("GET").build();

    for (int i = 1; i <= 5; i++) {
      RateLimitResult result = redisRateLimiter.tryConsume(context1, ruleSet, 1);
      assertTrue(result.isAllowed());
    }

    // 6th request from IP1 should be rejected
    RateLimitResult rejected1 = redisRateLimiter.tryConsume(context1, ruleSet, 1);
    assertFalse(rejected1.isAllowed());
    System.out.println("  ✓ IP 10.0.0.1 is rate limited after 5 requests");

    // IP2 should still be allowed
    System.out.println("Testing IP 10.0.0.2 (should be independent)");
    RequestContext context2 =
        RequestContext.builder().clientIp("10.0.0.2").endpoint("/api/test").method("GET").build();

    RateLimitResult allowed2 = redisRateLimiter.tryConsume(context2, ruleSet, 1);
    assertTrue(allowed2.isAllowed());
    System.out.println("  ✓ IP 10.0.0.2 is allowed (independent limit)");

    System.out.println("\n=== IP Isolation Test PASSED ===");
  }

  @Test
  @Order(3)
  @DisplayName("Verify Redis key structure and TTL")
  void shouldCreateCorrectRedisKeysWithTTL() {
    System.out.println("=== Test: Redis Key Structure ===\n");

    // Setup rule
    RateLimitBand band =
        RateLimitBand.builder(Duration.ofSeconds(30), 10).label("10-per-30sec").build();

    RateLimitRule rule =
        RateLimitRule.builder("redis-key-test")
            .name("Redis Key Test")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .keyStrategyId("clientIp")
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(band)
            .ruleSetId("redis-key-ruleset")
            .build();

    ruleRepository.upsert(RateLimitRuleMongoConverter.toDto(rule));

    Optional<RateLimitRuleSet> ruleSetOpt = ruleSetProvider.findById("redis-key-ruleset");
    RateLimitRuleSet ruleSet = ruleSetOpt.get();

    // Make a request to create Redis key
    RequestContext context =
        RequestContext.builder()
            .clientIp("192.168.1.100")
            .endpoint("/api/test")
            .method("GET")
            .build();

    redisRateLimiter.tryConsume(context, ruleSet, 1);

    // Verify Redis keys exist
    List<String> keys = connectionProvider.keys("fluxgate:*");
    assertNotNull(keys);
    assertFalse(keys.isEmpty(), "Redis should have FluxGate keys");

    System.out.println("  Redis keys created:");
    for (String key : keys) {
      System.out.println("    - " + key);

      // Check TTL
      Long ttl = connectionProvider.ttl(key);
      assertNotNull(ttl);
      assertTrue(ttl > 0, "Key should have TTL set");
      System.out.println("      TTL: " + ttl + " seconds");

      // Check key structure
      Map<String, String> value = connectionProvider.hgetall(key);
      System.out.println("      Fields: " + value.keySet());
      assertTrue(value.containsKey("tokens"), "Key should have 'tokens' field");
      assertTrue(
          value.containsKey("last_refill_nanos"), "Key should have 'last_refill_nanos' field");
    }

    System.out.println("\n=== Redis Key Structure Test PASSED ===");
  }
}
