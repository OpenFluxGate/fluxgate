package org.fluxgate.testkit.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Full end-to-end integration test for FluxGate rate limiting.
 *
 * <p>This test uses: - MongoDB to store and load rate limit rules - Redis as the runtime token
 * bucket storage - RedisRateLimiter as the RateLimiter implementation
 *
 * <p>Prerequisites: - MongoDB running at:
 * mongodb://fluxgate:fluxgate123@localhost:27017/fluxgate?authSource=admin - Redis running at:
 * redis://localhost:6379
 */
class MongoRedisRateLimitIntegrationTest {

  // ========================================================================
  // Configuration (supports environment variables for CI)
  // ========================================================================

  private static final String MONGO_URI =
      System.getenv()
          .getOrDefault(
              "FLUXGATE_MONGO_URI",
              "mongodb://fluxgate:fluxgate123@localhost:27017/fluxgate?authSource=admin");

  private static final String MONGO_DB =
      System.getenv().getOrDefault("FLUXGATE_MONGO_DB", "fluxgate");

  private static final String REDIS_URI =
      System.getenv().getOrDefault("FLUXGATE_REDIS_URI", "redis://localhost:6379");

  // Test constants as specified in the requirements
  private static final String RULE_SET_ID = "mongo-redis-per-ip-1m-100";
  private static final String RULE_ID = "per-ip-100-per-minute-rule";
  private static final String TEST_CLIENT_IP = "203.0.113.10";
  private static final String TEST_ENDPOINT = "/api/test/redis";
  private static final String TEST_METHOD = "GET";
  private static final String RULE_COLLECTION = "rate_limit_rules";

  // ========================================================================
  // Components
  // ========================================================================

  // MongoDB
  private MongoClient mongoClient;
  private MongoDatabase mongoDatabase;
  private MongoRateLimitRuleRepository ruleRepository;
  private MongoRuleSetProvider ruleSetProvider;

  // Redis
  private RedisRateLimiterConfig redisConfig;
  private RedisConnectionProvider connectionProvider;
  private RedisRateLimiter redisRateLimiter;

  // ========================================================================
  // Setup & Teardown
  // ========================================================================

  @BeforeEach
  void setUp() throws IOException {
    System.out.println("\n" + "=".repeat(70));
    System.out.println("SETUP: Initializing MongoDB and Redis connections");
    System.out.println("=".repeat(70));

    // 1. Connect to MongoDB
    System.out.println("\n[MongoDB] Connecting to: " + MONGO_URI);
    mongoClient = MongoClients.create(MONGO_URI);
    mongoDatabase = mongoClient.getDatabase(MONGO_DB);

    MongoCollection<Document> ruleCollection = mongoDatabase.getCollection(RULE_COLLECTION);

    // Clean state: drop collection to ensure fresh start
    System.out.println("[MongoDB] Dropping collection: " + RULE_COLLECTION);
    ruleCollection.drop();

    // Create repository and provider
    ruleRepository = new MongoRateLimitRuleRepository(ruleCollection);

    // KeyResolver: uses LimitScopeKeyResolver for scope-based key resolution
    ruleSetProvider = new MongoRuleSetProvider(ruleRepository, new LimitScopeKeyResolver());

    System.out.println("[MongoDB] Connected successfully");

    // 2. Connect to Redis
    System.out.println("\n[Redis] Connecting to: " + REDIS_URI);
    redisConfig = new RedisRateLimiterConfig(REDIS_URI);
    connectionProvider = redisConfig.getConnectionProvider();
    redisRateLimiter = new RedisRateLimiter(redisConfig.getTokenBucketStore());

    // Clean state: flush Redis DB
    System.out.println("[Redis] Flushing database");
    connectionProvider.flushdb();

    System.out.println("[Redis] Connected successfully");

    System.out.println("\n" + "-".repeat(70));
    System.out.println("SETUP COMPLETE");
    System.out.println("-".repeat(70) + "\n");
  }

  @AfterEach
  void tearDown() {
    System.out.println("\n" + "=".repeat(70));
    System.out.println("TEARDOWN: Closing connections");
    System.out.println("=".repeat(70));

    if (redisConfig != null) {
      redisConfig.close();
      System.out.println("[Redis] Connection closed");
    }

    if (mongoClient != null) {
      mongoClient.close();
      System.out.println("[MongoDB] Connection closed");
    }

    System.out.println("TEARDOWN COMPLETE\n");
  }

  // ========================================================================
  // Test: Full E2E Flow
  // ========================================================================

