package org.fluxgate.adapter.mongo.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.InsertManyOptions;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

  /** The recorder writes on its own thread: wait for the batch and return its only document. */
  @SuppressWarnings("unchecked")
  private Document recordedDocument() {
    ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
    verify(eventCollection, timeout(5_000))
        .insertMany(captor.capture(), any(InsertManyOptions.class));
    assertThat(captor.getValue()).hasSize(1);
    return captor.getValue().get(0);
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

  @Test
  @DisplayName("A MongoDB failure never reaches the caller; it is counted")
  void shouldSwallowAndCountWriteFailures() throws Exception {
    when(eventCollection.insertMany(anyList(), any(InsertManyOptions.class)))
        .thenThrow(new MongoTimeoutException("no server"));
    try (MongoRateLimitMetricsRecorder recorder =
        new MongoRateLimitMetricsRecorder(eventCollection)) {
      assertThatCode(() -> recorder.record(context(), result())).doesNotThrowAnyException();

      assertThat(recorder.flush(Duration.ofSeconds(5))).isTrue();
      assertThat(recorder.getFailedEvents()).isEqualTo(1L);
      assertThat(recorder.getWrittenEvents()).isZero();
    }
  }

  @Test
  @DisplayName("A stalled MongoDB neither blocks record() nor grows the queue: events are dropped")
  void shouldDropInsteadOfBlockingWhenTheQueueIsFull() throws Exception {
    CountDownLatch writerBlocked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              writerBlocked.countDown();
              release.await(10, TimeUnit.SECONDS);
              return null;
            })
        .when(eventCollection)
        .insertMany(anyList(), any(InsertManyOptions.class));

    try (MongoRateLimitMetricsRecorder recorder =
        new MongoRateLimitMetricsRecorder(eventCollection, false, 2)) {
      // Park the writer on a one-event batch first, so the rest of the run is deterministic:
      // the queue (capacity 2) fills and every further event is dropped.
      recorder.record(context(), result());
      assertThat(writerBlocked.await(5, TimeUnit.SECONDS)).isTrue();

      long start = System.nanoTime();
      for (int i = 0; i < 49; i++) {
        recorder.record(context(), result());
      }
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      assertThat(elapsedMillis).isLessThan(500L);
      assertThat(recorder.getDroppedEvents()).isEqualTo(47L);
      // two queued events plus the one the parked writer is holding
      assertThat(recorder.getPendingEvents()).isEqualTo(3L);

      release.countDown();
      assertThat(recorder.flush(Duration.ofSeconds(5))).isTrue();
      assertThat(recorder.getWrittenEvents() + recorder.getDroppedEvents()).isEqualTo(50L);
    }
  }

  @Test
  @DisplayName("'.', '$', NUL and '%' in header and attribute names are percent-escaped")
  void shouldEscapeFieldNames() {
    assertThat(MongoRateLimitMetricsRecorder.sanitizeFieldName("x.y")).isEqualTo("x%2Ey");
    assertThat(MongoRateLimitMetricsRecorder.sanitizeFieldName("$where")).isEqualTo("%24where");
    assertThat(MongoRateLimitMetricsRecorder.sanitizeFieldName("a\u0000b")).isEqualTo("a%00b");
    assertThat(MongoRateLimitMetricsRecorder.sanitizeFieldName("100%")).isEqualTo("100%25");
    assertThat(MongoRateLimitMetricsRecorder.sanitizeFieldName("%2E")).isEqualTo("%252E");
    assertThat(MongoRateLimitMetricsRecorder.sanitizeFieldName("plain")).isEqualTo("plain");
    assertThat(MongoRateLimitMetricsRecorder.sanitizeFieldName("a_b")).isEqualTo("a_b");
    assertThat(MongoRateLimitMetricsRecorder.sanitizeFieldName("")).isEqualTo("%");
    assertThat(MongoRateLimitMetricsRecorder.sanitizeFieldName(null)).isEqualTo("%");

    RequestContext ctx =
        RequestContext.builder()
            .clientIp("192.0.2.10")
            .header("a.b", "1")
            .attribute(
                "$set",
                new Object() {
                  @Override
                  public String toString() {
                    return "custom";
                  }
                })
            .build();
    MongoRateLimitMetricsRecorder recorder = new MongoRateLimitMetricsRecorder(eventCollection);
    recorder.record(ctx, result());

    Document doc = recordedDocument();
    assertThat(doc.get("headers", Document.class).getString("a%2Eb")).isEqualTo("1");
    assertThat(doc.get("attributes", Document.class).get("%24set")).isEqualTo("custom");
    recorder.close();
  }

  @Test
  @DisplayName("Escaping is injective: 'a.b' and 'a_b' (and '%2E' vs '.') stay distinct fields")
  void shouldNotCollideEscapedFieldNames() {
    assertThat(MongoRateLimitMetricsRecorder.sanitizeFieldName("a.b"))
        .isNotEqualTo(MongoRateLimitMetricsRecorder.sanitizeFieldName("a_b"));
    assertThat(MongoRateLimitMetricsRecorder.sanitizeFieldName("a%2Eb"))
        .isNotEqualTo(MongoRateLimitMetricsRecorder.sanitizeFieldName("a.b"));

    RequestContext ctx =
        RequestContext.builder()
            .clientIp("192.0.2.10")
            .header("a.b", "dot")
            .header("a_b", "underscore")
            .build();
    MongoRateLimitMetricsRecorder recorder = new MongoRateLimitMetricsRecorder(eventCollection);
    recorder.record(ctx, result());

    Document headers = recordedDocument().get("headers", Document.class);
    assertThat(headers.getString("a%2Eb")).isEqualTo("dot");
    assertThat(headers.getString("a_b")).isEqualTo("underscore");
    recorder.close();
  }

  @Test
  @DisplayName(
      "An event offered while close() runs is counted as dropped and flush() does not hang")
  void shouldDropAnEventThatRacesClose() throws Exception {
    MongoRateLimitMetricsRecorder recorder = new MongoRateLimitMetricsRecorder(eventCollection);
    // close() runs while record() builds the document: after its closed check, before the offer.
    RequestContext racing =
        RequestContext.builder()
            .clientIp("192.0.2.10")
            .attribute(
                "trigger",
                new Object() {
                  @Override
                  public String toString() {
                    recorder.close();
                    return "closed";
                  }
                })
            .build();

    recorder.record(racing, result());

    assertThat(recorder.getDroppedEvents()).isEqualTo(1L);
    assertThat(recorder.getPendingEvents()).isZero();
    assertThat(recorder.flush(Duration.ofMillis(200))).isTrue();
    assertThat(recorder.getWrittenEvents()).isZero();
  }

  @Test
  @DisplayName("close() writes what is queued and later events are dropped")
  void shouldFlushOnCloseAndDropAfterwards() {
    MongoRateLimitMetricsRecorder recorder = new MongoRateLimitMetricsRecorder(eventCollection);
    recorder.record(context(), result());
    recorder.close();

    assertThat(recorder.getWrittenEvents()).isEqualTo(1L);
    recorder.record(context(), result());
    assertThat(recorder.getDroppedEvents()).isEqualTo(1L);
  }
}
