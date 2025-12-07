package org.fluxgate.adapter.mongo;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.bson.Document;
import org.fluxgate.adapter.mongo.event.MongoRateLimitMetricsRecorder;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.junit.jupiter.api.*;

class MongoRateLimitMetricsRecorderIntegrationTest {

  private static final String MONGO_URI =
      System.getProperty(
          "fluxgate.mongo.uri",
          System.getenv()
              .getOrDefault(
                  "FLUXGATE_MONGO_URI",
                  "mongodb://fluxgate:fluxgate123@localhost:27017/fluxgate?authSource=admin"));

  private static final String DB_NAME =
      System.getProperty(
          "fluxgate.mongo.db", System.getenv().getOrDefault("FLUXGATE_MONGO_DB", "fluxgate"));

  private static final String EVENT_COLLECTION = "rate_limit_events";

  private MongoClient client;
  private MongoDatabase database;
  private MongoCollection<Document> eventCollection;
  private MongoRateLimitMetricsRecorder recorder;

  @BeforeEach
  void setUp() {
    client = MongoClients.create(MONGO_URI);
    database = client.getDatabase(DB_NAME);
    eventCollection = database.getCollection(EVENT_COLLECTION);

    // Start with a clean state
    eventCollection.drop();

    recorder = new MongoRateLimitMetricsRecorder(eventCollection);
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
  }

  @Test
  @DisplayName("Should insert event document when recording allowed request")
  void record_shouldInsertEventDocumentForAllowedRequest() {
    // given
    RequestContext context =
        RequestContext.builder()
            .clientIp("192.168.1.100")
            .endpoint("/api/users")
            .method("GET")
            .apiKey("test-api-key")
            .build();

    RateLimitKey key = new RateLimitKey("test-key");
    RateLimitResult result =
        RateLimitResult.allowed(
            key,
            null, // no rule
            100L, // remaining tokens
            0L // no wait time
            );

    // when
    recorder.record(context, result);

    // then
    long count = eventCollection.countDocuments();
    assertEquals(1L, count, "Should have 1 event document");

    Document doc = eventCollection.find().first();
    assertNotNull(doc);
    assertTrue(doc.getBoolean("allowed"), "Event should be marked as allowed");
    assertEquals(100L, doc.getLong("remainingTokens"));
    assertEquals(0L, doc.getLong("nanosToWaitForRefill"));
    assertEquals("/api/users", doc.getString("endpoint"));
    assertEquals("GET", doc.getString("method"));
    assertEquals("192.168.1.100", doc.getString("clientIp"));
    assertNotNull(doc.getLong("timestamp"));
  }

  @Test
  @DisplayName("Should insert event document when recording rejected request")
  void record_shouldInsertEventDocumentForRejectedRequest() {
    // given
    RequestContext context =
        RequestContext.builder()
            .clientIp("192.168.1.200")
            .endpoint("/api/products")
            .method("POST")
            .userId("user-123")
            .build();

    RateLimitKey key = new RateLimitKey("rejected-key");
    RateLimitResult result =
        RateLimitResult.rejected(
            key,
            null, // no rule
            5000000000L // 5 seconds wait time in nanos
            );

    // when
    recorder.record(context, result);

    // then
    Document doc = eventCollection.find().first();
    assertNotNull(doc);
    assertFalse(doc.getBoolean("allowed"), "Event should be marked as rejected");
    assertEquals(0L, doc.getLong("remainingTokens"));
    assertEquals(5000000000L, doc.getLong("nanosToWaitForRefill"));
    assertEquals("/api/products", doc.getString("endpoint"));
    assertEquals("POST", doc.getString("method"));
    assertEquals("192.168.1.200", doc.getString("clientIp"));
  }

