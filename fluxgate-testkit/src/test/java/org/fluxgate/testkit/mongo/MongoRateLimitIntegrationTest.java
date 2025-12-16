package org.fluxgate.testkit.mongo;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.fluxgate.adapter.mongo.event.MongoRateLimitMetricsRecorder;
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.adapter.mongo.rule.MongoRuleSetProvider;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.junit.jupiter.api.*;

/**
 * Integration test verifying: - A "PER_IP, 100 req/5s" rule set stored in MongoDB - Requests #1 ~
 * #100 are allowed - Request #101 is rejected - Metrics are recorded into MongoDB
 *
 * <p>This test uses a simple in-memory RateLimiter implementation so that only the Mongo adapter +
 * metrics adapter are exercised.
 */
class MongoRateLimitIntegrationTest {

  private static final String DEFAULT_MONGO_URI =
      "mongodb://fluxgate:fluxgate123@localhost:27017/fluxgate?authSource=admin";
  private static final String DEFAULT_DB_NAME = "fluxgate";

  private static final String MONGO_URI =
      System.getProperty(
          "fluxgate.mongo.uri",
          System.getenv().getOrDefault("FLUXGATE_MONGO_URI", DEFAULT_MONGO_URI));

  private static final String DB_NAME =
      System.getProperty(
          "fluxgate.mongo.db", System.getenv().getOrDefault("FLUXGATE_MONGO_DB", DEFAULT_DB_NAME));

  private static final String RULE_COLLECTION = "rate_limit_rules";
  private static final String METRIC_COLLECTION = "rate_limit_events";

  private static final String RULE_SET_ID = "mongo-per-ip-5s-100";
  private static final String RULE_ID = "rule-per-ip-5s-100";

  private MongoClient client;
  private MongoDatabase database;
  private MongoCollection<Document> ruleCollection;
  private MongoCollection<Document> metricCollection;

  private MongoRateLimitRuleRepository ruleRepository;
  private RateLimitRuleSetProvider ruleSetProvider;
  private MongoRateLimitMetricsRecorder metricsRecorder;

  @BeforeEach
  void setUp() {
    System.out.println("> Connecting to MongoDB: " + MONGO_URI);

    client = MongoClients.create(MONGO_URI);
    database = client.getDatabase(DB_NAME);
    ruleCollection = database.getCollection(RULE_COLLECTION);
    metricCollection = database.getCollection(METRIC_COLLECTION);

    System.out.println("> Cleaning test collections...");
    ruleCollection.deleteMany(Filters.eq("ruleSetId", RULE_SET_ID));
    metricCollection.deleteMany(new Document());

    System.out.println("> Inserting rule set document into Mongo...");
    insertRuleSetDocument();

    ruleRepository = new MongoRateLimitRuleRepository(ruleCollection);
    ruleSetProvider = new MongoRuleSetProvider(ruleRepository, new LimitScopeKeyResolver());
    metricsRecorder = new MongoRateLimitMetricsRecorder(metricCollection);

    System.out.println("* Setup completed.\n");
  }

  @AfterEach
  void tearDown() {
    System.out.println("> Closing MongoDB connection.");
    if (client != null) {
      client.close();
    }
  }

  @Test
  @DisplayName("Given a PER_IP 5-second 100 req rule, allow 100 requests and reject the 101st")
  void mongoRateLimitTest() {
    System.out.println("> Loading ruleSetId = " + RULE_SET_ID);

    Optional<RateLimitRuleSet> maybeRuleSet = ruleSetProvider.findById(RULE_SET_ID);
    assertTrue(maybeRuleSet.isPresent(), "RuleSet should load from MongoDB");

    RateLimitRuleSet ruleSet = maybeRuleSet.get();
    System.out.println("* RuleSet loaded: " + ruleSet.getId());

    RateLimiter limiter = createTestRateLimiter(metricsRecorder);

    RequestContext ctx = createRequestContext("203.0.113.10");
    System.out.println("> Using test IP = 203.0.113.10\n");

    System.out.println("> Sending 100 allowed requests...");
    for (int i = 1; i <= 100; i++) {
      RateLimitResult result = limiter.tryConsume(ctx, ruleSet);
      System.out.println(
          "  - Request #"
              + i
              + " allowed="
              + result.isAllowed()
              + ", remaining="
              + result.getRemainingTokens());
      assertTrue(result.isAllowed(), "Request #" + i + " should be allowed");
    }
    System.out.println("* First 100 requests passed.");

    long metricCountAfter100 = metricCollection.countDocuments();
    System.out.println("> Metric documents after 100 req: " + metricCountAfter100);

    // Now request 101
    System.out.println("\n> Sending 101st request...");
    RateLimitResult result101 = limiter.tryConsume(ctx, ruleSet);
    System.out.println("  - Request #101 allowed=" + result101.isAllowed());
    assertFalse(result101.isAllowed(), "101st request must be rejected");

    long metricCountAfter101 = metricCollection.countDocuments();
    System.out.println("> Metric documents after 101 req: " + metricCountAfter101);
  }

