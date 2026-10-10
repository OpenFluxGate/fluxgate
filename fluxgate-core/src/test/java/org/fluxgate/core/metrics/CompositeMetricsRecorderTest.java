package org.fluxgate.core.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Unit tests for {@link CompositeMetricsRecorder}. */
class CompositeMetricsRecorderTest {

  @Test
  void shouldDelegateToEveryRecorderInOrder() {
    List<String> invoked = new ArrayList<>();

    CompositeMetricsRecorder composite =
        new CompositeMetricsRecorder(
            List.of(
                (context, result) -> invoked.add("first"),
                (context, result) -> invoked.add("second")));

    composite.record(RequestContext.builder().build(), RateLimitResult.allowedWithoutRule());

    assertThat(invoked).containsExactly("first", "second");
    assertThat(composite.size()).isEqualTo(2);
  }

  @Test
  void shouldKeepGoingWhenARecorderFails() {
    List<String> invoked = new ArrayList<>();

    CompositeMetricsRecorder composite =
        new CompositeMetricsRecorder(
            List.of(
                (context, result) -> {
                  throw new IllegalStateException("recorder backend is down");
                },
                (context, result) -> invoked.add("second")));

    composite.record(RequestContext.builder().build(), RateLimitResult.allowedWithoutRule());

    assertThat(invoked).containsExactly("second");
  }

  @Test
  void shouldKeepGoingWhenARecorderSneakyThrowsACheckedException() {
    List<String> invoked = new ArrayList<>();

    CompositeMetricsRecorder composite =
        new CompositeMetricsRecorder(
            List.of(
                (context, result) -> sneakyThrow(new IOException("recorder backend is down")),
                (context, result) -> invoked.add("second")));

    composite.record(RequestContext.builder().build(), RateLimitResult.allowedWithoutRule());

    assertThat(invoked).containsExactly("second");
  }

  @Test
  void shouldLetAnErrorThrownByARecorderPropagate() {
    CompositeMetricsRecorder composite =
        new CompositeMetricsRecorder(
            List.of(
                (context, result) -> {
                  throw new AssertionError("not a recorder failure");
                }));

    assertThatThrownBy(
            () ->
                composite.record(
                    RequestContext.builder().build(), RateLimitResult.allowedWithoutRule()))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void shouldCallTheHealthyRecorderEveryTimeAndWarnOncePerFailingRecorder() {
    AtomicInteger healthyCalls = new AtomicInteger();
    RateLimitMetricsRecorder failingFirst =
        (context, result) -> {
          throw new IllegalStateException("first backend is down");
        };
    RateLimitMetricsRecorder failingSecond =
        (context, result) -> sneakyThrow(new IOException("second backend is down"));
    CompositeMetricsRecorder composite =
        new CompositeMetricsRecorder(
            List.of(
                failingFirst, (context, result) -> healthyCalls.incrementAndGet(), failingSecond));

    Logger logger = (Logger) LoggerFactory.getLogger(CompositeMetricsRecorder.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    Level previousLevel = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    try {
      for (int i = 0; i < 5; i++) {
        composite.record(RequestContext.builder().build(), RateLimitResult.allowedWithoutRule());
      }
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previousLevel);
    }

    assertThat(healthyCalls).hasValue(5);
    assertThat(appender.list)
        .filteredOn(event -> event.getLevel() == Level.WARN)
        .extracting(event -> event.getArgumentArray()[0])
        .containsExactlyInAnyOrder(
            failingFirst.getClass().getName(), failingSecond.getClass().getName());
  }

  @Test
  void shouldRejectEmptyRecorderList() {
    assertThatThrownBy(() -> new CompositeMetricsRecorder(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recorders must not be empty");
  }

  @Test
  void shouldExposeAnUnmodifiableRecorderList() {
    RateLimitMetricsRecorder recorder = (context, result) -> {};
    CompositeMetricsRecorder composite = new CompositeMetricsRecorder(List.of(recorder));

    assertThatThrownBy(() -> composite.getRecorders().add(recorder))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
    throw (T) t;
  }
}
