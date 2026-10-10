package org.fluxgate.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class LogThrottleTest {

  @Test
  void firstAcquisitionSucceedsAndRepeatsWithinTheIntervalDoNot() {
    LogThrottle throttle = new LogThrottle(Duration.ofHours(1));

    assertThat(throttle.tryAcquire()).isTrue();
    assertThat(throttle.tryAcquire()).isFalse();
    assertThat(throttle.tryAcquire()).isFalse();
  }

  @Test
  void acquiresAgainOnceTheIntervalHasElapsed() throws InterruptedException {
    LogThrottle throttle = new LogThrottle(Duration.ofMillis(1));

    assertThat(throttle.tryAcquire()).isTrue();
    Thread.sleep(5);
    assertThat(throttle.tryAcquire()).isTrue();
  }

  @Test
  void rejectsANonPositiveInterval() {
    assertThatThrownBy(() -> new LogThrottle(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
