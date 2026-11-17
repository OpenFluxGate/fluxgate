package org.fluxgate.spring.reload.handler;

import java.util.Objects;
import java.util.function.Supplier;
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
 *
 * <p>The store is resolved through a {@link Supplier} so the handler can be created before the
 * Redis connection is established: when Redis is still unavailable, a reset is logged and skipped
 * instead of failing the reload. Deleting only bucket keys is the store's responsibility.
 */
public class RedisBucketResetHandler implements BucketResetHandler, RuleReloadListener {

  private static final Logger log = LoggerFactory.getLogger(RedisBucketResetHandler.class);

  private final Supplier<RedisTokenBucketStore> tokenBucketStoreSupplier;

  /**
   * Creates a new RedisBucketResetHandler.
   *
   * @param tokenBucketStore the Redis token bucket store
   */
  public RedisBucketResetHandler(RedisTokenBucketStore tokenBucketStore) {
    Objects.requireNonNull(tokenBucketStore, "tokenBucketStore must not be null");
    this.tokenBucketStoreSupplier = () -> tokenBucketStore;
  }

  /**
   * Creates a new RedisBucketResetHandler that resolves the store on each reset.
   *
   * @param tokenBucketStoreSupplier supplier of the Redis token bucket store; may throw while the
   *     connection is not established yet
   */
  public RedisBucketResetHandler(Supplier<RedisTokenBucketStore> tokenBucketStoreSupplier) {
    this.tokenBucketStoreSupplier =
        Objects.requireNonNull(tokenBucketStoreSupplier, "tokenBucketStoreSupplier must not null");
  }

  @Override
  public void resetBuckets(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    RedisTokenBucketStore store = resolveStore("ruleSetId " + ruleSetId);
    if (store == null) {
      return;
    }
    log.info("Resetting token buckets for ruleSetId: {}", ruleSetId);
    long deleted = store.deleteBucketsByRuleSetId(ruleSetId);
    log.info("Reset complete: {} buckets deleted for ruleSetId: {}", deleted, ruleSetId);
  }

  @Override
  public void resetAllBuckets() {
    RedisTokenBucketStore store = resolveStore("all rule sets");
    if (store == null) {
      return;
    }
    log.info("Resetting all token buckets (full reset)");
    long deleted = store.deleteAllBuckets();
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

  /** Resolves the store, returning null and logging a warning when Redis is unavailable. */
  private RedisTokenBucketStore resolveStore(String what) {
    try {
      return tokenBucketStoreSupplier.get();
    } catch (RuntimeException e) {
      log.warn(
          "Redis is unavailable, skipping bucket reset for {}: {}. The buckets expire on their own "
              + "TTL once the new rules are in use.",
          what,
          e.getMessage());
      return null;
    }
  }
}
