package org.fluxgate.control.aop;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.fluxgate.control.notify.RuleChangeNotifier;
import org.fluxgate.control.notify.RuleChangeNotifierMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.ClassUtils;

/**
 * Aspect that handles {@link NotifyRuleChange} and {@link NotifyFullReload} annotations.
 *
 * <p>This aspect intercepts methods annotated with rule change annotations and automatically
 * notifies FluxGate instances after successful method execution.
 *
 * <p>For {@link NotifyRuleChange}, it evaluates the SpEL expression to extract the rule set ID from
 * method parameters or return value.
 *
 * <p><b>Transaction awareness.</b> {@code @AfterReturning} runs when the annotated method returns,
 * which inside a {@code @Transactional} boundary is still before the commit. A subscriber that
 * reacts then re-reads the pre-commit state and caches it again, and no second notification ever
 * arrives. So when a transaction synchronization is active the publish is deferred to {@code
 * afterCommit}; with no transaction it is published immediately, as before.
 *
 * <p><b>Delivery.</b> A failed publish is retried with bounded exponential backoff and jitter (3
 * attempts, 100ms base) on a small daemon scheduler, and a final failure increments {@link
 * RuleChangeNotifierMetrics#getFailedNotifications()} so it can be alerted on. A publish that no
 * subscriber received is counted in {@link RuleChangeNotifierMetrics#getNoReceiverNotifications()}
 * instead of as published, and a retry still pending when the aspect shuts down is counted as
 * failed. Redis Pub/Sub is at-most-once and gives the publisher no way to detect a lost message, so
 * <b>data plane instances using the PUBSUB reload strategy should keep a low-frequency polling
 * backstop enabled</b> ({@code fluxgate.reload.pubsub.backstop-polling-interval}, 60s by default):
 * that is what makes a lost notification converge instead of leaving a node on stale rules.
 *
 * <p><b>Parameter names.</b> An expression such as {@code "#ruleSetId"} needs the annotated class
 * to be compiled with {@code -parameters}, which the Spring Boot parent POM enables by default;
 * Spring 6 no longer falls back to the local variable table. Without it, use the positional forms
 * {@code #a0} / {@code #p0} instead.
 *
 * <p>Example:
 *
 * <pre>{@code
 * @NotifyRuleChange(ruleSetId = "#ruleSetId")
 * public void updateRule(String ruleSetId, RuleDto dto) {
 *     // After this method returns successfully (or, inside a transaction, after it commits),
 *     // notifier.notifyChange(ruleSetId) is called automatically
 * }
 * }</pre>
 */
@Aspect
public class RuleChangeAspect {

  private static final Logger log = LoggerFactory.getLogger(RuleChangeAspect.class);

  /** Total publish attempts, the first one included. */
  private static final int MAX_ATTEMPTS = 3;

  /** Base backoff before the second attempt; doubled per attempt. */
  private static final long BASE_BACKOFF_MILLIS = 100L;

  /** Fraction of the backoff applied as random jitter, so retries of a burst spread out. */
  private static final double JITTER_FACTOR = 0.2;

  /** Whether spring-tx is available; it is an optional dependency of this module. */
  private static final boolean TRANSACTION_SUPPORT_PRESENT =
      ClassUtils.isPresent(
          "org.springframework.transaction.support.TransactionSynchronizationManager",
          RuleChangeAspect.class.getClassLoader());

  private final RuleChangeNotifier notifier;
  private final RuleChangeNotifierMetrics metrics;
  private final ExpressionParser parser = new SpelExpressionParser();
  private final ParameterNameDiscoverer parameterNameDiscoverer =
      new DefaultParameterNameDiscoverer();

  /**
   * Parsed SpEL expressions; parsing is expensive and the expressions are fixed at compile time.
   */
  private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

  private final ScheduledExecutorService retryScheduler;

  /**
   * Creates the aspect with its own failure counters.
   *
   * @param notifier the rule change notifier
   */
  public RuleChangeAspect(RuleChangeNotifier notifier) {
    this(notifier, new RuleChangeNotifierMetrics());
  }