  @Test
  @DisplayName("E2E: 100 requests allowed, 101st rejected - MongoDB rules + Redis enforcement")
  void shouldAllowFirst100RequestsThenReject101st() {
    System.out.println("=".repeat(70));
    System.out.println("TEST: Full E2E Rate Limiting");
    System.out.println("  - RuleSetId: " + RULE_SET_ID);
    System.out.println("  - Scope: PER_IP");
    System.out.println("  - Capacity: 100 requests");
    System.out.println("  - Window: 60 seconds");
    System.out.println("=".repeat(70));

    // ====================================================================
    // STEP 1: Insert rule into MongoDB
    // ====================================================================
    System.out.println("\n[STEP 1] Inserting rate limit rule into MongoDB...");

    // Create the band: 100 requests per 60 seconds
    RateLimitBand band =
        RateLimitBand.builder(Duration.ofSeconds(60), 100).label("100-per-minute").build();

    // Create the rule
    RateLimitRule rule =
        RateLimitRule.builder(RULE_ID)
            .name("Per-IP Rate Limit: 100/minute")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .keyStrategyId("ip") // As specified in requirements
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(band)
            .ruleSetId(RULE_SET_ID)
            .build();

    // Convert to MongoDB document and insert
    RateLimitRuleDocument ruleDocument = RateLimitRuleMongoConverter.toDto(rule);
    ruleRepository.upsert(ruleDocument);

    System.out.println("  Rule ID: " + RULE_ID);
    System.out.println("  RuleSet ID: " + RULE_SET_ID);
    System.out.println("  Scope: " + LimitScope.PER_IP);
    System.out.println(
        "  Band: "
            + band.getLabel()
            + " ("
            + band.getCapacity()
            + " / "
            + band.getWindow().getSeconds()
            + "s)");
    System.out.println("  [OK] Rule inserted into MongoDB");

    // ====================================================================
    // STEP 2: Load rule set using MongoRuleSetProvider
    // ====================================================================
    System.out.println("\n[STEP 2] Loading rule set from MongoDB...");

    Optional<RateLimitRuleSet> ruleSetOpt = ruleSetProvider.findById(RULE_SET_ID);

    assertTrue(
        ruleSetOpt.isPresent(), "RuleSet should be found in MongoDB with ID: " + RULE_SET_ID);

    RateLimitRuleSet ruleSet = ruleSetOpt.get();

    System.out.println("  RuleSet ID: " + ruleSet.getId());
    System.out.println("  Rules loaded: " + ruleSet.getRules().size());
    System.out.println("  [OK] RuleSet loaded successfully");

    // ====================================================================
    // STEP 3: Build RequestContext
    // ====================================================================
    System.out.println("\n[STEP 3] Building RequestContext...");

    RequestContext context =
        RequestContext.builder()
            .clientIp(TEST_CLIENT_IP)
            .endpoint(TEST_ENDPOINT)
            .method(TEST_METHOD)
            .build();

    System.out.println("  Client IP: " + TEST_CLIENT_IP);
    System.out.println("  Endpoint: " + TEST_ENDPOINT);
    System.out.println("  Method: " + TEST_METHOD);
    System.out.println("  [OK] RequestContext created");

    // ====================================================================
    // STEP 4: Make 100 requests (all should be ALLOWED)
    // ====================================================================
    System.out.println("\n[STEP 4] Making 100 requests (expecting all ALLOWED)...");
    System.out.println("-".repeat(50));

    for (int i = 1; i <= 100; i++) {
      RateLimitResult result = redisRateLimiter.tryConsume(context, ruleSet, 1);

      assertTrue(result.isAllowed(), "Request #" + i + " should be allowed but was rejected");

      // Log every request for clarity
      System.out.printf(
          "  Request #%3d: allowed=%s, remaining=%d%n",
          i, result.isAllowed(), result.getRemainingTokens());
    }

    System.out.println("-".repeat(50));
    System.out.println("  [OK] All 100 requests were ALLOWED");

    // ====================================================================
    // STEP 5: Make 101st request (should be REJECTED)
    // ====================================================================
    System.out.println("\n[STEP 5] Making 101st request (expecting REJECTED)...");
    System.out.println("-".repeat(50));

    RateLimitResult result101 = redisRateLimiter.tryConsume(context, ruleSet, 1);

    assertFalse(result101.isAllowed(), "Request #101 should be REJECTED (rate limit exceeded)");

    assertEquals(
        0,
        result101.getRemainingTokens(),
        "Remaining tokens should be 0 after exhausting capacity");

    assertTrue(
        result101.getNanosToWaitForRefill() > 0, "Wait time should be > 0 for rejected request");

    long waitMillis = result101.getNanosToWaitForRefill() / 1_000_000;
    long waitSeconds = waitMillis / 1000;

    System.out.printf(
        "  Request #101: allowed=%s, remaining=%d%n",
        result101.isAllowed(), result101.getRemainingTokens());
    System.out.printf("  Wait time until refill: %d ms (~%d seconds)%n", waitMillis, waitSeconds);
    System.out.println("-".repeat(50));
    System.out.println("  [OK] Request #101 was correctly REJECTED");

    // ====================================================================
    // STEP 6: Verify matched rule in rejection
    // ====================================================================
    System.out.println("\n[STEP 6] Verifying rejection details...");

    assertNotNull(result101.getMatchedRule(), "Rejected result should have a matched rule");
    assertEquals(
        RULE_ID,
        result101.getMatchedRule().getId(),
        "Matched rule ID should match the configured rule");

    System.out.println("  Matched Rule ID: " + result101.getMatchedRule().getId());
    System.out.println("  Matched Rule Name: " + result101.getMatchedRule().getName());
    System.out.println("  [OK] Rejection details verified");

    // ====================================================================
    // SUCCESS
    // ====================================================================
    System.out.println("\n" + "=".repeat(70));
    System.out.println("TEST PASSED: Full E2E Rate Limiting works correctly!");
    System.out.println("  - MongoDB successfully stored and loaded rules");
    System.out.println("  - Redis successfully enforced rate limits");
    System.out.println("  - 100 requests allowed, 101st rejected as expected");
    System.out.println("=".repeat(70) + "\n");
  }

