package org.fluxgate.adapter.mongo.event;

import com.mongodb.client.MongoCollection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Map;
import org.bson.Document;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitResult;

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
 */
public class MongoRateLimitMetricsRecorder implements RateLimitMetricsRecorder {

  /** Number of hex characters of the SHA-256 digest kept in a fingerprint. */
  public static final int FINGERPRINT_HEX_LENGTH = 16;

  /** Prefix marking a value as a fingerprint rather than the value itself. */
  public static final String FINGERPRINT_PREFIX = "sha256:";

  private static final DateTimeFormatter ISO_FORMATTER =
      DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

  private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

  private final MongoCollection<Document> eventCollection;
  private final boolean fingerprintUserId;

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
    this.eventCollection = eventCollection;
    this.fingerprintUserId = fingerprintUserId;
  }

  /**
   * Whether the user id is stored as a fingerprint.
   *
   * @return true when {@code userId} is fingerprinted
   */
  public boolean isFingerprintUserId() {
    return fingerprintUserId;
  }

  @Override
  public void record(RequestContext context, RateLimitResult result) {
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

    eventCollection.insertOne(doc);
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

  /** Converts headers map to BSON Document. */
  private Document convertHeaders(Map<String, String> headers) {
    if (headers == null || headers.isEmpty()) {
      return new Document();
    }
    Document doc = new Document();
    headers.forEach(
        (key, value) -> {
          if (value != null) {
            doc.append(key, value);
          }
        });
    return doc;
  }

  /** Converts attributes map to BSON Document. */
  private Document convertAttributes(Map<String, Object> attributes) {
    if (attributes == null || attributes.isEmpty()) {
      return new Document();
    }
    Document doc = new Document();
    attributes.forEach(
        (key, value) -> {
          if (value != null) {
            doc.append(key, value);
          }
        });
    return doc;
  }
}
