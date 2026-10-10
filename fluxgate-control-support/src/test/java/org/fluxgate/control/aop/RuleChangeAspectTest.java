package org.fluxgate.control.aop;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.fluxgate.control.notify.RuleChangeNotificationException;
import org.fluxgate.control.notify.RuleChangeNotifier;
import org.fluxgate.control.notify.RuleChangeNotifierMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tests for {@link RuleChangeAspect}.
 *
 * <p>Two behaviours matter here and neither existed before: a notification published inside a
 * transaction must wait for the commit (otherwise subscribers re-read and cache the pre-commit
 * state), and a publish that fails must be retried and then counted rather than swallowed.
 */
class RuleChangeAspectTest {

  /** Records what was published, and optionally fails a number of times first. */
  private static final class RecordingNotifier implements RuleChangeNotifier {

    private final List<String> published = new ArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();

    void failNextTimes(int times) {
      failuresLeft.set(times);
    }

    @Override
    public void notifyChange(String ruleSetId) {
      maybeFail();
      published.add("change:" + ruleSetId);
    }

    @Override
    public void notifyFullReload() {
      maybeFail();
      published.add("fullReload");
    }

    @Override
    public void close() {}

    private void maybeFail() {
      if (failuresLeft.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0) {
        throw new RuleChangeNotificationException("redis unavailable");
      }
    }
  }

  /** A target whose annotated methods the aspect is invoked against directly. */
  public static class RuleService {

    @NotifyRuleChange(ruleSetId = "#ruleSetId")
    public void updateRule(String ruleSetId) {}

    @NotifyFullReload
    public void deleteAll() {}
  }

  private RecordingNotifier notifier;
  private RuleChangeNotifierMetrics metrics;
  private RuleChangeAspect aspect;

  @BeforeEach
  void setUp() {
    notifier = new RecordingNotifier();
    metrics = new RuleChangeNotifierMetrics();
    aspect = new RuleChangeAspect(notifier, metrics);
  }

  @AfterEach
  void tearDown() {
    aspect.shutdown();
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  /** Minimal JoinPoint over one method of {@link RuleService}. */
  private static JoinPoint joinPointFor(String methodName, Object... args) throws Exception {
    Class<?>[] parameterTypes = new Class<?>[args.length];
    for (int i = 0; i < args.length; i++) {
      parameterTypes[i] = args[i].getClass();
    }
    Method method = RuleService.class.getMethod(methodName, parameterTypes);
    RuleService target = new RuleService();

    MethodSignature signature =
        new MethodSignature() {
          @Override
          public Class<?> getReturnType() {
            return method.getReturnType();
          }

          @Override
          public Method getMethod() {
            return method;
          }

          @Override
          public Class<?>[] getParameterTypes() {
            return method.getParameterTypes();
          }

          @Override
          public String[] getParameterNames() {
            return new String[] {"ruleSetId"};
          }

          @Override
          public Class<?>[] getExceptionTypes() {
            return method.getExceptionTypes();
          }

          @Override
          public String toShortString() {
            return methodName;
          }

          @Override
          public String toLongString() {
            return methodName;
          }

          @Override
          public String getName() {
            return methodName;
          }

          @Override
          public int getModifiers() {
            return method.getModifiers();
          }

          @Override
          public Class<?> getDeclaringType() {
            return RuleService.class;
          }

          @Override
          public String getDeclaringTypeName() {
            return RuleService.class.getName();
          }
        };

    return new JoinPoint() {
      @Override
      public String toShortString() {
        return methodName;
      }

      @Override
      public String toLongString() {
        return methodName;
      }

      @Override
      public Object getThis() {
        return target;
      }

      @Override
      public Object getTarget() {
        return target;
      }

      @Override
      public Object[] getArgs() {
        return args;
      }

      @Override
      public Signature getSignature() {
        return signature;
      }

      @Override
      public org.aspectj.lang.reflect.SourceLocation getSourceLocation() {
        return null;
      }

      @Override
      public String getKind() {
        return JoinPoint.METHOD_EXECUTION;
      }

      @Override
      public JoinPoint.StaticPart getStaticPart() {
        return null;
      }
    };
  }

  /** Waits up to five seconds for a condition the retry scheduler satisfies asynchronously. */
  private static boolean awaitUntil(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      Thread.sleep(20);
    }
    return condition.getAsBoolean();
  }

  private static NotifyRuleChange ruleChangeAnnotation() throws Exception {
    return RuleService.class
        .getMethod("updateRule", String.class)
        .getAnnotation(NotifyRuleChange.class);
  }

  private static NotifyFullReload fullReloadAnnotation() throws Exception {
    return RuleService.class.getMethod("deleteAll").getAnnotation(NotifyFullReload.class);
  }

  @Test
  void shouldPublishImmediatelyWithoutATransaction() throws Exception {
    aspect.afterRuleChange(joinPointFor("updateRule", "orders"), ruleChangeAnnotation(), null);

    assertThat(notifier.published).containsExactly("change:orders");
    assertThat(metrics.getPublishedNotifications()).isEqualTo(1);
  }

