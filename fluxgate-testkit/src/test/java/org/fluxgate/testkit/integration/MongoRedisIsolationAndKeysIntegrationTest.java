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
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.fluxgate.testkit.support.MongoContainerSupport;
import org.fluxgate.testkit.support.RedisContainerSupport;
import org.junit.jupiter.api.*;

/**
 * End-to-end integration test combining MongoDB rule storage with Redis rate limiting.
 *
 * <p>This test demonstrates the full FluxGate architecture: 1. Rules are stored in MongoDB (via
 * fluxgate-mongo-adapter) 2. Rules are loaded using MongoRuleSetProvider 3. Rate limiting is
 * enforced by RedisRateLimiter (via fluxgate-redis-ratelimiter) 4. Token buckets are stored in
 * Redis for distributed enforcement
 *
 * <p>The MongoDB and Redis targets come from {@link MongoContainerSupport} and {@link
 * RedisContainerSupport}: supplied {@code FLUXGATE_MONGO_URI} / {@code FLUXGATE_REDIS_URI},
 * Testcontainers, or the test is skipped. The rule set id carries a run id so Redis buckets are
 * never inherited from an earlier run, and only those keys are deleted afterwards.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MongoRedisIsolationAndKeysIntegrationTest {

  /** Makes every rule set id - and therefore every Redis bucket key - unique to this JVM run. */
  private static final String RUN_ID = RedisContainerSupport.newRunId();

  // Test constants
  private static final String RULE_SET_ID = "e2e-test-ruleset-" + RUN_ID;
  private static final String RULE_ID = "per-ip-100-per-day";
  private static final String TEST_IP = "203.0.113.10";

  // MongoDB components
  private MongoClient mongoClient;
  private MongoDatabase mongoDatabase;
  private MongoCollection<Document> ruleCollection;
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
    mongoClient = MongoClients.create(MongoContainerSupport.mongoUri());
    mongoDatabase = mongoClient.getDatabase(MongoContainerSupport.databaseName());

    // A collection of this test's own, so a shared MongoDB keeps its data
    ruleCollection =
        mongoDatabase.getCollection(MongoContainerSupport.uniqueCollectionName("rate_limit_rules"));
    System.out.println(
        "Using MongoDB collection: " + ruleCollection.getNamespace().getCollectionName());

    ruleRepository = new MongoRateLimitRuleRepository(ruleCollection);

    // KeyResolver: uses LimitScopeKeyResolver for scope-based key resolution
    ruleSetProvider = new MongoRuleSetProvider(ruleRepository, new LimitScopeKeyResolver());

    // 2. Setup Redis
    redisConfig = new RedisRateLimiterConfig(RedisContainerSupport.redisUri());
    connectionProvider = redisConfig.getConnectionProvider();
    redisRateLimiter = new RedisRateLimiter(redisConfig.getTokenBucketStore());

    // Clean state: this run's own keys only - never flushdb, the target may be shared
    RedisContainerSupport.deleteKeys(connectionProvider, runKeyPattern());

    System.out.println("✓ Setup complete\n");
  }

  @AfterEach
  void tearDown() {
    System.out.println("\n=== Cleaning up ===");

    if (redisConfig != null) {
      RedisContainerSupport.deleteKeys(connectionProvider, runKeyPattern());
      redisConfig.close();
    }

    if (ruleCollection != null) {
      ruleCollection.drop();
    }

    if (mongoClient != null) {
      mongoClient.close();
    }

    System.out.println("✓ Cleanup complete\n");
  }

  /** Matches only the FluxGate keys this JVM run created. */
  private static String runKeyPattern() {
    return RedisContainerSupport.KEY_PREFIX + "*" + RUN_ID + "*";
  }

  @Test
  @Order(1)
  @DisplayName(
      "End-to-End: MongoDB rule storage → Redis rate limiting (100 allowed, 101st rejected)")
  void shouldEnforceRateLimitFromMongoRuleStoredInRedis() {
    System.out.println("=== Test: MongoDB Rule Storage → Redis Enforcement ===\n");

    // Step 1: Create and store rate limit rule in MongoDB
    System.out.println("STEP 1: Storing rule in MongoDB");
    System.out.println("  Rule: PER_IP, 100 requests per day");

    // A day-long window refills one token every 864 s, so no token comes back while the loop runs
    // (100 per minute refilled one every 600 ms and let request #101 through on a loaded machine).
    RateLimitBand band =
        RateLimitBand.builder(Duration.ofDays(1), 100).label("100-per-day").build();

    RateLimitRule rule =
        RateLimitRule.builder(RULE_ID)
            .name("E2E Test: 100 requests per day per IP")
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

    String isolationRuleSetId = "isolation-ruleset-" + RUN_ID;

    // Setup: Store rule in MongoDB
    RateLimitBand band = RateLimitBand.builder(Duration.ofDays(1), 5).label("5-per-day").build();

    RateLimitRule rule =
        RateLimitRule.builder("isolation-test")
            .name("IP Isolation Test")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .keyStrategyId("clientIp")
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(band)
            .ruleSetId(isolationRuleSetId)
            .build();

    ruleRepository.upsert(RateLimitRuleMongoConverter.toDto(rule));

    Optional<RateLimitRuleSet> ruleSetOpt = ruleSetProvider.findById(isolationRuleSetId);
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

    String keyTestRuleSetId = "redis-key-ruleset-" + RUN_ID;

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
            .ruleSetId(keyTestRuleSetId)
            .build();

    ruleRepository.upsert(RateLimitRuleMongoConverter.toDto(rule));

    Optional<RateLimitRuleSet> ruleSetOpt = ruleSetProvider.findById(keyTestRuleSetId);
    RateLimitRuleSet ruleSet = ruleSetOpt.get();

    // Make a request to create Redis key
    RequestContext context =
        RequestContext.builder()
            .clientIp("192.168.1.100")
            .endpoint("/api/test")
            .method("GET")
            .build();

    assertTrue(redisRateLimiter.tryConsume(context, ruleSet, 1).isAllowed());

    // Verify Redis keys exist. Scan only this run's keys: the target may be a shared Redis
    // whose other keys (rule sets, other runs) legitimately have no TTL.
    List<String> keys = connectionProvider.scanKeys(runKeyPattern(), 500);
    assertNotNull(keys);
    assertFalse(keys.isEmpty(), "Redis should have FluxGate keys for " + keyTestRuleSetId);

    int bucketCount = 0;
    System.out.println("  Redis keys created:");
    for (String key : keys) {
      System.out.println("    - " + key);

      // Check TTL
      Long ttl = connectionProvider.ttl(key);
      assertNotNull(ttl);
      assertTrue(ttl > 0, "Key should have TTL set");
      System.out.println("      TTL: " + ttl + " seconds");

      // Policy metadata and revision fences share the run's namespace but are not buckets.
      // Check those through each bucket's actual companion keys below.
      if (key.startsWith("fluxgate:policy:")) {
        continue;
      }
      bucketCount++;

      // Check bucket structure and the policy geometry used by this request.
      Map<String, String> value = connectionProvider.hgetall(key);
      System.out.println("      Fields: " + value.keySet());
      assertTrue(value.containsKey("tokens"), "Key should have 'tokens' field");
      assertTrue(
          value.containsKey("last_refill_micros"), "Key should have 'last_refill_micros' field");
      String metadataKey = RedisTokenBucketStore.metadataKey(key);
      assertTrue(keys.contains(metadataKey), "Bucket policy metadata should exist");
      Map<String, String> metadata = connectionProvider.hgetall(metadataKey);
      assertEquals("0", metadata.get("revision"));
      assertEquals("10", metadata.get("capacity"));
      assertEquals("30000000", metadata.get("window_micros"));
      assertTrue(
          keys.contains(RedisTokenBucketStore.revisionKey(key)),
          "Rule revision fence should exist");
    }
    assertEquals(1, bucketCount, "One rule and one band should create exactly one bucket");

    System.out.println("\n=== Redis Key Structure Test PASSED ===");
  }
}
