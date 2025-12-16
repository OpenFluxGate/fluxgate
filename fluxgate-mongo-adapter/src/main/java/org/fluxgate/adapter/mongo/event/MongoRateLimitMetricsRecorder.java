package org.fluxgate.adapter.mongo.event;

import com.mongodb.client.MongoCollection;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
 *   <li>Client identification (IP, userId, apiKey)
 *   <li>HTTP headers (collected in headers subdocument)
 *   <li>Custom attributes
 * </ul>
 */
public class MongoRateLimitMetricsRecorder implements RateLimitMetricsRecorder {

  private static final DateTimeFormatter ISO_FORMATTER =
      DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

  private final MongoCollection<Document> eventCollection;

  public MongoRateLimitMetricsRecorder(MongoCollection<Document> eventCollection) {
    this.eventCollection = eventCollection;
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
            // Timestamp information
            .append("timestamp", now.toEpochMilli())
            .append("timestampIso", ISO_FORMATTER.format(now))

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

            // Client identification
            .append("clientIp", context.getClientIp())
            .append("userId", context.getUserId())
            .append("apiKey", context.getApiKey())

            // HTTP headers (as subdocument)
            .append("headers", convertHeaders(context.getHeaders()))

            // Custom attributes
            .append("attributes", convertAttributes(context.getAttributes()));

    eventCollection.insertOne(doc);
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
