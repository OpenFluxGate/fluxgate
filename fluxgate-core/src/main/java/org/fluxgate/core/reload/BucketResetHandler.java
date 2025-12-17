package org.fluxgate.core.reload;

/**
 * Handler for resetting rate limit buckets when rules are changed.
 *
 * <p>When a rule is modified in the Admin UI, the cached rule definitions are invalidated, but the
 * token bucket state in Redis (or other storage) remains. This handler is responsible for resetting
 * the bucket state so that the new rules take effect immediately.
 *
 * <p>Implementations should delete or reset the token buckets associated with the changed rule set.
 *
 * <p>Example implementation for Redis:
 *
 * <pre>
 * public class RedisBucketResetHandler implements BucketResetHandler {
 *     private final RedisTokenBucketStore store;
 *
 *     public void resetBuckets(String ruleSetId) {
 *         store.deleteBucketsByRuleSetId(ruleSetId);
 *     }
 *
 *     public void resetAllBuckets() {
 *         store.deleteAllBuckets();
 *     }
 * }
 * </pre>
 */
public interface BucketResetHandler {

  /**
   * Resets all buckets associated with the given rule set.
   *
   * <p>Called when a specific rule set is modified or deleted.
   *
   * @param ruleSetId the rule set ID whose buckets should be reset
   */
  void resetBuckets(String ruleSetId);

  /**
   * Resets all buckets (full reset).
   *
   * <p>Called when a full reload is triggered.
   */
  void resetAllBuckets();
}
