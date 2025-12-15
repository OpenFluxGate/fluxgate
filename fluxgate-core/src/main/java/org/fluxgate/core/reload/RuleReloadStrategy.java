package org.fluxgate.core.reload;

/**
 * Strategy interface for rule hot reload.
 *
 * <p>Implementations define how rules are reloaded (e.g., via polling, Redis Pub/Sub, etc.) and
 * manage the lifecycle of reload mechanisms.
 *
 * <p>Typical usage:
 *
 * <pre>{@code
 * RuleReloadStrategy strategy = new PollingReloadStrategy(...);
 * strategy.addListener(event -> cache.invalidate(event.getRuleSetId()));
 * strategy.start();
 *
 * // Later, to trigger a reload programmatically:
 * strategy.triggerReload("my-rule-set");
 *
 * // On shutdown:
 * strategy.stop();
 * }</pre>
 */
public interface RuleReloadStrategy {

  /**
   * Starts the reload mechanism.
   *
   * <p>For polling strategies, this starts the scheduled task. For Pub/Sub strategies, this
   * establishes the subscription.
   *
   * <p>This method is idempotent - calling it multiple times has no additional effect.
   */
  void start();

  /**
   * Stops the reload mechanism and releases resources.
   *
   * <p>This method is idempotent - calling it multiple times has no additional effect.
   */
  void stop();

  /**
   * Returns whether the reload mechanism is currently running.
   *
   * @return true if started and not stopped
   */
  boolean isRunning();

  /**
   * Triggers a reload for a specific rule set.
   *
   * <p>This method can be called to programmatically trigger a reload, for example after updating a
   * rule via an admin API.
   *
   * @param ruleSetId the ID of the rule set to reload
   */
  void triggerReload(String ruleSetId);

  /**
   * Triggers a full reload of all cached rules.
   *
   * <p>This is useful for scenarios like configuration refresh or manual cache invalidation.
   */
  void triggerReloadAll();

  /**
   * Adds a listener that will be notified when reload events occur.
   *
   * @param listener the listener to add
   */
  void addListener(RuleReloadListener listener);

  /**
   * Removes a previously added listener.
   *
   * @param listener the listener to remove
   */
  void removeListener(RuleReloadListener listener);
}
