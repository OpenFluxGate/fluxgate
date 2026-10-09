package org.fluxgate.control.aop;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to automatically notify FluxGate instances when a rule is changed.
 *
 * <p>When applied to a method, the {@link RuleChangeAspect} will automatically call {@link
 * org.fluxgate.control.notify.RuleChangeNotifier#notifyChange(String)} after the method completes
 * successfully.
 *
 * <p>The {@code ruleSetId} attribute supports Spring Expression Language (SpEL) to extract the rule
 * set ID from method parameters.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * @Service
 * public class RuleManagementService {
 *
 *     @NotifyRuleChange(ruleSetId = "#ruleSetId")
 *     public void updateRule(String ruleSetId, RuleDto dto) {
 *         mongoRepository.save(dto);
 *         // Notification is sent automatically after this method returns
 *     }
 *
 *     @NotifyRuleChange(ruleSetId = "#dto.ruleSetId")
 *     public void saveRule(RuleDto dto) {
 *         mongoRepository.save(dto);
 *     }
 *
 *     @NotifyRuleChange(ruleSetId = "#result.id")
 *     public Rule createRule(RuleDto dto) {
 *         return mongoRepository.save(dto);
 *         // Uses the returned object's id
 *     }
 * }
 * }</pre>
 *
 * <p>SpEL expressions can reference:
 *
 * <ul>
 *   <li>{@code #paramName} - Method parameter by name
 *   <li>{@code #result} - The return value of the method
 *   <li>{@code #root.method} - The method being invoked
 *   <li>{@code #root.target} - The target object
 * </ul>
 *
 * @see NotifyFullReload
 * @see RuleChangeAspect
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface NotifyRuleChange {

  /**
   * SpEL expression to extract the rule set ID.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code "#ruleSetId"} - Parameter named ruleSetId
   *   <li>{@code "#dto.ruleSetId"} - Property of a parameter
   *   <li>{@code "#result.id"} - Property of the return value
   * </ul>
   *
   * @return SpEL expression for rule set ID
   */
  String ruleSetId();
}
