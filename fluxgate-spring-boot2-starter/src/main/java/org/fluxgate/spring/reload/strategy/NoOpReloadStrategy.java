package org.fluxgate.spring.reload.strategy;

import org.fluxgate.core.reload.ReloadSource;

/**
 * No-operation reload strategy that does nothing.
 *
 * <p>Used when hot reload is disabled (strategy = NONE). Rules are always fetched fresh from the
 * provider without caching.
 */
public class NoOpReloadStrategy extends AbstractReloadStrategy {

  @Override
  protected ReloadSource getReloadSource() {
    return ReloadSource.MANUAL;
  }

  @Override
  protected void doStart() {
    log.info("NoOp reload strategy active - hot reload disabled");
  }

  @Override
  protected void doStop() {
    // Nothing to clean up
  }

  @Override
  public void triggerReload(String ruleSetId) {
    log.debug("NoOp reload triggered for ruleSetId: {} (ignored)", ruleSetId);
  }

  @Override
  public void triggerReloadAll() {
    log.debug("NoOp full reload triggered (ignored)");
  }
}
