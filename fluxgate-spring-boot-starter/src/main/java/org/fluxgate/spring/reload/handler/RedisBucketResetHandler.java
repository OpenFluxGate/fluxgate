package org.fluxgate.spring.reload.handler;

import java.util.Objects;
import org.fluxgate.core.reload.BucketResetHandler;
import org.fluxgate.core.reload.RuleReloadEvent;
import org.fluxgate.core.reload.RuleReloadListener;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis implementation of {@link BucketResetHandler}.
 *
 * <p>This handler deletes token buckets from Redis when rules are changed, ensuring that the new
 * rules take effect immediately.
 *
 * <p>It also implements {@link RuleReloadListener} to automatically reset buckets when reload
 * events are received via Pub/Sub or polling.
 */
public class RedisBucketResetHandler implements BucketResetHandler, RuleReloadListener {

  private static final Logger log = LoggerFactory.getLogger(RedisBucketResetHandler.class);

  private final RedisTokenBucketStore tokenBucketStore;

  /**
   * Creates a new RedisBucketResetHandler.
   *
   * @param tokenBucketStore the Redis token bucket store
   */
  public RedisBucketResetHandler(RedisTokenBucketStore tokenBucketStore) {
    this.tokenBucketStore =
        Objects.requireNonNull(tokenBucketStore, "tokenBucketStore must not be null");
  }

  @Override
  public void resetBuckets(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    log.info("Resetting token buckets for ruleSetId: {}", ruleSetId);
    long deleted = tokenBucketStore.deleteBucketsByRuleSetId(ruleSetId);
    log.info("Reset complete: {} buckets deleted for ruleSetId: {}", deleted, ruleSetId);
  }

  @Override
  public void resetAllBuckets() {
    log.info("Resetting all token buckets (full reset)");
    long deleted = tokenBucketStore.deleteAllBuckets();
    log.info("Full reset complete: {} buckets deleted", deleted);
  }

  @Override
  public void onReload(RuleReloadEvent event) {
    if (event.isFullReload()) {
      log.info("Full reload event received, resetting all buckets");
      resetAllBuckets();
    } else {
      String ruleSetId = event.getRuleSetId();
      log.info("Reload event received for ruleSetId: {}, resetting buckets", ruleSetId);
      resetBuckets(ruleSetId);
    }
  }
}
