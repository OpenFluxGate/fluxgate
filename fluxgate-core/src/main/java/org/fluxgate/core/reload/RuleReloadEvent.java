package org.fluxgate.core.reload;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Event representing a rule reload trigger.
 *
 * <p>This event is published when rules need to be reloaded, either for a specific rule set or for
 * all cached rules.
 */
public final class RuleReloadEvent {

  private final String ruleSetId;
  private final ReloadSource source;
  private final Instant timestamp;
  private final Map<String, Object> metadata;
  private final boolean fullReload;

  private RuleReloadEvent(Builder builder) {
    this.ruleSetId = builder.ruleSetId;
    this.source = Objects.requireNonNull(builder.source, "source must not be null");
    this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
    this.metadata =
        builder.metadata != null
            ? Collections.unmodifiableMap(new HashMap<>(builder.metadata))
            : Collections.emptyMap();
    this.fullReload = builder.fullReload;
  }

  /**
   * Returns the rule set ID to reload. If null, all cached rules should be reloaded.
   *
   * @return the rule set ID, or null for full reload
   */
  public String getRuleSetId() {
    return ruleSetId;
  }

  /**
   * Returns true if this is a full reload event (all rules).
   *
   * <p>A full reload invalidates every cached rule set, so it must be requested explicitly via
   * {@link Builder#fullReload(boolean)} or {@link #fullReload(ReloadSource)}. A null {@code
   * ruleSetId} is still treated as a full reload for backward compatibility, but callers should not
   * rely on it.
   *
   * @return true if the full reload flag is set or ruleSetId is null
   */
  public boolean isFullReload() {
    return fullReload || ruleSetId == null;
  }

  /**
   * Returns the source that triggered this reload.
   *
   * @return the reload source
   */
  public ReloadSource getSource() {
    return source;
  }

  /**
   * Returns the timestamp when this event was created.
   *
   * @return the event timestamp
   */
  public Instant getTimestamp() {
    return timestamp;
  }

  /**
   * Returns additional metadata about the reload event.
   *
   * @return unmodifiable map of metadata
   */
  public Map<String, Object> getMetadata() {
    return metadata;
  }

  /**
   * Creates a new builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Creates a reload event for a specific rule set.
   *
   * @param ruleSetId the rule set ID to reload
   * @param source the reload source
   * @return a new reload event
   */
  public static RuleReloadEvent forRuleSet(String ruleSetId, ReloadSource source) {
    return builder().ruleSetId(ruleSetId).source(source).build();
  }

  /**
   * Creates a full reload event (all rules).
   *
   * @param source the reload source
   * @return a new reload event
   */
  public static RuleReloadEvent fullReload(ReloadSource source) {
    return builder().source(source).fullReload(true).build();
  }

  @Override
  public String toString() {
    return "RuleReloadEvent{"
        + "ruleSetId='"
        + (ruleSetId != null ? ruleSetId : "ALL")
        + '\''
        + ", source="
        + source
        + ", timestamp="
        + timestamp
        + '}';
  }

  /** Builder for {@link RuleReloadEvent}. */
  public static final class Builder {
    private String ruleSetId;
    private ReloadSource source;
    private Instant timestamp;
    private Map<String, Object> metadata;
    private boolean fullReload;

    private Builder() {}

    /**
     * Sets the rule set to reload; leave unset for a full reload.
     *
     * @param ruleSetId the rule set id
     * @return this builder
     */
    public Builder ruleSetId(String ruleSetId) {
      this.ruleSetId = ruleSetId;
      return this;
    }

    /**
     * Sets the source that triggered this reload.
     *
     * @param source the reload source (required)
     * @return this builder
     */
    public Builder source(ReloadSource source) {
      this.source = source;
      return this;
    }

    /**
     * Sets the event timestamp; defaults to {@code Instant.now()}.
     *
     * @param timestamp the event timestamp
     * @return this builder
     */
    public Builder timestamp(Instant timestamp) {
      this.timestamp = timestamp;
      return this;
    }

    /**
     * Sets additional metadata describing the reload. The map is copied defensively.
     *
     * @param metadata the metadata (null is treated as empty)
     * @return this builder
     */
    public Builder metadata(Map<String, Object> metadata) {
      this.metadata = metadata;
      return this;
    }

    /**
     * Marks this event as a full reload of every cached rule set.
     *
     * @param fullReload true to invalidate all cached rules
     * @return this builder
     */
    public Builder fullReload(boolean fullReload) {
      this.fullReload = fullReload;
      return this;
    }

    /**
     * Builds the reload event.
     *
     * @return the reload event
     * @throws NullPointerException if no source is set
     */
    public RuleReloadEvent build() {
      return new RuleReloadEvent(this);
    }
  }
}
