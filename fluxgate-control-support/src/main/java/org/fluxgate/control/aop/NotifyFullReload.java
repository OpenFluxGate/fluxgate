package org.fluxgate.control.aop;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to automatically notify FluxGate instances to perform a full rule reload.
 *
 * <p>When applied to a method, the {@link RuleChangeAspect} will automatically call {@link
 * org.fluxgate.control.notify.RuleChangeNotifier#notifyFullReload()} after the method completes
 * successfully.
 *
 * <p>Use this annotation for operations that affect multiple rules or when the specific rule set ID
 * is not available.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * @Service
 * public class RuleManagementService {
 *
 *     @NotifyFullReload
 *     public void deleteAllRules() {
 *         mongoRepository.deleteAll();
 *         // Full reload notification is sent automatically
 *     }
 *
 *     @NotifyFullReload
 *     public void importRules(List<RuleDto> rules) {
 *         mongoRepository.deleteAll();
 *         mongoRepository.saveAll(rules);
 *     }
 *
 *     @NotifyFullReload
 *     public void resetToDefaults() {
 *         mongoRepository.deleteAll();
 *         mongoRepository.saveAll(defaultRules);
 *     }
 * }
 * }</pre>
 *
 * @see NotifyRuleChange
 * @see RuleChangeAspect
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface NotifyFullReload {}
