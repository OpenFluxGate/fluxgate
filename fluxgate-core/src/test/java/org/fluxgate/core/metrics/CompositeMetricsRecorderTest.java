package org.fluxgate.core.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.junit.jupiter.api.Test;

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
}
