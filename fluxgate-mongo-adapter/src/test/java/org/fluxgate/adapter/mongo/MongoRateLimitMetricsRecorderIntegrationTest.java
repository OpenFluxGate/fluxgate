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
import org.fluxgate.adapter.mongo.support.MongoContainerSupport;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.junit.jupiter.api.*;

/**
 * Integration tests for {@link MongoRateLimitMetricsRecorder} against a real MongoDB.
 *
 * <p>The target comes from {@link MongoContainerSupport}: a supplied {@code FLUXGATE_MONGO_URI}, a
 * Testcontainers {@code mongo:7.0}, or the test is skipped. Each test gets its own collection, so
 * nothing a shared MongoDB already holds is dropped.
 */
class MongoRateLimitMetricsRecorderIntegrationTest {

  private MongoClient client;
  private MongoDatabase database;
  private MongoCollection<Document> eventCollection;
  private MongoRateLimitMetricsRecorder recorder;

  @BeforeEach
  void setUp() {
    client = MongoClients.create(MongoContainerSupport.mongoUri());
    database = client.getDatabase(MongoContainerSupport.databaseName());

    // A collection of this test's own, so a shared MongoDB keeps its data
    eventCollection =
        database.getCollection(MongoContainerSupport.uniqueCollectionName("rate_limit_events"));

    recorder = new MongoRateLimitMetricsRecorder(eventCollection);
  }

  @AfterEach
  void tearDown() {
    if (eventCollection != null) {
      eventCollection.drop();
    }
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

  @Test
  @DisplayName("Should record HTTP headers from RequestContext")
  void record_shouldIncludeHttpHeaders() {
    // given
    RequestContext context =
        RequestContext.builder()
            .clientIp("10.0.0.10")
            .endpoint("/api/headers")
            .method("GET")
            .header("User-Agent", "Mozilla/5.0 Test Browser")
            .header("Accept", "application/json")
            .header("X-Request-Id", "req-12345")
            .header("X-Forwarded-For", "192.168.1.1, 10.0.0.1")
            .build();

    RateLimitKey key = new RateLimitKey("headers-key");
    RateLimitResult result = RateLimitResult.allowed(key, null, 50L, 0L);

    // when
    recorder.record(context, result);

    // then
    Document doc = eventCollection.find().first();
    assertNotNull(doc);

    Document headers = doc.get("headers", Document.class);
    assertNotNull(headers, "Headers document should exist");
    assertEquals("Mozilla/5.0 Test Browser", headers.getString("User-Agent"));
    assertEquals("application/json", headers.getString("Accept"));
    assertEquals("req-12345", headers.getString("X-Request-Id"));
    assertEquals("192.168.1.1, 10.0.0.1", headers.getString("X-Forwarded-For"));
  }

  @Test
  @DisplayName("Should record onLimitExceedPolicy from matched rule")
  void record_shouldIncludeOnLimitExceedPolicy() {
    // given
    RateLimitRule rule =
        RateLimitRule.builder("wait-rule")
            .name("Wait For Refill Rule")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .keyStrategyId("ip")
            .onLimitExceedPolicy(OnLimitExceedPolicy.WAIT_FOR_REFILL)
            .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).label("per-minute").build())
            .ruleSetId("wait-ruleset")
            .build();

    RequestContext context =
        RequestContext.builder().clientIp("10.0.0.11").endpoint("/api/wait").method("POST").build();

    RateLimitKey key = new RateLimitKey("wait-key");
    RateLimitResult result =
        RateLimitResult.builder(key)
            .allowed(false)
            .remainingTokens(0)
            .nanosToWaitForRefill(5000000000L)
            .matchedRule(rule)
            .build();

    // when
    recorder.record(context, result);

