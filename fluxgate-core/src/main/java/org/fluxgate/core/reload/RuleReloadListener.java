package org.fluxgate.core.reload;

/**
 * Listener interface for rule reload events.
 *
 * <p>Implementations can react to rule changes, such as invalidating caches or updating
 * configurations.
 */
@FunctionalInterface
public interface RuleReloadListener {

  /**
   * Called when a rule reload event is received.
   *
   * @param event the reload event containing details about what should be reloaded
   */
  void onReload(RuleReloadEvent event);
}