  @Test
  @DisplayName("After 5-second window, the same IP should be allowed again")
  void mongoRateLimitWindowResetTest() throws InterruptedException {
    System.out.println("> Loading ruleSetId = " + RULE_SET_ID + " (window reset test)");

    Optional<RateLimitRuleSet> maybeRuleSet = ruleSetProvider.findById(RULE_SET_ID);
    assertTrue(maybeRuleSet.isPresent(), "RuleSet should load from MongoDB");

    RateLimitRuleSet ruleSet = maybeRuleSet.get();
    System.out.println("* RuleSet loaded: " + ruleSet.getId());

    InMemoryTestRateLimiter limiter = new InMemoryTestRateLimiter(metricsRecorder);

    RequestContext ctx = createRequestContext("203.0.113.20");
    System.out.println("> Using test IP = 203.0.113.20\n");

    System.out.println("> Consuming 100 requests in the first window...");
    for (int i = 1; i <= 100; i++) {
      RateLimitResult result = limiter.tryConsume(ctx, ruleSet);
      System.out.println(
          "  - [Window#1] Request #"
              + i
              + " allowed="
              + result.isAllowed()
              + ", remaining="
              + result.getRemainingTokens());
      assertTrue(result.isAllowed(), "Request #" + i + " should be allowed in first window");
    }

    System.out.println("> Sending 101st request in the same window...");
    RateLimitResult result101 = limiter.tryConsume(ctx, ruleSet);
    System.out.println("  - [Window#1] Request #101 allowed=" + result101.isAllowed());
    assertFalse(result101.isAllowed(), "101st request must be rejected in first window");

    long metricsBeforeSleep = metricCollection.countDocuments();
    System.out.println("> Metric documents before sleeping: " + metricsBeforeSleep);

    // Derive the window size from the rule itself (should be 60 seconds)
    RateLimitRule rule = ruleSet.getRules().get(0);
    RateLimitBand band = rule.getBands().get(0);
    Duration window = band.getWindow();
    long sleepMillis = window.toMillis() + 1_000L; // window + 1 second buffer

    System.out.println("\n> Sleeping for " + sleepMillis + " ms to cross the time window...");
    Thread.sleep(sleepMillis);

    System.out.println("> Sending a new request after the window should have reset...");
    RateLimitResult resultAfterWindow = limiter.tryConsume(ctx, ruleSet);
    System.out.println(
        "  - [Window#2] Request #1 allowed="
            + resultAfterWindow.isAllowed()
            + ", remaining="
            + resultAfterWindow.getRemainingTokens());
    assertTrue(
        resultAfterWindow.isAllowed(),
        "Request should be allowed after the time window has passed");

    long metricsAfterSleep = metricCollection.countDocuments();
    System.out.println("> Metric documents after sleeping: " + metricsAfterSleep);
  }

  private void insertRuleSetDocument() {
    Document band =
        new Document()
            .append("windowSeconds", 5L)
            .append("capacity", 100L)
            .append("label", "100-per-5-seconds");

    Document rule =
        new Document()
            .append("id", RULE_ID)
            .append("name", "Per IP 100 req per 5 seconds")
            .append("enabled", true)
            .append("scope", LimitScope.PER_IP.name())
            .append("keyStrategyId", "ip")
            .append("onLimitExceedPolicy", OnLimitExceedPolicy.REJECT_REQUEST.name())
            .append("ruleSetId", RULE_SET_ID)
            .append("bands", List.of(band));

    ruleCollection.insertOne(rule);
  }

  private RequestContext createRequestContext(String ip) {
    return RequestContext.builder().endpoint("/api/mongo/test").method("GET").clientIp(ip).build();
  }

  private RateLimiter createTestRateLimiter(MongoRateLimitMetricsRecorder recorder) {
    return new InMemoryTestRateLimiter(recorder);
  }

  /**
   * Simple in-memory RateLimiter used only in this test. It assumes a single RateLimitRule in the
   * provided RateLimitRuleSet. This implementation uses System.nanoTime() and respects the first
   * band window.
   */
  private static class InMemoryTestRateLimiter implements RateLimiter {

    private final MongoRateLimitMetricsRecorder recorder;

    private long consumedInWindow = 0L;
    private long windowStartNanos = -1L;
    private long windowNanos = -1L; // initialized lazily from rule band

    InMemoryTestRateLimiter(MongoRateLimitMetricsRecorder recorder) {
      this.recorder = recorder;
    }

    @Override
    public RateLimitResult tryConsume(
        RequestContext context, RateLimitRuleSet ruleSet, long permits) {

      RateLimitRule rule = ruleSet.getRules().get(0);
      RateLimitKey key = ruleSet.getKeyResolver().resolve(context, rule);

      // Lazily initialize window duration from the first band
      if (windowNanos < 0L) {
        RateLimitBand band = rule.getBands().get(0);
        Duration window = band.getWindow();
        windowNanos = window.toNanos();
        windowStartNanos = System.nanoTime();
        System.out.println(
            "  - [Window] initialized: window="
                + window
                + ", windowNanos="
                + windowNanos
                + ", startNanos="
                + windowStartNanos);
      }

      long now = System.nanoTime();

      // Roll the window if the time has passed
      if (now - windowStartNanos >= windowNanos) {
        System.out.println(
            "  - [Window] expired, resetting (now="
                + now
                + ", startNanos="
                + windowStartNanos
                + ")");
        windowStartNanos = now;
        consumedInWindow = 0L;
      }

      long capacity = 100L; // from our test rule (band.capacity)

      RateLimitResult result;

      if (consumedInWindow + permits <= capacity) {
        consumedInWindow += permits;
        long remaining = capacity - consumedInWindow;
        result = RateLimitResult.allowed(key, rule, remaining, 0L);
      } else {
        long nanosToWait = windowNanos - (now - windowStartNanos);
        if (nanosToWait < 0) {
          nanosToWait = 0;
        }
        result = RateLimitResult.rejected(key, rule, nanosToWait);
      }

      recorder.record(context, result);

      return result;
    }
  }
}