  @Test
  @DisplayName("Should record rule information when result contains matched rule")
  void record_shouldIncludeRuleInformation() {
    // given
    RateLimitRule rule =
        RateLimitRule.builder("test-rule")
            .name("Test Rate Limit Rule")
            .enabled(true)
            .scope(LimitScope.PER_API_KEY)
            .keyStrategyId("apiKey")
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 100).label("per-second").build())
            .ruleSetId("test-ruleset")
            .build();

    RequestContext context =
        RequestContext.builder().clientIp("10.0.0.1").endpoint("/api/test").method("GET").build();

    RateLimitKey key = new RateLimitKey("test-key");
    RateLimitResult result =
        RateLimitResult.builder(key)
            .allowed(true)
            .remainingTokens(50)
            .nanosToWaitForRefill(0)
            .matchedRule(rule)
            .build();

    // when
    recorder.record(context, result);

    // then
    Document doc = eventCollection.find().first();
    assertNotNull(doc);
    assertEquals("test-ruleset", doc.getString("ruleSetId"));
    assertEquals("test-rule", doc.getString("ruleId"));
  }

  @Test
  @DisplayName("Should handle null rule gracefully")
  void record_shouldHandleNullRuleGracefully() {
    // given
    RequestContext context = RequestContext.builder().clientIp("10.0.0.2").build();

    RateLimitKey key = new RateLimitKey("no-rule-key");
    RateLimitResult result = RateLimitResult.allowedWithoutRule();

    // when
    recorder.record(context, result);

    // then
    Document doc = eventCollection.find().first();
    assertNotNull(doc);
    assertNull(doc.getString("ruleSetId"), "RuleSetId should be null");
    assertNull(doc.getString("ruleId"), "RuleId should be null");
  }

  @Test
  @DisplayName("Should record custom attributes from RequestContext")
  void record_shouldIncludeCustomAttributes() {
    // given
    RequestContext context =
        RequestContext.builder()
            .clientIp("10.0.0.3")
            .endpoint("/api/custom")
            .method("PUT")
            .attribute("region", "us-east-1")
            .attribute("tenantId", "tenant-456")
            .attribute("requestId", "req-789")
            .build();

    RateLimitKey key = new RateLimitKey("custom-key");
    RateLimitResult result = RateLimitResult.allowed(key, null, 75L, 0L);

    // when
    recorder.record(context, result);

    // then
    Document doc = eventCollection.find().first();
    assertNotNull(doc);

    @SuppressWarnings("unchecked")
    Map<String, Object> recordedAttributes = (Map<String, Object>) doc.get("attributes");
    assertNotNull(recordedAttributes);
    assertEquals("us-east-1", recordedAttributes.get("region"));
    assertEquals("tenant-456", recordedAttributes.get("tenantId"));
    assertEquals("req-789", recordedAttributes.get("requestId"));
  }

  @Test
  @DisplayName("Should record multiple events in sequence")
  void record_shouldInsertMultipleEvents() {
    // given
    RequestContext context1 =
        RequestContext.builder().clientIp("10.0.0.4").endpoint("/api/event1").method("GET").build();

    RequestContext context2 =
        RequestContext.builder()
            .clientIp("10.0.0.5")
            .endpoint("/api/event2")
            .method("POST")
            .build();

    RateLimitKey key1 = new RateLimitKey("key1");
    RateLimitKey key2 = new RateLimitKey("key2");

    RateLimitResult result1 = RateLimitResult.allowed(key1, null, 90L, 0L);
    RateLimitResult result2 = RateLimitResult.rejected(key2, null, 1000000000L);

    // when
    recorder.record(context1, result1);
    recorder.record(context2, result2);

    // then
    long count = eventCollection.countDocuments();
    assertEquals(2L, count, "Should have 2 event documents");

    // Verify first event
    Document doc1 = eventCollection.find(new Document("clientIp", "10.0.0.4")).first();
    assertNotNull(doc1);
    assertTrue(doc1.getBoolean("allowed"));
    assertEquals("/api/event1", doc1.getString("endpoint"));

    // Verify second event
    Document doc2 = eventCollection.find(new Document("clientIp", "10.0.0.5")).first();
    assertNotNull(doc2);
    assertFalse(doc2.getBoolean("allowed"));
    assertEquals("/api/event2", doc2.getString("endpoint"));
  }

  @Test
  @DisplayName("Should record timestamp correctly")
  void record_shouldIncludeValidTimestamp() {
    // given
    long beforeRecording = Instant.now().toEpochMilli();

    RequestContext context = RequestContext.builder().clientIp("10.0.0.6").build();

    RateLimitKey key = new RateLimitKey("timestamp-key");
    RateLimitResult result = RateLimitResult.allowed(key, null, 100L, 0L);

    // when
    recorder.record(context, result);

    long afterRecording = Instant.now().toEpochMilli();

    // then
    Document doc = eventCollection.find().first();
    assertNotNull(doc);

    long recordedTimestamp = doc.getLong("timestamp");
    assertTrue(
        recordedTimestamp >= beforeRecording, "Timestamp should be >= time before recording");
    assertTrue(recordedTimestamp <= afterRecording, "Timestamp should be <= time after recording");
  }

  @Test
  @DisplayName("Should handle all RequestContext fields")
  void record_shouldIncludeAllRequestContextFields() {
    // given
    RequestContext context =
        RequestContext.builder()
            .clientIp("203.0.113.100")
            .userId("user-999")
            .apiKey("api-key-xyz")
            .endpoint("/api/complete")
            .method("DELETE")
            .attribute("custom1", "value1")
            .attribute("custom2", 42)
            .build();

    RateLimitKey key = new RateLimitKey("complete-key");
    RateLimitResult result = RateLimitResult.allowed(key, null, 25L, 0L);

    // when
    recorder.record(context, result);

    // then
    Document doc = eventCollection.find().first();
    assertNotNull(doc);
    assertEquals("203.0.113.100", doc.getString("clientIp"));
    assertEquals("/api/complete", doc.getString("endpoint"));
    assertEquals("DELETE", doc.getString("method"));

    @SuppressWarnings("unchecked")
    Map<String, Object> attributes = (Map<String, Object>) doc.get("attributes");
    assertNotNull(attributes);
    assertEquals("value1", attributes.get("custom1"));
    assertEquals(42, attributes.get("custom2"));
  }
}
