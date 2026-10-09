package org.fluxgate.control.notify;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Objects;

/**
 * Message payload for rule change notifications.
 *
 * <p>This class is serialized to JSON and published via Redis Pub/Sub. FluxGate instances
 * subscribed to the channel will deserialize this message and take appropriate action.
 */
public class RuleChangeMessage {

  private final String ruleSetId;
  private final boolean fullReload;
  private final long timestamp;
  private final String source;

  @JsonCreator
  public RuleChangeMessage(
      @JsonProperty("ruleSetId") String ruleSetId,
      @JsonProperty("fullReload") boolean fullReload,
      @JsonProperty("timestamp") long timestamp,
      @JsonProperty("source") String source) {
    this.ruleSetId = ruleSetId;
    this.fullReload = fullReload;
    this.timestamp = timestamp;
    this.source = source;
  }

  /**
   * Creates a message for a specific rule set change.
   *
   * @param ruleSetId the ID of the changed rule set
   * @param source identifier of the source application (e.g., "fluxgate-control")
   * @return the change message
   */
  public static RuleChangeMessage forRuleSet(String ruleSetId, String source) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    return new RuleChangeMessage(ruleSetId, false, Instant.now().toEpochMilli(), source);
  }

  /**
   * Creates a message for a full reload of all rules.
   *
   * @param source identifier of the source application (e.g., "fluxgate-control")
   * @return the full reload message
   */
  public static RuleChangeMessage fullReload(String source) {
    return new RuleChangeMessage(null, true, Instant.now().toEpochMilli(), source);
  }

  /** Returns the rule set ID, or null if this is a full reload. */
  public String getRuleSetId() {
    return ruleSetId;
  }

  /** Returns true if this is a full reload request. */
  public boolean isFullReload() {
    return fullReload;
  }

  /** Returns the timestamp when this message was created (epoch millis). */
  public long getTimestamp() {
    return timestamp;
  }

  /** Returns the source application identifier. */
  public String getSource() {
    return source;
  }

  @Override
  public String toString() {
    if (fullReload) {
      return "RuleChangeMessage{fullReload=true, source='"
          + source
          + "', timestamp="
          + timestamp
          + "}";
    }
    return "RuleChangeMessage{ruleSetId='"
        + ruleSetId
        + "', source='"
        + source
        + "', timestamp="
        + timestamp
        + "}";
  }
}
