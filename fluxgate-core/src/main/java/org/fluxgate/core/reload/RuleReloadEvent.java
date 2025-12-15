package org.fluxgate.core.reload;

import java.time.Instant;
import java.util.Collections;
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

  private RuleReloadEvent(Builder builder) {
    this.ruleSetId = builder.ruleSetId;
    this.source = Objects.requireNonNull(builder.source, "source must not be null");
    this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
    this.metadata =
        builder.metadata != null
            ? Collections.unmodifiableMap(builder.metadata)
            : Collections.emptyMap();
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
   * @return true if ruleSetId is null
   */
  public boolean isFullReload() {
    return ruleSetId == null;
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
    return builder().source(source).build();
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

  public static final class Builder {
    private String ruleSetId;
    private ReloadSource source;
    private Instant timestamp;
    private Map<String, Object> metadata;

    private Builder() {}

    public Builder ruleSetId(String ruleSetId) {
      this.ruleSetId = ruleSetId;
      return this;
    }

    public Builder source(ReloadSource source) {
      this.source = source;
      return this;
    }

    public Builder timestamp(Instant timestamp) {
      this.timestamp = timestamp;
      return this;
    }

    public Builder metadata(Map<String, Object> metadata) {
      this.metadata = metadata;
      return this;
    }

    public RuleReloadEvent build() {
      return new RuleReloadEvent(this);
    }
  }
}
