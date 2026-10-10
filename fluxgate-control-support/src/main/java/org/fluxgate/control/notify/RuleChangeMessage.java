package org.fluxgate.control.notify;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
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
 * <p>When {@code fluxgate.control.secret} is configured, {@link #signed(String, String)} adds a
 * {@code signature} over {@link HmacSigner#canonicalRuleChangeV2}, which binds the channel and a
 * per-message {@code nonce}: the data plane ignores every message that does not carry a matching
 * signature and every nonce it has already seen inside the replay window. Without a secret the
 * message is unsigned and anyone who can publish to the channel can trigger a full reload.
 *
 * <p>A payload without a {@code version} came from a publisher older than schema version 2 and is
 * read as {@link #LEGACY_SCHEMA_VERSION}.
 */
public class RuleChangeMessage {

  /** Schema version of the messages this class creates: length-prefixed, channel bound, nonce. */
  public static final int SCHEMA_VERSION = 2;

  /**
   * Schema version 1: no nonce, no channel binding, {@code |}-joined canonical form.
   *
   * @since 0.4.0
   */
  public static final int LEGACY_SCHEMA_VERSION = 1;

  private final int version;
  private final String ruleSetId;
  private final boolean fullReload;
  private final long timestamp;
  private final String source;
  private final String nonce;
  private final String signature;

  /**
   * Creates a message from its JSON fields.
   *
   * <p>The nonce is taken as given: a version 2 payload without one must stay without one, so the
   * subscriber can reject it rather than accept a nonce made up on its own side.
   *
   * @param version the schema version; null or non-positive means {@link #LEGACY_SCHEMA_VERSION}
   * @param ruleSetId the changed rule set, or null for a full reload
   * @param fullReload whether every rule set should be reloaded
   * @param timestamp creation time in epoch millis
   * @param source identifier of the source application
   * @param nonce unique per message identifier, or null
   * @param signature hex HMAC-SHA256 signature, or null when unsigned
   * @since 0.4.0
   */
  @JsonCreator
  public RuleChangeMessage(
      @JsonProperty("version") Integer version,
      @JsonProperty("ruleSetId") String ruleSetId,
      @JsonProperty("fullReload") boolean fullReload,
      @JsonProperty("timestamp") long timestamp,
      @JsonProperty("source") String source,
      @JsonProperty("nonce") String nonce,
      @JsonProperty("signature") String signature) {
    // An older publisher sends no version at all; it spoke version 1.
    this.version = version != null && version > 0 ? version : LEGACY_SCHEMA_VERSION;
    this.ruleSetId = ruleSetId;
    this.fullReload = fullReload;
    this.timestamp = timestamp;
    this.source = source;
    this.nonce = nonce;
    this.signature = signature;
  }

  /**
   * Creates a message with an explicit schema version and signature.
   *
   * <p>A version 2 (or later) message gets a fresh random nonce. Because a version 2 signature
   * covers the nonce, a signature passed here could never verify against the nonce generated here,
   * so a non-null {@code signature} is only accepted for a version 1 message.
   *
   * @param version the schema version; null or non-positive means {@link #LEGACY_SCHEMA_VERSION}
   * @param ruleSetId the changed rule set, or null for a full reload
   * @param fullReload whether every rule set should be reloaded
   * @param timestamp creation time in epoch millis
   * @param source identifier of the source application
   * @param signature hex HMAC-SHA256 signature, or null when unsigned
   * @throws IllegalArgumentException when {@code signature} is not null and {@code version} is 2 or
   *     later
   * @deprecated use {@link #RuleChangeMessage(Integer, String, boolean, long, String, String,
   *     String)}, which takes the nonce the signature was computed over, or {@link #signed(String,
   *     String)} to sign a message
   */
  @Deprecated
  public RuleChangeMessage(
      Integer version,
      String ruleSetId,
      boolean fullReload,
      long timestamp,
      String source,
      String signature) {
    this(
        version,
        ruleSetId,
        fullReload,
        timestamp,
        source,
        nonceFor(version),
        requireNoV2Signature(version, signature));
  }

  /**
   * Creates an unsigned message with an explicit schema version.
   *
   * @param version the schema version, or null for {@link #LEGACY_SCHEMA_VERSION}
   * @param ruleSetId the changed rule set, or null for a full reload
   * @param fullReload whether every rule set should be reloaded
   * @param timestamp creation time in epoch millis
   * @param source identifier of the source application
   */
  public RuleChangeMessage(
      Integer version, String ruleSetId, boolean fullReload, long timestamp, String source) {
    this(version, ruleSetId, fullReload, timestamp, source, nonceFor(version), null);
  }

  /**
   * Creates an unsigned message with the current schema version and a fresh nonce.
   *
   * @param ruleSetId the changed rule set, or null for a full reload
   * @param fullReload whether every rule set should be reloaded
   * @param timestamp creation time in epoch millis
   * @param source identifier of the source application
   */
  public RuleChangeMessage(String ruleSetId, boolean fullReload, long timestamp, String source) {
    this(SCHEMA_VERSION, ruleSetId, fullReload, timestamp, source, newNonce(), null);
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

  private static String newNonce() {
    return UUID.randomUUID().toString();
  }

  /** A fresh nonce for a version 2 (or later) message, null for version 1. */
  private static String nonceFor(Integer version) {
    return version != null && version >= SCHEMA_VERSION ? newNonce() : null;
  }

  private static String requireNoV2Signature(Integer version, String signature) {
    if (signature != null && version != null && version >= SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "A version "
              + version
              + " signature covers the nonce; pass the nonce it was computed over to the "
              + "7-argument constructor, or use signed(secret, channel)");
    }
    return signature;
  }

  /**
   * Returns a copy of this message carrying an HMAC-SHA256 signature bound to {@code channel}.
   *
   * <p>The signature covers {@link #canonicalForm(String)}: the timestamp bounds how long a
   * captured message stays acceptable, the nonce lets the subscriber reject a replay inside that
   * window, and the channel stops a message signed for one channel from being accepted on another
   * that shares the secret.
   *
   * @param secret the shared secret, must not be null
   * @param channel the channel the message is published on, must not be null
   * @return a signed copy of this message
   * @since 0.4.0
   */
  public RuleChangeMessage signed(String secret, String channel) {
    Objects.requireNonNull(secret, "secret must not be null");
    Objects.requireNonNull(channel, "channel must not be null");
    return new RuleChangeMessage(
        version,
        ruleSetId,
        fullReload,
        timestamp,
        source,
        nonce,
        HmacSigner.sign(secret, canonicalForm(channel)));
  }

  /**
   * Returns a copy of this message signed in the version 1 format.
   *
   * <p>The copy is a version 1 message: no nonce and no channel binding. A subscriber accepts it
   * only inside its replay window and deduplicates it by signature.
   *
   * @param secret the shared secret, must not be null
   * @return a signed version 1 copy of this message
   * @deprecated use {@link #signed(String, String)}, which binds the channel and a nonce
   */
  @Deprecated
  public RuleChangeMessage signed(String secret) {
    Objects.requireNonNull(secret, "secret must not be null");
    RuleChangeMessage legacy =
        new RuleChangeMessage(
            LEGACY_SCHEMA_VERSION, ruleSetId, fullReload, timestamp, source, null, null);
    return new RuleChangeMessage(
        LEGACY_SCHEMA_VERSION,
        ruleSetId,
        fullReload,
        timestamp,
        source,
        null,
        HmacSigner.sign(secret, legacy.canonicalForm(null)));
  }

  /**
   * Returns the canonical string a signature is computed over.
   *
   * @param channel the channel the message is published on; ignored by version 1 messages
   * @return the canonical form of this message
   * @since 0.4.0
   */
  public String canonicalForm(String channel) {
    if (version >= SCHEMA_VERSION) {
      return HmacSigner.canonicalRuleChangeV2(
          version, channel, ruleSetId, fullReload, timestamp, source, nonce);
    }
    return HmacSigner.canonicalRuleChange(version, ruleSetId, fullReload, timestamp, source);
  }

  /**
   * Returns the version 1 canonical string.
   *
   * @return the canonical form of this version 1 message
   * @throws IllegalStateException for a version 2 message, whose form needs the channel
   * @deprecated use {@link #canonicalForm(String)}
   */
  @Deprecated
  public String canonicalForm() {
    if (version >= SCHEMA_VERSION) {
      throw new IllegalStateException(
          "A version " + version + " canonical form binds the channel; use canonicalForm(channel)");
    }
    return canonicalForm(null);
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
   * Returns the per-message nonce, or null for a version 1 message.
   *
   * @return the nonce
   * @since 0.4.0
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public String getNonce() {
    return nonce;
  }

  /**
   * Returns the HMAC-SHA256 signature over {@link #canonicalForm(String)}, or null when unsigned.
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
