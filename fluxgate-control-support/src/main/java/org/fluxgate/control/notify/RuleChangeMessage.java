package org.fluxgate.control.notify;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Objects;
import org.fluxgate.core.util.HmacSigner;

/**
 * Message payload for rule change notifications.
 *
 * <p>This class is serialized to JSON and published via Redis Pub/Sub. FluxGate instances
 * subscribed to the channel will deserialize this message and take appropriate action.
 *
 * <p>Messages carry an explicit {@link #SCHEMA_VERSION}: a consumer that does not understand a
 * version drops the message instead of guessing, which is what stops an unrelated publisher on a
 * shared Redis from triggering a full reload. {@code fullReload} is always explicit too, so a full
 * reload can never be the accidental result of a missing {@code ruleSetId}.
 *
 * <p>When {@code fluxgate.control.secret} is configured, {@link #signed(String)} adds a {@code
 * signature} over {@link HmacSigner#canonicalRuleChange} and the data plane ignores every message
 * that does not carry a matching one. Without a secret the message is unsigned and anyone who can
 * publish to the channel can trigger a full reload.
 */
public class RuleChangeMessage {

  /** Schema version of the message payload. */
  public static final int SCHEMA_VERSION = 1;

  private final int version;
  private final String ruleSetId;
  private final boolean fullReload;
  private final long timestamp;
  private final String source;
  private final String signature;

  @JsonCreator
  public RuleChangeMessage(
      @JsonProperty("version") Integer version,
      @JsonProperty("ruleSetId") String ruleSetId,
      @JsonProperty("fullReload") boolean fullReload,
      @JsonProperty("timestamp") long timestamp,
      @JsonProperty("source") String source,
      @JsonProperty("signature") String signature) {
    // An older publisher sends no version at all; it spoke version 1.
    this.version = version != null && version > 0 ? version : SCHEMA_VERSION;
    this.ruleSetId = ruleSetId;
    this.fullReload = fullReload;
    this.timestamp = timestamp;
    this.source = source;
    this.signature = signature;
  }

  /**
   * Creates an unsigned message with an explicit schema version.
   *
   * @param version the schema version, or null for {@link #SCHEMA_VERSION}
   * @param ruleSetId the changed rule set, or null for a full reload
   * @param fullReload whether every rule set should be reloaded
   * @param timestamp creation time in epoch millis
   * @param source identifier of the source application
   */
  public RuleChangeMessage(
      Integer version, String ruleSetId, boolean fullReload, long timestamp, String source) {
    this(version, ruleSetId, fullReload, timestamp, source, null);
  }

  /**
   * Creates a message with the current schema version.
   *
   * @param ruleSetId the changed rule set, or null for a full reload
   * @param fullReload whether every rule set should be reloaded
   * @param timestamp creation time in epoch millis
   * @param source identifier of the source application
   */
  public RuleChangeMessage(String ruleSetId, boolean fullReload, long timestamp, String source) {
    this(SCHEMA_VERSION, ruleSetId, fullReload, timestamp, source, null);
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

  /**
   * Returns a copy of this message carrying an HMAC-SHA256 signature.
   *
   * <p>The signature covers {@link #canonicalForm()}, which includes the timestamp: a subscriber
   * can therefore both authenticate the message and bound how long a captured one stays replayable.
   *
   * @param secret the shared secret, must not be null
   * @return a signed copy of this message
   */
  public RuleChangeMessage signed(String secret) {
    Objects.requireNonNull(secret, "secret must not be null");
    return new RuleChangeMessage(
        version,
        ruleSetId,
        fullReload,
        timestamp,
        source,
        HmacSigner.sign(secret, canonicalForm()));
  }

  /**
   * Returns the canonical string a signature is computed over.
   *
   * @return the canonical form of this message
   */
  public String canonicalForm() {
    return HmacSigner.canonicalRuleChange(version, ruleSetId, fullReload, timestamp, source);
  }

  /** Returns the schema version of this message. */
  public int getVersion() {
    return version;
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

  /**
   * Returns the HMAC-SHA256 signature over {@link #canonicalForm()}, or null when unsigned.
   *
   * <p>Omitted from the JSON payload when absent, so an unsigned message keeps exactly the shape
   * earlier FluxGate versions published.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public String getSignature() {
    return signature;
  }

  @Override
  public String toString() {
    if (fullReload) {
      return "RuleChangeMessage{fullReload=true, source='"
          + source
          + "', timestamp="
          + timestamp
          + ", signed="
          + (signature != null)
          + "}";
    }
    return "RuleChangeMessage{ruleSetId='"
        + ruleSetId
        + "', source='"
        + source
        + "', timestamp="
        + timestamp
        + ", signed="
        + (signature != null)
        + "}";
  }
}
