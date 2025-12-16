package org.fluxgate.control.notify;

/**
 * Interface for notifying FluxGate application servers about rule changes.
 *
 * <p>When rules are modified in the Admin/Studio application, this notifier broadcasts the change
 * to all FluxGate instances so they can invalidate their local caches and reload the updated rules.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * @Service
 * public class RuleManagementService {
 *     private final RuleChangeNotifier notifier;
 *
 *     public void updateRule(String ruleSetId, RuleDto dto) {
 *         // 1. Save to database
 *         mongoRepository.save(dto);
 *
 *         // 2. Notify all FluxGate instances
 *         notifier.notifyChange(ruleSetId);
 *     }
 *
 *     public void deleteAllRules() {
 *         mongoRepository.deleteAll();
 *         notifier.notifyFullReload();
 *     }
 * }
 * }</pre>
 */
public interface RuleChangeNotifier {

  /**
   * Notifies all FluxGate instances that a specific rule set has changed.
   *
   * <p>The instances will invalidate their local cache for this rule set and reload it from the
   * database on the next request.
   *
   * @param ruleSetId the ID of the changed rule set
   */
  void notifyChange(String ruleSetId);

  /**
   * Notifies all FluxGate instances to perform a full reload of all rules.
   *
   * <p>Use this when multiple rules have changed or when performing bulk operations. All instances
   * will invalidate their entire rule cache.
   */
  void notifyFullReload();

  /**
   * Closes the notifier and releases any resources.
   *
   * <p>After calling this method, the notifier should not be used.
   */
  void close();
}