  /**
   * Creates the aspect.
   *
   * @param notifier the rule change notifier
   * @param metrics counters for notification outcomes
   */
  public RuleChangeAspect(RuleChangeNotifier notifier, RuleChangeNotifierMetrics metrics) {
    this.notifier = Objects.requireNonNull(notifier, "notifier must not be null");
    this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    this.retryScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "fluxgate-notify-retry");
              t.setDaemon(true);
              return t;
            });
    log.info("RuleChangeAspect initialized");
  }

  /**
   * Handles methods annotated with {@link NotifyRuleChange}.
   *
   * <p>After the method returns successfully - or, inside a transaction, after it commits -
   * extracts the rule set ID using the SpEL expression and notifies all FluxGate instances.
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
    String method = joinPoint.getSignature().getName();
    String ruleSetId;
    try {
      ruleSetId = extractRuleSetId(joinPoint, annotation.ruleSetId(), result);
    } catch (Exception e) {
      log.error(
          "Failed to evaluate ruleSetId expression '{}' in method {}: {}",
          annotation.ruleSetId(),
          method,
          e.getMessage(),
          e);
      // Don't rethrow - notification failure should not fail the business operation
      return;
    }

    if (ruleSetId == null || ruleSetId.isBlank()) {
      log.warn(
          "Could not extract ruleSetId from expression '{}' in method {}",
          annotation.ruleSetId(),
          method);
      return;
    }

    log.debug("Notifying rule change for ruleSetId={} from method {}", ruleSetId, method);
    publishWhenCommitted(
        "rule change for ruleSetId=" + ruleSetId, () -> notifier.publishChange(ruleSetId));
  }

  /**
   * Handles methods annotated with {@link NotifyFullReload}.
   *
   * <p>After the method returns successfully - or, inside a transaction, after it commits -
   * notifies all FluxGate instances to perform a full reload.
   *
   * @param joinPoint the join point
   * @param annotation the annotation
   */
  @AfterReturning(pointcut = "@annotation(annotation)", argNames = "joinPoint,annotation")
  public void afterFullReload(JoinPoint joinPoint, NotifyFullReload annotation) {
    log.debug("Notifying full reload from method {}", joinPoint.getSignature().getName());
    publishWhenCommitted("full reload", notifier::publishFullReload);
  }

  /**
   * Publishes now, or after the current transaction commits when one is active.
   *
   * @param description human readable description of the notification, for logs
   * @param publisher the publish action
   */
  private void publishWhenCommitted(String description, LongSupplier publisher) {
    if (TRANSACTION_SUPPORT_PRESENT
        && TransactionDeferral.deferUntilAfterCommit(
            () -> publishWithRetry(description, publisher))) {
      log.debug("Transaction active, deferred {} until after commit", description);
      return;
    }
    publishWithRetry(description, publisher);
  }

  /**
   * Publishes, retrying on the scheduler with exponential backoff and jitter.
   *
   * @param description human readable description of the notification, for logs
   * @param publisher the publish action
   */
  private void publishWithRetry(String description, LongSupplier publisher) {
    attemptPublish(description, publisher, 1);
  }

  /** Runs one publish attempt and schedules the next one on failure. */
  private void attemptPublish(String description, LongSupplier publisher, int attempt) {
    try {
      long receivers = publisher.getAsLong();
      if (receivers == 0) {
        // Delivered to nobody: a channel mismatch or no running data plane, not a success.
        metrics.recordNoReceivers();
      } else {
        metrics.recordPublished();
      }
      if (attempt > 1) {
        log.info("Published {} on attempt {}", description, attempt);
      }
    } catch (Exception e) {
      if (attempt >= MAX_ATTEMPTS) {
        metrics.recordFailed();
        log.error(
            "Gave up publishing {} after {} attempts ({} lost notification(s) so far). The data "
                + "plane keeps the previous rules until its cache expires or a backstop poll picks "
                + "the change up. Cause: {}",
            description,
            attempt,
            metrics.getFailedNotifications(),
            e.getMessage(),
            e);
        // Don't rethrow - notification failure should not fail the business operation
        return;
      }

      long delay = backoffMillis(attempt);
      metrics.recordRetry();
      log.warn(
          "Failed to publish {} (attempt {}/{}), retrying in {}ms: {}",
          description,
          attempt,
          MAX_ATTEMPTS,
          delay,
          e.getMessage());
      try {
        retryScheduler.schedule(
            () -> attemptPublish(description, publisher, attempt + 1),
            delay,
            TimeUnit.MILLISECONDS);
      } catch (RuntimeException schedulingFailure) {
        metrics.recordFailed();
        log.error("Could not schedule a retry for {}", description, schedulingFailure);
      }
    }
  }

  /** Computes the jittered backoff before the given attempt's retry. */
  private long backoffMillis(int attempt) {
    long base = BASE_BACKOFF_MILLIS << (attempt - 1);
    long jitter = (long) (base * JITTER_FACTOR);
    if (jitter <= 0) {
      return base;
    }
    return Math.max(1L, base + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1));
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

    Object value =
        expressionCache.computeIfAbsent(expression, parser::parseExpression).getValue(context);

    return value != null ? value.toString() : null;
  }

  /**
   * Returns the notification counters.
   *
   * @return the metrics
   */
  public RuleChangeNotifierMetrics getMetrics() {
    return metrics;
  }

  /**
   * Shuts down the retry scheduler.
   *
   * <p>A retry that has not run yet is dropped and counted as a failed notification - it is a rule
   * change no data plane will be told about - and an attempt already running is given up to five
   * seconds to finish.
   */
  public void shutdown() {
    List<Runnable> dropped = retryScheduler.shutdownNow();
    if (!dropped.isEmpty()) {
      for (int i = 0; i < dropped.size(); i++) {
        metrics.recordFailed();
      }
      log.error(
          "Dropped {} pending rule change notification retr{} on shutdown. The data plane keeps the "
              + "previous rules until its cache expires or a backstop poll picks the change up.",
          dropped.size(),
          dropped.size() == 1 ? "y" : "ies");
    }
    try {
      if (!retryScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        log.warn("A rule change notification attempt was still running after 5s of shutdown");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Isolates the spring-tx types into their own class so the aspect keeps working when spring-tx -
   * an optional dependency - is absent. Only loaded when {@link #TRANSACTION_SUPPORT_PRESENT}.
   */
  private static final class TransactionDeferral {

    private TransactionDeferral() {}

    /**
     * Registers the publish for {@code afterCommit} when a transaction synchronization is active.
     *
     * @param publish the publish action
     * @return true when the publish was deferred, false when it should run immediately
     */
    static boolean deferUntilAfterCommit(Runnable publish) {
      if (!TransactionSynchronizationManager.isSynchronizationActive()) {
        return false;
      }
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              publish.run();
            }
          });
      return true;
    }
  }
}
