package org.fluxgate.adapter.mongo.event;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.InsertManyOptions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.bson.Document;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB implementation of RateLimitMetricsRecorder.
 *
 * <p>Stores rate limit events with comprehensive tracking information including:
 *
 * <ul>
 *   <li>Rate limit decision details (allowed, tokens, policy)
 *   <li>Request metadata (endpoint, method)
 *   <li>Client identification (IP, userId, apiKey fingerprint)
 *   <li>HTTP headers (collected in headers subdocument)
 *   <li>Custom attributes
 * </ul>
 *
 * <p><strong>The API key is never stored in clear.</strong> It is a credential, and this recorder
 * writes one document per request: a dump of the event collection used to be a dump of every API
 * key that had passed through the gateway. What is stored instead is {@code sha256:} plus the first
 * {@value #FINGERPRINT_HEX_LENGTH} hex characters of its SHA-256, which still correlates the events
 * of one caller but cannot be replayed against the API. {@code userId} can be fingerprinted the
 * same way, off by default because it is usually the field analytics are built on; {@code clientIp}
 * is stored as-is.
 *
 * <p>Every document also carries {@code createdAt} as a BSON date, which is what a TTL index can
 * expire. Without a retention window this collection grows forever, and everything above about
 * credentials and personal data applies for as long as it is kept.
 *
 * <p><strong>Never on the request path.</strong> {@link #record} only builds the document and
 * offers it to a bounded queue; a single daemon thread writes the queue to MongoDB in batches. A
 * slow or unreachable MongoDB therefore cannot block or fail a request: when the queue is full the
 * event is dropped and counted ({@link #getDroppedEvents()}), and a failed write is logged at WARN
 * (at most every ten seconds) and counted ({@link #getFailedEvents()}). This is an audit trail, not
 * a guarantee - use the Micrometer recorder for numbers that must add up. {@link #close()} stops
 * the writer after flushing what is queued, for up to five seconds.
 *
 * <p>Header and attribute names become BSON field names, so {@code .} and {@code $} in them (which
 * MongoDB treats as path and operator syntax) and NUL (which BSON cannot store in a name) are
 * percent-escaped as {@code %2E}, {@code %24} and {@code %00}; {@code %} itself becomes {@code %25}
 * and an empty or null name is stored as a lone {@code %}. The escaping is reversible, so two
 * different names (say {@code a.b} and {@code a_b}) never share a field.
 */
public class MongoRateLimitMetricsRecorder implements RateLimitMetricsRecorder, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(MongoRateLimitMetricsRecorder.class);

  /** Default number of events that may wait for the writer before new ones are dropped. */
  public static final int DEFAULT_QUEUE_CAPACITY = 10_000;

  /** Largest batch written with one {@code insertMany}. */
  private static final int MAX_BATCH_SIZE = 500;

  /** Minimum interval between two WARN lines about dropped or failed events. */
  private static final long WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

  /** How long {@link #close()} waits for queued events to be written. */
  private static final Duration CLOSE_FLUSH_TIMEOUT = Duration.ofSeconds(5);

  /** Number of hex characters of the SHA-256 digest kept in a fingerprint. */
  public static final int FINGERPRINT_HEX_LENGTH = 16;

  /** Prefix marking a value as a fingerprint rather than the value itself. */
  public static final String FINGERPRINT_PREFIX = "sha256:";

  private static final DateTimeFormatter ISO_FORMATTER =
      DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

  private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

  private final MongoCollection<Document> eventCollection;
  private final boolean fingerprintUserId;

  private final BlockingQueue<Document> queue;
  private final Thread writer;
  private volatile boolean closed;

  /** Set when {@link #close()} gives up waiting: the writer stops without draining the queue. */
  private volatile boolean abandoned;

  /** Events accepted by {@link #record} and not yet written or failed. */
  private final AtomicLong pendingEvents = new AtomicLong();

  private final AtomicLong writtenEvents = new AtomicLong();
  private final AtomicLong droppedEvents = new AtomicLong();
  private final AtomicLong failedEvents = new AtomicLong();
  private final AtomicLong lastDropWarn = new AtomicLong(System.nanoTime() - WARN_INTERVAL_NANOS);
  private final AtomicLong lastFailureWarn =
      new AtomicLong(System.nanoTime() - WARN_INTERVAL_NANOS);

  /**
   * Creates a recorder that fingerprints the API key and stores the user id as-is.
   *
   * @param eventCollection the collection events are inserted into
   */
  public MongoRateLimitMetricsRecorder(MongoCollection<Document> eventCollection) {
    this(eventCollection, false);
  }

  /**
   * Creates a recorder with explicit control over user id fingerprinting.
   *
   * @param eventCollection the collection events are inserted into
   * @param fingerprintUserId whether {@code userId} is stored as a fingerprint as well
   */
  public MongoRateLimitMetricsRecorder(
      MongoCollection<Document> eventCollection, boolean fingerprintUserId) {
    this(eventCollection, fingerprintUserId, DEFAULT_QUEUE_CAPACITY);
  }

  /**
   * Creates a recorder with an explicit queue capacity.
   *
   * @param eventCollection the collection events are inserted into
   * @param fingerprintUserId whether {@code userId} is stored as a fingerprint as well
   * @param queueCapacity events that may wait for the writer before new ones are dropped
   * @since 0.4.0
   */
  public MongoRateLimitMetricsRecorder(
      MongoCollection<Document> eventCollection, boolean fingerprintUserId, int queueCapacity) {
    this.eventCollection =
        Objects.requireNonNull(eventCollection, "eventCollection must not be null");
    this.fingerprintUserId = fingerprintUserId;
    if (queueCapacity < 1) {
      throw new IllegalArgumentException("queueCapacity must be positive: " + queueCapacity);
    }
    this.queue = new ArrayBlockingQueue<>(queueCapacity);
    this.writer = new Thread(this::writeLoop, "fluxgate-mongo-events");
    this.writer.setDaemon(true);
    this.writer.start();
  }

  /**
   * Whether the user id is stored as a fingerprint.
   *
   * @return true when {@code userId} is fingerprinted
   */
  public boolean isFingerprintUserId() {
    return fingerprintUserId;
  }

  /**
   * Queues the event for the background writer. Never blocks and never throws.
   *
   * @param context the request context
   * @param result the rate limit result
   */
  @Override
  public void record(RequestContext context, RateLimitResult result) {
    if (closed) {
      droppedEvents.incrementAndGet();
      return;
    }
    Document doc;
    try {
      doc = buildDocument(context, result);
    } catch (RuntimeException e) {
      failedEvents.incrementAndGet();
      warnFailure("Could not build a rate limit event document: {}", e);
      return;
    }

    pendingEvents.incrementAndGet();
    if (queue.offer(doc)) {
      // close() may have run between the check above and the offer, and the writer may already be
      // gone: take the event back and count it as dropped, so it neither lingers nor stalls
      // flush().
      if (closed && queue.remove(doc)) {
        pendingEvents.decrementAndGet();
        droppedEvents.incrementAndGet();
      }
    } else {
      pendingEvents.decrementAndGet();
      long dropped = droppedEvents.incrementAndGet();
      if (shouldWarn(lastDropWarn)) {
        log.warn(
            "Rate limit event queue is full ({} events); dropping events ({} dropped so far). "
                + "MongoDB is slow or unreachable, or the event rate exceeds what it absorbs.",
            queue.remainingCapacity() + queue.size(),
            dropped);
      }
    }
  }

  private Document buildDocument(RequestContext context, RateLimitResult result) {
    Instant now = Instant.now();

    // Extract rule information
    String ruleSetId = null;
    String ruleId = null;
    String ruleName = null;
    String onLimitExceedPolicy = null;
    String keyStrategyId = null;

    if (result.getMatchedRule() != null) {
      ruleSetId = result.getMatchedRule().getRuleSetIdOrNull();
      ruleId = result.getMatchedRule().getId();
      ruleName = result.getMatchedRule().getName();
      keyStrategyId = result.getMatchedRule().getKeyStrategyId();

      OnLimitExceedPolicy policy = result.getMatchedRule().getOnLimitExceedPolicy();
      if (policy != null) {
        onLimitExceedPolicy = policy.name();
      }
    }

    // Build event document with comprehensive tracking info
    Document doc =
        new Document()
            // Timestamp information; createdAt is the BSON date a TTL index expires on
            .append("timestamp", now.toEpochMilli())
            .append("timestampIso", ISO_FORMATTER.format(now))
            .append("createdAt", Date.from(now))

            // Rate limit decision
            .append("allowed", result.isAllowed())
            .append("remainingTokens", result.getRemainingTokens())
            .append("nanosToWaitForRefill", result.getNanosToWaitForRefill())
            .append("retryAfterMs", result.getNanosToWaitForRefill() / 1_000_000)

            // Rule information
            .append("ruleSetId", ruleSetId)
            .append("ruleId", ruleId)
            .append("ruleName", ruleName)
            .append("onLimitExceedPolicy", onLimitExceedPolicy)
            .append("keyStrategyId", keyStrategyId)

            // Request information
            .append("endpoint", context.getEndpoint())
            .append("method", context.getMethod())

            // Client identification; the API key is a credential and is never stored in clear
            .append("clientIp", context.getClientIp())
            .append(
                "userId",
                fingerprintUserId ? fingerprint(context.getUserId()) : context.getUserId())
            .append("apiKey", fingerprint(context.getApiKey()))

            // HTTP headers (as subdocument)
            .append("headers", convertHeaders(context.getHeaders()))

            // Custom attributes
            .append("attributes", convertAttributes(context.getAttributes()));
    return doc;
  }

  /** Background writer: drains the queue in batches until closed and empty. */
  private void writeLoop() {
    List<Document> batch = new ArrayList<>(MAX_BATCH_SIZE);
    while (!abandoned && (!closed || !queue.isEmpty())) {
      Document first;
      try {
        first = queue.poll(100, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        // close() interrupts only after its flush window: give up on what is left
        Thread.currentThread().interrupt();
        break;
      }
      if (first == null) {
        continue;
      }
      batch.clear();
      batch.add(first);
      queue.drainTo(batch, MAX_BATCH_SIZE - 1);
      writeBatch(batch);
    }
    long abandoned = queue.size();
    if (abandoned > 0) {
      queue.clear();
      droppedEvents.addAndGet(abandoned);
      pendingEvents.addAndGet(-abandoned);
      log.warn("Dropped {} queued rate limit events on shutdown", abandoned);
    }
  }

  private void writeBatch(List<Document> batch) {
    int size = batch.size();
    try {
      eventCollection.insertMany(new ArrayList<>(batch), new InsertManyOptions().ordered(false));
      writtenEvents.addAndGet(size);
    } catch (MongoBulkWriteException e) {
      int failed = e.getWriteErrors().size();
      failedEvents.addAndGet(failed);
      writtenEvents.addAndGet(size - failed);
      warnFailure("Could not write " + failed + " of " + size + " rate limit events: {}", e);
    } catch (RuntimeException e) {
      failedEvents.addAndGet(size);
      warnFailure("Could not write " + size + " rate limit events to MongoDB: {}", e);
    } finally {
      pendingEvents.addAndGet(-size);
    }
  }

  private void warnFailure(String message, RuntimeException e) {
    if (shouldWarn(lastFailureWarn)) {
      log.warn(message + " ({} failed so far)", e.toString(), failedEvents.get());
    } else {
      log.debug(message, e.toString());
    }
  }

  private static boolean shouldWarn(AtomicLong lastWarn) {
    long now = System.nanoTime();
    long last = lastWarn.get();
    return now - last >= WARN_INTERVAL_NANOS && lastWarn.compareAndSet(last, now);
  }

  /**
   * Waits until every event accepted so far has been written or has failed.
   *
   * @param timeout how long to wait at most
   * @return true when nothing is pending any more
   * @throws InterruptedException if interrupted while waiting
   * @since 0.4.0
   */
  public boolean flush(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (pendingEvents.get() > 0) {
      if (System.nanoTime() >= deadline) {
        return false;
      }
      Thread.sleep(5);
    }
    return true;
  }

  /**
   * Stops accepting events, writes what is queued (for up to five seconds) and stops the writer.
   * Events still queued after that are dropped and counted.
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      writer.join(CLOSE_FLUSH_TIMEOUT.toMillis());
      if (writer.isAlive()) {
        abandoned = true;
        writer.interrupt();
        writer.join(1_000);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (!writer.isAlive()) {
      dropLeftovers();
    }
  }

  /** Counts events still queued once the writer has stopped as dropped. */
  private void dropLeftovers() {
    List<Document> leftovers = new ArrayList<>();
    int count = queue.drainTo(leftovers);
    if (count > 0) {
      droppedEvents.addAndGet(count);
      pendingEvents.addAndGet(-count);
      log.warn("Dropped {} rate limit events queued after shutdown", count);
    }
  }

  /**
   * Returns how many events were written to MongoDB.
   *
   * @return the written event count
   * @since 0.4.0
   */
  public long getWrittenEvents() {
    return writtenEvents.get();
  }

  /**
   * Returns how many events were dropped because the queue was full or the recorder was closed.
   *
   * @return the dropped event count
   * @since 0.4.0
   */
  public long getDroppedEvents() {
    return droppedEvents.get();
  }

  /**
   * Returns how many events could not be built or written.
   *
   * @return the failed event count
   * @since 0.4.0
   */
  public long getFailedEvents() {
    return failedEvents.get();
  }

  /**
   * Returns how many accepted events are still waiting to be written.
   *
   * @return the pending event count
   * @since 0.4.0
   */
  public long getPendingEvents() {
    return Math.max(0L, pendingEvents.get());
  }

  /**
   * Returns a non-reversible fingerprint of a secret, or null when there is nothing to fingerprint.
   *
   * <p>Truncating the digest is deliberate: the full hash of a short or low-entropy value is a
   * dictionary attack away from the value itself, and correlating one caller's events only needs
   * enough bits to be collision-free in practice.
   *
   * @param value the value to fingerprint, may be null
   * @return {@code sha256:<first 16 hex chars>}, or null
   */
  static String fingerprint(String value) {
    if (value == null || value.isEmpty()) {
      return null;
    }

    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(FINGERPRINT_PREFIX.length() + FINGERPRINT_HEX_LENGTH);
      sb.append(FINGERPRINT_PREFIX);
      for (int i = 0; i < FINGERPRINT_HEX_LENGTH / 2; i++) {
        sb.append(HEX_DIGITS[(hash[i] >> 4) & 0x0F]).append(HEX_DIGITS[hash[i] & 0x0F]);
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandatory on every JRE; storing the value in clear instead is not an option.
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /**
   * Makes a header or attribute name safe as a BSON field name with a reversible escape: {@code %}
   * becomes {@code %25}, {@code .} {@code %2E}, {@code $} {@code %24} and NUL {@code %00}. An empty
   * or null name becomes a lone {@code %}, which no escaped non-empty name can produce.
   */
  static String sanitizeFieldName(String key) {
    if (key == null || key.isEmpty()) {
      return "%";
    }
    int i = 0;
    while (i < key.length() && !needsEscape(key.charAt(i))) {
      i++;
    }
    if (i == key.length()) {
      return key;
    }
    StringBuilder sb = new StringBuilder(key.length() + 8).append(key, 0, i);
    for (; i < key.length(); i++) {
      char c = key.charAt(i);
      switch (c) {
        case '%':
          sb.append("%25");
          break;
        case '.':
          sb.append("%2E");
          break;
        case '$':
          sb.append("%24");
          break;
        case '\u0000':
          sb.append("%00");
          break;
        default:
          sb.append(c);
      }
    }
    return sb.toString();
  }

  private static boolean needsEscape(char c) {
    return c == '%' || c == '.' || c == '$' || c == '\u0000';
  }

  /** Converts headers map to BSON Document. */
  private Document convertHeaders(Map<String, String> headers) {
    if (headers == null || headers.isEmpty()) {
      return new Document();
    }
    Document doc = new Document();
    headers.forEach(
        (key, value) -> {
          if (value != null) {
            doc.append(sanitizeFieldName(key), value);
          }
        });
    return doc;
  }

  /**
   * Converts attributes map to BSON Document.
   *
   * <p>Values the BSON codec handles natively are kept; anything else is stored as its {@code
   * toString()}, so an attribute of an arbitrary type cannot make the whole batch unwritable.
   */
  private Document convertAttributes(Map<String, Object> attributes) {
    if (attributes == null || attributes.isEmpty()) {
      return new Document();
    }
    Document doc = new Document();
    attributes.forEach(
        (key, value) -> {
          if (value != null) {
            doc.append(sanitizeFieldName(key), toBsonValue(value));
          }
        });
    return doc;
  }

  private static Object toBsonValue(Object value) {
    if (value instanceof String
        || value instanceof Integer
        || value instanceof Long
        || value instanceof Double
        || value instanceof Boolean
        || value instanceof Date) {
      return value;
    }
    return String.valueOf(value);
  }
}
