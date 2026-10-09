package org.fluxgate.adapter.mongo.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.mongodb.client.MongoCollection;
import java.util.Date;
import org.bson.Document;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link MongoRateLimitMetricsRecorder} (N-12).
 *
 * <p>The recorder writes one document per request, so what it puts in that document is what a dump
 * of the event collection contains. These tests pin the API key to a fingerprint and the retention
 * field to a BSON date.
 */
@ExtendWith(MockitoExtension.class)
class MongoRateLimitMetricsRecorderTest {

  private static final String API_KEY = "unit-test-only-api-key";

  @Mock private MongoCollection<Document> eventCollection;

  private static RequestContext context() {
    return RequestContext.builder()
        .clientIp("192.0.2.10")
        .userId("user-123")
        .apiKey(API_KEY)
        .endpoint("/api/users")
        .method("GET")
        .build();
  }

  private static RateLimitResult result() {
    return RateLimitResult.allowed(new RateLimitKey("test-key"), null, 100L, 0L);
  }

  private Document recordedDocument() {
    ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
    verify(eventCollection).insertOne(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("The API key is stored as a fingerprint, never in clear")
  void shouldFingerprintTheApiKey() {
    new MongoRateLimitMetricsRecorder(eventCollection).record(context(), result());

    Document doc = recordedDocument();
    assertThat(doc.getString("apiKey"))
        .isNotNull()
        .doesNotContain(API_KEY)
        .startsWith("sha256:")
        .hasSize("sha256:".length() + 16)
        .isEqualTo(MongoRateLimitMetricsRecorder.fingerprint(API_KEY));
  }

  @Test
  @DisplayName("The user id is stored as-is unless fingerprinting is switched on")
  void shouldKeepTheUserIdByDefault() {
    MongoRateLimitMetricsRecorder recorder = new MongoRateLimitMetricsRecorder(eventCollection);

    assertThat(recorder.isFingerprintUserId()).isFalse();

    recorder.record(context(), result());

    assertThat(recordedDocument().getString("userId")).isEqualTo("user-123");
  }

  @Test
  @DisplayName("The user id can be fingerprinted too")
  void shouldFingerprintTheUserIdWhenAsked() {
    MongoRateLimitMetricsRecorder recorder =
        new MongoRateLimitMetricsRecorder(eventCollection, true);

    assertThat(recorder.isFingerprintUserId()).isTrue();

    recorder.record(context(), result());

    Document doc = recordedDocument();
    assertThat(doc.getString("userId"))
        .isEqualTo(MongoRateLimitMetricsRecorder.fingerprint("user-123"));
    assertThat(doc.getString("clientIp"))
        .as("the client IP stays readable; it is what an operator correlates on")
        .isEqualTo("192.0.2.10");
  }

  @Test
  @DisplayName("createdAt is a BSON date, which is the only thing a TTL index can expire")
  void shouldWriteADateForTheTtlIndex() {
    long before = System.currentTimeMillis();

    new MongoRateLimitMetricsRecorder(eventCollection).record(context(), result());

    Document doc = recordedDocument();
    assertThat(doc.get("createdAt")).isInstanceOf(Date.class);
    assertThat(doc.getDate("createdAt").getTime())
        .isBetween(before - 1_000L, System.currentTimeMillis() + 1_000L);
    assertThat(doc.getString("timestampIso")).isNotNull();
  }

  @Test
  @DisplayName("A missing credential fingerprints to null rather than to a constant hash")
  void shouldNotFingerprintAnAbsentValue() {
    assertThat(MongoRateLimitMetricsRecorder.fingerprint(null)).isNull();
    assertThat(MongoRateLimitMetricsRecorder.fingerprint("")).isNull();

    RequestContext anonymous = RequestContext.builder().clientIp("192.0.2.11").build();
    new MongoRateLimitMetricsRecorder(eventCollection).record(anonymous, result());

    assertThat(recordedDocument().getString("apiKey")).isNull();
  }

  @Test
  @DisplayName("The same key always produces the same fingerprint, different keys do not collide")
  void shouldFingerprintDeterministically() {
    assertThat(MongoRateLimitMetricsRecorder.fingerprint(API_KEY))
        .isEqualTo(MongoRateLimitMetricsRecorder.fingerprint(API_KEY))
        .isNotEqualTo(MongoRateLimitMetricsRecorder.fingerprint(API_KEY + "x"));
  }
}