  @Test
  void shouldDeferThePublishUntilAfterCommitWhenATransactionIsActive() throws Exception {
    TransactionSynchronizationManager.initSynchronization();
    try {
      aspect.afterRuleChange(joinPointFor("updateRule", "orders"), ruleChangeAnnotation(), null);

      // The method has returned but the transaction has not committed: nothing published yet.
      assertThat(notifier.published).isEmpty();
      assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

      for (TransactionSynchronization synchronization :
          TransactionSynchronizationManager.getSynchronizations()) {
        synchronization.afterCommit();
      }

      assertThat(notifier.published).containsExactly("change:orders");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void shouldDeferAFullReloadUntilAfterCommitAsWell() throws Exception {
    TransactionSynchronizationManager.initSynchronization();
    try {
      aspect.afterFullReload(joinPointFor("deleteAll"), fullReloadAnnotation());

      assertThat(notifier.published).isEmpty();
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(TransactionSynchronization::afterCommit);

      assertThat(notifier.published).containsExactly("fullReload");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void shouldNotPublishWhenTheTransactionRollsBack() throws Exception {
    TransactionSynchronizationManager.initSynchronization();
    try {
      aspect.afterRuleChange(joinPointFor("updateRule", "orders"), ruleChangeAnnotation(), null);

      // No afterCommit callback is invoked on a rollback.
      assertThat(notifier.published).isEmpty();
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void shouldRetryAFailedPublish() throws Exception {
    notifier.failNextTimes(2);

    aspect.afterRuleChange(joinPointFor("updateRule", "orders"), ruleChangeAnnotation(), null);

    assertThat(awaitUntil(() -> !notifier.published.isEmpty())).isTrue();
    assertThat(notifier.published).containsExactly("change:orders");
    assertThat(metrics.getRetriedNotifications()).isEqualTo(2);
    assertThat(metrics.getFailedNotifications()).isZero();
  }

  @Test
  void shouldCountANotificationThatFailsEveryAttempt() throws Exception {
    notifier.failNextTimes(Integer.MAX_VALUE);

    aspect.afterRuleChange(joinPointFor("updateRule", "orders"), ruleChangeAnnotation(), null);

    assertThat(awaitUntil(() -> metrics.getFailedNotifications() == 1)).isTrue();
    assertThat(notifier.published).isEmpty();
  }

  @Test
  void shouldNotFailTheBusinessOperationWhenTheExpressionCannotBeEvaluated() throws Exception {
    // "#ruleSetId" resolves against a parameter list the signature does not describe.
    aspect.afterRuleChange(joinPointFor("deleteAll"), ruleChangeAnnotation(), null);

    assertThat(notifier.published).isEmpty();
  }

  @Test
  void shouldReuseTheParsedExpression() throws Exception {
    for (int i = 0; i < 5; i++) {
      aspect.afterRuleChange(joinPointFor("updateRule", "orders"), ruleChangeAnnotation(), null);
    }

    assertThat(notifier.published).hasSize(5);
    assertThat(aspect.getMetrics().getPublishedNotifications()).isEqualTo(5);
  }

  @Test
  void shouldNotCountAPublishWithoutReceiversAsPublished() throws Exception {
    RuleChangeNotifier nobodyListening =
        new RuleChangeNotifier() {
          @Override
          public void notifyChange(String ruleSetId) {}

          @Override
          public void notifyFullReload() {}

          @Override
          public long publishChange(String ruleSetId) {
            return 0L;
          }

          @Override
          public long publishFullReload() {
            return 0L;
          }

          @Override
          public void close() {}
        };
    RuleChangeNotifierMetrics zeroMetrics = new RuleChangeNotifierMetrics();
    RuleChangeAspect zeroAspect = new RuleChangeAspect(nobodyListening, zeroMetrics);
    try {
      zeroAspect.afterRuleChange(
          joinPointFor("updateRule", "orders"), ruleChangeAnnotation(), null);
      zeroAspect.afterFullReload(joinPointFor("deleteAll"), fullReloadAnnotation());

      assertThat(zeroMetrics.getPublishedNotifications()).isZero();
      assertThat(zeroMetrics.getNoReceiverNotifications()).isEqualTo(2);
    } finally {
      zeroAspect.shutdown();
    }
  }

  @Test
  void shouldCountARetryDroppedAtShutdownAsFailed() throws Exception {
    notifier.failNextTimes(1);

    aspect.afterRuleChange(joinPointFor("updateRule", "orders"), ruleChangeAnnotation(), null);
    assertThat(metrics.getRetriedNotifications()).isEqualTo(1);

    aspect.shutdown(); // the retry is still waiting for its ~100ms backoff

    assertThat(metrics.getFailedNotifications()).isEqualTo(1);
    assertThat(notifier.published).isEmpty();
  }
}