    // then
    Document doc = eventCollection.find().first();
    assertNotNull(doc);
    assertEquals("WAIT_FOR_REFILL", doc.getString("onLimitExceedPolicy"));
    assertEquals("wait-ruleset", doc.getString("ruleSetId"));
    assertEquals("wait-rule", doc.getString("ruleId"));
    assertEquals("Wait For Refill Rule", doc.getString("ruleName"));
    assertEquals("ip", doc.getString("keyStrategyId"));
  }

  @Test
  @DisplayName("Should record retryAfterMs calculated from nanosToWaitForRefill")
  void record_shouldIncludeRetryAfterMs() {
    // given
    RequestContext context =
        RequestContext.builder().clientIp("10.0.0.12").endpoint("/api/retry").method("GET").build();

    RateLimitKey key = new RateLimitKey("retry-key");
    // 3.5 seconds in nanoseconds
    RateLimitResult result = RateLimitResult.rejected(key, null, 3500000000L);

    // when
    recorder.record(context, result);

    // then
    Document doc = eventCollection.find().first();
    assertNotNull(doc);
    assertEquals(3500000000L, doc.getLong("nanosToWaitForRefill"));
    assertEquals(3500L, doc.getLong("retryAfterMs"));
  }

  @Test
  @DisplayName("Should record timestampIso in ISO format")
  void record_shouldIncludeTimestampIso() {
    // given
    RequestContext context =
        RequestContext.builder().clientIp("10.0.0.13").endpoint("/api/iso").method("GET").build();

    RateLimitKey key = new RateLimitKey("iso-key");
    RateLimitResult result = RateLimitResult.allowed(key, null, 100L, 0L);

    // when
    recorder.record(context, result);

    // then
    Document doc = eventCollection.find().first();
    assertNotNull(doc);
    String timestampIso = doc.getString("timestampIso");
    assertNotNull(timestampIso, "timestampIso should be present");
    assertTrue(timestampIso.contains("T"), "timestampIso should be in ISO format");
    assertTrue(timestampIso.endsWith("Z"), "timestampIso should end with Z (UTC)");
  }

  @Test
  @DisplayName("Should handle empty headers gracefully")
  void record_shouldHandleEmptyHeaders() {
    // given
    RequestContext context =
        RequestContext.builder()
            .clientIp("10.0.0.14")
            .endpoint("/api/no-headers")
            .method("GET")
            .build();

    RateLimitKey key = new RateLimitKey("no-headers-key");
    RateLimitResult result = RateLimitResult.allowed(key, null, 100L, 0L);

    // when
    recorder.record(context, result);

    // then
    Document doc = eventCollection.find().first();
    assertNotNull(doc);
    Document headers = doc.get("headers", Document.class);
    assertNotNull(headers, "Headers document should exist even if empty");
    assertTrue(headers.isEmpty(), "Headers should be empty");
  }

  @Test
  @DisplayName("Should handle empty attributes gracefully")
  void record_shouldHandleEmptyAttributes() {
    // given
    RequestContext context =
        RequestContext.builder()
            .clientIp("10.0.0.15")
            .endpoint("/api/no-attrs")
            .method("GET")
            .build();

    RateLimitKey key = new RateLimitKey("no-attrs-key");
    RateLimitResult result = RateLimitResult.allowed(key, null, 100L, 0L);

    // when
    recorder.record(context, result);

    // then
    Document doc = eventCollection.find().first();
    assertNotNull(doc);
    Document attributes = doc.get("attributes", Document.class);
    assertNotNull(attributes, "Attributes document should exist even if empty");
    assertTrue(attributes.isEmpty(), "Attributes should be empty");
  }

  @Test
  @DisplayName("Should filter out null values from headers")
  void record_shouldFilterNullValuesFromHeaders() {
    // given
    // Note: RequestContext.Builder.header() ignores null values,
    // so this test verifies the convertHeaders() behavior indirectly
    RequestContext context =
        RequestContext.builder()
            .clientIp("10.0.0.21")
            .endpoint("/api/filter-null")
            .method("GET")
            .header("Valid-Header", "valid-value")
            .header("Null-Header", null) // this should be ignored by builder
            .build();

    RateLimitKey key = new RateLimitKey("filter-null-key");
    RateLimitResult result = RateLimitResult.allowed(key, null, 100L, 0L);

    // when
    recorder.record(context, result);

    // then
    Document doc = eventCollection.find().first();
    assertNotNull(doc);
    Document headers = doc.get("headers", Document.class);
    assertNotNull(headers);
    assertEquals("valid-value", headers.getString("Valid-Header"));
    assertFalse(headers.containsKey("Null-Header"), "Null header should not be stored");
  }

  @Test
  @DisplayName("Should store the API key as a fingerprint and createdAt as a BSON date (N-12)")
  void record_shouldStoreAFingerprintedApiKeyAndADateForTheTtlIndex() {
    // given
    RequestContext context =
        RequestContext.builder()
            .clientIp("198.51.100.7")
            .userId("user-fingerprint")
            .apiKey("it-test-only-api-key")
            .endpoint("/api/fingerprint")
            .method("GET")
            .build();

    RateLimitResult result = RateLimitResult.allowed(new RateLimitKey("fp-key"), null, 10L, 0L);

    // when
    recorder.record(context, result);

    // then
    Document doc = eventCollection.find().first();
    assertNotNull(doc);
    String storedApiKey = doc.getString("apiKey");
    assertNotNull(storedApiKey, "The apiKey field is still recorded");
    assertNotEquals(
        "it-test-only-api-key", storedApiKey, "A credential must not be stored in clear");
    assertTrue(storedApiKey.startsWith("sha256:"), "The apiKey is a fingerprint");
    assertEquals("user-fingerprint", doc.getString("userId"), "userId stays readable by default");
    assertEquals("198.51.100.7", doc.getString("clientIp"));

    // A TTL index only expires a BSON date; the ISO string would look configured and expire
    // nothing.
    assertTrue(doc.get("createdAt") instanceof java.util.Date, "createdAt must be a BSON date");
  }

  @Test
  @DisplayName("Should fingerprint the user id when the recorder is configured to")
  void record_shouldFingerprintTheUserIdWhenConfigured() {
    // given
    MongoRateLimitMetricsRecorder fingerprinting =
        new MongoRateLimitMetricsRecorder(eventCollection, true);
    RequestContext context =
        RequestContext.builder().clientIp("198.51.100.8").userId("user-secret").build();

    // when
    fingerprinting.record(context, RateLimitResult.allowedWithoutRule());

    // then
    Document doc = eventCollection.find().first();
    assertNotNull(doc);
    assertNotEquals("user-secret", doc.getString("userId"));
    assertTrue(doc.getString("userId").startsWith("sha256:"));
  }

  @Test
  @DisplayName("Should let MongoDB expire the events through a TTL index on createdAt")
  void record_shouldBeExpirableByATtlIndexOnCreatedAt() {
    // given
    eventCollection.createIndex(
        com.mongodb.client.model.Indexes.ascending("createdAt"),
        new com.mongodb.client.model.IndexOptions()
            .name("createdAt_ttl")
            .expireAfter(3600L, java.util.concurrent.TimeUnit.SECONDS));

    // when
    recorder.record(
        RequestContext.builder().clientIp("198.51.100.9").build(),
        RateLimitResult.allowedWithoutRule());

    // then: the index MongoDB accepted is a TTL index, and the field it covers is present
    boolean ttlIndexPresent = false;
    for (Document index : eventCollection.listIndexes()) {
      if ("createdAt_ttl".equals(index.getString("name"))) {
        ttlIndexPresent = true;
        assertEquals(3600L, index.get("expireAfterSeconds", Number.class).longValue());
      }
    }
    assertTrue(ttlIndexPresent, "MongoDB should have accepted the TTL index");
    assertNotNull(eventCollection.find().first().get("createdAt"));
  }
}
