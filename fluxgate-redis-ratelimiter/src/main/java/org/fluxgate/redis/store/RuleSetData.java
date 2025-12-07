package org.fluxgate.redis.store;

/**
 * Simple data class for storing RuleSet configuration in Redis. This is a simplified representation
 * for JSON serialization.
 */
public class RuleSetData {

  private String ruleSetId;
  private long capacity;
  private long windowSeconds;
  private String keyStrategyId;
  private long createdAt;

  public RuleSetData() {}

  public RuleSetData(String ruleSetId, long capacity, long windowSeconds, String keyStrategyId) {
    this.ruleSetId = ruleSetId;
    this.capacity = capacity;
    this.windowSeconds = windowSeconds;
    this.keyStrategyId = keyStrategyId;
    this.createdAt = System.currentTimeMillis();
  }

  public String getRuleSetId() {
    return ruleSetId;
  }

  public void setRuleSetId(String ruleSetId) {
    this.ruleSetId = ruleSetId;
  }

  public long getCapacity() {
    return capacity;
  }

  public void setCapacity(long capacity) {
    this.capacity = capacity;
  }

  public long getWindowSeconds() {
    return windowSeconds;
  }

  public void setWindowSeconds(long windowSeconds) {
    this.windowSeconds = windowSeconds;
  }

  public String getKeyStrategyId() {
    return keyStrategyId;
  }

  public void setKeyStrategyId(String keyStrategyId) {
    this.keyStrategyId = keyStrategyId;
  }

  public long getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(long createdAt) {
    this.createdAt = createdAt;
  }

  @Override
  public String toString() {
    return "RuleSetData{"
        + "ruleSetId='"
        + ruleSetId
        + '\''
        + ", capacity="
        + capacity
        + ", windowSeconds="
        + windowSeconds
        + ", keyStrategyId='"
        + keyStrategyId
        + '\''
        + ", createdAt="
        + createdAt
        + '}';
  }
}
