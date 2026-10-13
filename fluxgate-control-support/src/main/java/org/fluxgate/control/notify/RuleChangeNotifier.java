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
   * Returned by {@link #publishChange(String)} and {@link #publishFullReload()} when the
   * implementation cannot tell how many subscribers received the notification.
   *
   * @since 0.4.0
   */
  long UNKNOWN_RECEIVERS = -1L;

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
   * Notifies about a rule set change and reports how many subscribers received it.
   *
   * <p>The default implementation calls {@link #notifyChange(String)} and returns {@link
   * #UNKNOWN_RECEIVERS}; implementations that know the receiver count override it.
   *
   * @param ruleSetId the ID of the changed rule set
   * @return the number of receivers, or {@link #UNKNOWN_RECEIVERS}
   * @since 0.4.0
   */
  default long publishChange(String ruleSetId) {
    notifyChange(ruleSetId);
    return UNKNOWN_RECEIVERS;
  }

  /**
   * Requests a full reload and reports how many subscribers received it.
   *
   * <p>The default implementation calls {@link #notifyFullReload()} and returns {@link
   * #UNKNOWN_RECEIVERS}.
   *
   * @return the number of receivers, or {@link #UNKNOWN_RECEIVERS}
   * @since 0.4.0
   */
  default long publishFullReload() {
    notifyFullReload();
    return UNKNOWN_RECEIVERS;
  }

  /**
   * Closes the notifier and releases any resources.
   *
   * <p>After calling this method, the notifier should not be used.
   */
  void close();
}
