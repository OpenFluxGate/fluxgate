package org.fluxgate.spring.reload.handler;

import java.util.Objects;
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.fluxgate.core.reload.BucketResetHandler;
import org.fluxgate.core.reload.RuleReloadEvent;
import org.fluxgate.core.reload.RuleReloadListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory implementation of {@link BucketResetHandler} for {@link Bucket4jRateLimiter}.
 *
 * <p>Counterpart of {@link RedisBucketResetHandler} for deployments that run the in-memory limiter
 * ({@code fluxgate.ratelimit.mode=IN_MEMORY}, or AUTO without Redis). It drops the local buckets of
 * a rule set when its rules change so the new limits take effect immediately instead of when the
 * existing buckets expire.
 *
 * <p>It also implements {@link RuleReloadListener} so it can be registered directly on a reload
 * strategy.
 */
public class InMemoryBucketResetHandler implements BucketResetHandler, RuleReloadListener {

  private static final Logger log = LoggerFactory.getLogger(InMemoryBucketResetHandler.class);

  private final Bucket4jRateLimiter rateLimiter;

  /**
   * Creates a new InMemoryBucketResetHandler.
   *
   * @param rateLimiter the in-memory rate limiter owning the buckets
   */
  public InMemoryBucketResetHandler(Bucket4jRateLimiter rateLimiter) {
    this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter must not be null");
  }

  @Override
  public void resetBuckets(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    log.info("Resetting in-memory buckets for ruleSetId: {}", ruleSetId);
    rateLimiter.reset(ruleSetId);
  }

  @Override
  public void resetAllBuckets() {
    log.info("Resetting all in-memory buckets (full reset)");
    rateLimiter.resetAll();
  }

  @Override
  public void onReload(RuleReloadEvent event) {
    if (event.isFullReload()) {
      log.info("Full reload event received, resetting all in-memory buckets");
      resetAllBuckets();
    } else {
      String ruleSetId = event.getRuleSetId();
      log.info("Reload event received for ruleSetId: {}, resetting in-memory buckets", ruleSetId);
      resetBuckets(ruleSetId);
    }
  }
}