  // ========================================================================
  // Test: Concurrency Stress Test
  // ========================================================================

  @Test
  @DisplayName("Concurrency: 20 threads x 50 requests = 1000 total, exactly 100 allowed")
  void shouldEnforceRateLimitUnderConcurrentLoad() throws InterruptedException {
    System.out.println("=".repeat(70));
    System.out.println("TEST: Concurrency Stress Test");
    System.out.println("  - Threads: 20");
    System.out.println("  - Requests per thread: 50");
    System.out.println("  - Total requests: 1000");
    System.out.println("  - Expected allowed: 100");
    System.out.println("  - Expected rejected: 900");
    System.out.println("=".repeat(70));

    // Configuration
    final int THREAD_COUNT = 20;
    final int REQUESTS_PER_THREAD = 50;
    final int TOTAL_REQUESTS = THREAD_COUNT * REQUESTS_PER_THREAD;
    final int CAPACITY = 100;
    final String CONCURRENCY_TEST_IP = "203.0.113.20";
    final String CONCURRENCY_RULE_SET_ID = "concurrency-stress-test";
    final String CONCURRENCY_RULE_ID = "concurrency-per-ip-100";

    // ====================================================================
    // STEP 1: Insert rule into MongoDB
    // ====================================================================
    System.out.println("\n[STEP 1] Inserting rate limit rule into MongoDB...");

    RateLimitBand band =
        RateLimitBand.builder(Duration.ofSeconds(60), CAPACITY).label("100-per-minute").build();

    RateLimitRule rule =
        RateLimitRule.builder(CONCURRENCY_RULE_ID)
            .name("Concurrency Test: 100/minute per IP")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .keyStrategyId("ip")
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(band)
            .ruleSetId(CONCURRENCY_RULE_SET_ID)
            .build();

    RateLimitRuleDocument ruleDocument = RateLimitRuleMongoConverter.toDto(rule);
    ruleRepository.upsert(ruleDocument);

    System.out.println("  [OK] Rule inserted: " + CONCURRENCY_RULE_ID);

    // ====================================================================
    // STEP 2: Load rule set
    // ====================================================================
    System.out.println("\n[STEP 2] Loading rule set from MongoDB...");

    Optional<RateLimitRuleSet> ruleSetOpt = ruleSetProvider.findById(CONCURRENCY_RULE_SET_ID);
    assertTrue(ruleSetOpt.isPresent(), "RuleSet should be found");

    RateLimitRuleSet ruleSet = ruleSetOpt.get();
    System.out.println("  [OK] RuleSet loaded: " + ruleSet.getId());

    // ====================================================================
    // STEP 3: Prepare RequestContext (same IP for all threads)
    // ====================================================================
    System.out.println("\n[STEP 3] Creating RequestContext...");

    RequestContext context =
        RequestContext.builder()
            .clientIp(CONCURRENCY_TEST_IP)
            .endpoint("/api/stress-test")
            .method("GET")
            .build();

    System.out.println("  Client IP: " + CONCURRENCY_TEST_IP);
    System.out.println("  [OK] RequestContext created");

    // ====================================================================
    // STEP 4: Spawn threads and send concurrent requests
    // ====================================================================
    System.out.println("\n[STEP 4] Spawning " + THREAD_COUNT + " threads...");
    System.out.println("-".repeat(50));

    // Atomic counters for thread-safe counting
    AtomicInteger allowedCount = new AtomicInteger(0);
    AtomicInteger rejectedCount = new AtomicInteger(0);

    // Use CountDownLatch to start all threads at the same time
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

    // Track per-thread results for debugging
    List<AtomicInteger> perThreadAllowed = new ArrayList<>();
    List<AtomicInteger> perThreadRejected = new ArrayList<>();
    for (int i = 0; i < THREAD_COUNT; i++) {
      perThreadAllowed.add(new AtomicInteger(0));
      perThreadRejected.add(new AtomicInteger(0));
    }

    // Create thread pool
    ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

    // Submit tasks
    for (int t = 0; t < THREAD_COUNT; t++) {
      final int threadId = t;
      executor.submit(
          () -> {
            try {
              // Wait for start signal (ensures all threads start together)
              startLatch.await();

              for (int r = 0; r < REQUESTS_PER_THREAD; r++) {
                RateLimitResult result = redisRateLimiter.tryConsume(context, ruleSet, 1);

                if (result.isAllowed()) {
                  allowedCount.incrementAndGet();
                  perThreadAllowed.get(threadId).incrementAndGet();
                } else {
                  rejectedCount.incrementAndGet();
                  perThreadRejected.get(threadId).incrementAndGet();
                }
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    // Start all threads simultaneously
    long startTime = System.currentTimeMillis();
    System.out.println("  Starting all threads NOW!");
    startLatch.countDown();

    // Wait for all threads to complete (with timeout)
    boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
    long elapsed = System.currentTimeMillis() - startTime;

    assertTrue(completed, "All threads should complete within timeout");

    // Shutdown executor
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);

    System.out.println("  [OK] All threads completed in " + elapsed + " ms");
    System.out.println("-".repeat(50));

    // ====================================================================
    // STEP 5: Print per-thread breakdown
    // ====================================================================
    System.out.println("\n[STEP 5] Per-thread breakdown:");
    System.out.println("-".repeat(50));

    for (int t = 0; t < THREAD_COUNT; t++) {
      System.out.printf(
          "  Thread #%2d: allowed=%2d, rejected=%2d%n",
          t, perThreadAllowed.get(t).get(), perThreadRejected.get(t).get());
    }

    System.out.println("-".repeat(50));

    // ====================================================================
    // STEP 6: Verify results
    // ====================================================================
    System.out.println("\n[STEP 6] Verifying results...");
    System.out.println("-".repeat(50));

    int totalAllowed = allowedCount.get();
    int totalRejected = rejectedCount.get();
    int totalProcessed = totalAllowed + totalRejected;

    System.out.println("  Total requests processed: " + totalProcessed);
    System.out.println("  Allowed: " + totalAllowed);
    System.out.println("  Rejected: " + totalRejected);
    System.out.println("-".repeat(50));

    // Assertions
    assertEquals(
        TOTAL_REQUESTS, totalProcessed, "Total processed should equal total requests sent");

    assertEquals(
        CAPACITY,
        totalAllowed,
        "Exactly " + CAPACITY + " requests should be allowed (capacity limit)");

    assertEquals(
        TOTAL_REQUESTS - CAPACITY, totalRejected, "Rejected count should be (total - capacity)");

    // ====================================================================
    // SUCCESS
    // ====================================================================
    System.out.println("\n" + "=".repeat(70));
    System.out.println("TEST PASSED: Concurrency Stress Test!");
    System.out.printf(
        "  allowed=%d, rejected=%d (total=%d)%n", totalAllowed, totalRejected, totalProcessed);
    System.out.println("  - Redis Lua script correctly handles concurrent access");
    System.out.println("  - Token bucket atomicity verified under load");
    System.out.println("  - Throughput: " + (TOTAL_REQUESTS * 1000 / elapsed) + " req/sec");
    System.out.println("=".repeat(70) + "\n");
  }
}
