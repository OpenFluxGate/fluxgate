package org.fluxgate.control.aop;

import java.lang.reflect.Method;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.fluxgate.control.notify.RuleChangeNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * Aspect that handles {@link NotifyRuleChange} and {@link NotifyFullReload} annotations.
 *
 * <p>This aspect intercepts methods annotated with rule change annotations and automatically
 * notifies FluxGate instances after successful method execution.
 *
 * <p>For {@link NotifyRuleChange}, it evaluates the SpEL expression to extract the rule set ID from
 * method parameters or return value.
 *
 * <p>Example:
 *
 * <pre>{@code
 * @NotifyRuleChange(ruleSetId = "#ruleSetId")
 * public void updateRule(String ruleSetId, RuleDto dto) {
 *     // After this method returns successfully,
 *     // notifier.notifyChange(ruleSetId) is called automatically
 * }
 * }</pre>
 */
@Aspect
public class RuleChangeAspect {

  private static final Logger log = LoggerFactory.getLogger(RuleChangeAspect.class);

  private final RuleChangeNotifier notifier;
  private final ExpressionParser parser = new SpelExpressionParser();
  private final ParameterNameDiscoverer parameterNameDiscoverer =
      new DefaultParameterNameDiscoverer();

  public RuleChangeAspect(RuleChangeNotifier notifier) {
    this.notifier = notifier;
    log.info("RuleChangeAspect initialized");
  }

  /**
   * Handles methods annotated with {@link NotifyRuleChange}.
   *
   * <p>After the method returns successfully, extracts the rule set ID using the SpEL expression
   * and notifies all FluxGate instances.
   *
   * @param joinPoint the join point
   * @param annotation the annotation
   * @param result the return value of the method
   */
  @AfterReturning(
      pointcut = "@annotation(annotation)",
      returning = "result",
      argNames = "joinPoint,annotation,result")
  public void afterRuleChange(JoinPoint joinPoint, NotifyRuleChange annotation, Object result) {
    try {
      String ruleSetId = extractRuleSetId(joinPoint, annotation.ruleSetId(), result);

      if (ruleSetId == null || ruleSetId.isBlank()) {
        log.warn(
            "Could not extract ruleSetId from expression '{}' in method {}",
            annotation.ruleSetId(),
            joinPoint.getSignature().getName());
        return;
      }

      log.debug(
          "Notifying rule change for ruleSetId={} from method {}",
          ruleSetId,
          joinPoint.getSignature().getName());

      notifier.notifyChange(ruleSetId);

    } catch (Exception e) {
      log.error(
          "Failed to notify rule change for method {}: {}",
          joinPoint.getSignature().getName(),
          e.getMessage(),
          e);
      // Don't rethrow - notification failure should not fail the business operation
    }
  }

  /**
   * Handles methods annotated with {@link NotifyFullReload}.
   *
   * <p>After the method returns successfully, notifies all FluxGate instances to perform a full
   * reload.
   *
   * @param joinPoint the join point
   * @param annotation the annotation
   */
  @AfterReturning(pointcut = "@annotation(annotation)", argNames = "joinPoint,annotation")
  public void afterFullReload(JoinPoint joinPoint, NotifyFullReload annotation) {
    try {
      log.debug("Notifying full reload from method {}", joinPoint.getSignature().getName());

      notifier.notifyFullReload();

    } catch (Exception e) {
      log.error(
          "Failed to notify full reload for method {}: {}",
          joinPoint.getSignature().getName(),
          e.getMessage(),
          e);
      // Don't rethrow - notification failure should not fail the business operation
    }
  }

  /**
   * Extracts the rule set ID from the SpEL expression.
   *
   * @param joinPoint the join point
   * @param expression the SpEL expression
   * @param result the return value of the method
   * @return the extracted rule set ID, or null if extraction fails
   */
  private String extractRuleSetId(JoinPoint joinPoint, String expression, Object result) {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    Method method = signature.getMethod();
    Object target = joinPoint.getTarget();
    Object[] args = joinPoint.getArgs();

    EvaluationContext context =
        new MethodBasedEvaluationContext(target, method, args, parameterNameDiscoverer);

    // Add result to context for expressions like #result.id
    context.setVariable("result", result);

    Object value = parser.parseExpression(expression).getValue(context);

    return value != null ? value.toString() : null;
  }
}
