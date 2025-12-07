package org.fluxgate.adapter.mongo.event;

import com.mongodb.client.MongoCollection;
import java.time.Instant;
import org.bson.Document;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitResult;

public class MongoRateLimitMetricsRecorder implements RateLimitMetricsRecorder {

  private final MongoCollection<Document> eventCollection;

  public MongoRateLimitMetricsRecorder(MongoCollection<Document> eventCollection) {
    this.eventCollection = eventCollection;
  }

  @Override
  public void record(RequestContext context, RateLimitResult result) {

    String ruleSetId = null;
    String ruleId = null;
    if (result.getMatchedRule() != null) {
      ruleSetId = result.getMatchedRule().getRuleSetIdOrNull();
      ruleId = result.getMatchedRule().getId(); // Omit or adjust if this method doesn't exist
    }

    Document doc =
        new Document()
            .append("timestamp", Instant.now().toEpochMilli())
            .append("allowed", result.isAllowed())
            .append("remainingTokens", result.getRemainingTokens())
            .append("nanosToWaitForRefill", result.getNanosToWaitForRefill())
            .append("ruleSetId", ruleSetId)
            .append("ruleId", ruleId)
            .append("endpoint", context.getEndpoint())
            .append("method", context.getMethod())
            .append("clientIp", context.getClientIp())
            .append("attributes", context.getAttributes());

    eventCollection.insertOne(doc);
  }
}
