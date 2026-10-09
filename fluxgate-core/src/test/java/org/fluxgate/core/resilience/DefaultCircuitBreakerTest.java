/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fluxgate.core.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultCircuitBreaker")
class DefaultCircuitBreakerTest {

  private CircuitBreakerConfig config;
  private DefaultCircuitBreaker circuitBreaker;

  @BeforeEach
  void setUp() {
    config =
        CircuitBreakerConfig.builder()
            .enabled(true)
            .failureThreshold(3)
            .waitDurationInOpenState(Duration.ofMillis(100))
            .permittedCallsInHalfOpenState(2)
            .fallbackStrategy(CircuitBreakerConfig.FallbackStrategy.FAIL_CLOSED)
            .build();
    circuitBreaker = new DefaultCircuitBreaker("test", config);
  }

  @Nested
  @DisplayName("State Transitions")
  class StateTransitionTests {

    @Test
    @DisplayName("should start in CLOSED state")
    void shouldStartInClosedState() {
      assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("should transition to OPEN after failure threshold")
    void shouldTransitionToOpenAfterFailureThreshold() {
      // Trigger failures up to threshold
      for (int i = 0; i < 3; i++) {
        try {
          circuitBreaker.execute(
              () -> {
                throw new RuntimeException("failure");
              });
        } catch (Exception ignored) {
        }
      }

      assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("should transition to HALF_OPEN after wait duration")
    void shouldTransitionToHalfOpenAfterWaitDuration() throws Exception {
      // Open the circuit
      for (int i = 0; i < 3; i++) {
        try {
          circuitBreaker.execute(
              () -> {
                throw new RuntimeException("failure");
              });
        } catch (Exception ignored) {
        }
      }

      assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

      // Wait for transition
      Thread.sleep(150);

      // The transition happens on the execute path, not when the state is merely observed
      assertThat(circuitBreaker.tryTransitionToHalfOpen()).isTrue();
      assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    @DisplayName("should transition from HALF_OPEN to CLOSED on success")
    void shouldTransitionToClosedOnSuccess() throws Exception {
      // Open the circuit
      for (int i = 0; i < 3; i++) {
        try {
          circuitBreaker.execute(
              () -> {
                throw new RuntimeException("failure");
              });
        } catch (Exception ignored) {
        }
      }

      // Wait for transition to HALF_OPEN
      Thread.sleep(150);

      // Successful calls in HALF_OPEN
      for (int i = 0; i < 2; i++) {
        circuitBreaker.execute(() -> "success");
      }

      assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("should transition from HALF_OPEN to OPEN on failure")
    void shouldTransitionToOpenOnFailureInHalfOpen() throws Exception {
      // Open the circuit
      for (int i = 0; i < 3; i++) {
        try {
          circuitBreaker.execute(
              () -> {
                throw new RuntimeException("failure");
              });
        } catch (Exception ignored) {
        }
      }

      // Wait for transition to HALF_OPEN
      Thread.sleep(150);
      assertThat(circuitBreaker.tryTransitionToHalfOpen()).isTrue();
      assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

      // Failure in HALF_OPEN
      try {
        circuitBreaker.execute(
            () -> {
              throw new RuntimeException("failure");
            });
      } catch (Exception ignored) {
      }

      assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
  }

  @Nested
  @DisplayName("execute")
  class ExecuteTests {

    @Test
    @DisplayName("should allow calls when CLOSED")
    void shouldAllowCallsWhenClosed() throws Exception {
      String result = circuitBreaker.execute(() -> "success");

      assertThat(result).isEqualTo("success");
    }

    @Test
    @DisplayName("should throw CircuitBreakerOpenException when OPEN with FAIL_CLOSED")
    void shouldThrowWhenOpenWithFailClosed() {
      // Open the circuit
      for (int i = 0; i < 3; i++) {
        try {
          circuitBreaker.execute(
              () -> {
                throw new RuntimeException("failure");
              });
        } catch (Exception ignored) {
        }
      }

      assertThatThrownBy(() -> circuitBreaker.execute(() -> "should not execute"))
          .isInstanceOf(CircuitBreakerOpenException.class)
          .hasMessageContaining("test");
    }

    @Test
    @DisplayName("should throw instead of returning null when OPEN with FAIL_OPEN")
    void shouldThrowWhenOpenWithFailOpen() {
      CircuitBreakerConfig failOpenConfig =
          CircuitBreakerConfig.builder()
              .enabled(true)
              .failureThreshold(3)
              .fallbackStrategy(CircuitBreakerConfig.FallbackStrategy.FAIL_OPEN)
              .build();
      DefaultCircuitBreaker failOpenCb =
          new DefaultCircuitBreaker("test-fail-open", failOpenConfig);

      // Open the circuit
      for (int i = 0; i < 3; i++) {
        try {
          failOpenCb.execute(
              () -> {
                throw new RuntimeException("failure");
              });
        } catch (Exception ignored) {
        }
      }

      // execute() never hands a null result to a caller expecting a value
      assertThatThrownBy(() -> failOpenCb.execute(() -> "should not execute"))
          .isInstanceOf(CircuitBreakerOpenException.class);
    }

    @Test
    @DisplayName("should reset failure count on success")
    void shouldResetFailureCountOnSuccess() throws Exception {
      // Some failures
      for (int i = 0; i < 2; i++) {
        try {
          circuitBreaker.execute(
              () -> {
                throw new RuntimeException("failure");
              });
        } catch (Exception ignored) {
        }
      }

      // Success resets count
      circuitBreaker.execute(() -> "success");

      assertThat(circuitBreaker.getFailureCount()).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("executeWithFallback")
  class ExecuteWithFallbackTests {

    @Test
    @DisplayName("should return result on success")
    void shouldReturnResultOnSuccess() {
      String result = circuitBreaker.executeWithFallback(() -> "success", () -> "fallback");

      assertThat(result).isEqualTo("success");
    }

    @Test
    @DisplayName("should return fallback on failure")
    void shouldReturnFallbackOnFailure() {
      String result =
          circuitBreaker.executeWithFallback(
              () -> {
                throw new RuntimeException("failure");
              },
              () -> "fallback");

      assertThat(result).isEqualTo("fallback");
    }

    @Test
    @DisplayName("should return fallback when circuit is open")
    void shouldReturnFallbackWhenCircuitIsOpen() {
      // Open the circuit
      for (int i = 0; i < 3; i++) {
        circuitBreaker.executeWithFallback(
            () -> {
              throw new RuntimeException("failure");
            },
            () -> "fallback");
      }

      AtomicInteger callCount = new AtomicInteger(0);
      String result =
          circuitBreaker.executeWithFallback(
              () -> {
                callCount.incrementAndGet();
                return "success";
              },
              () -> "fallback");

      assertThat(result).isEqualTo("fallback");
      assertThat(callCount.get()).isEqualTo(0); // Action should not be called
    }
  }

  @Nested
  @DisplayName("getState")
  class GetStateTests {

    @Test
    @DisplayName("should not advance the state machine when polled")
    void shouldNotTransitionWhenPolled() throws Exception {
      // Open the circuit
      for (int i = 0; i < 3; i++) {
        try {
          circuitBreaker.execute(
              () -> {
                throw new RuntimeException("failure");
              });
        } catch (Exception ignored) {
        }
      }

      Thread.sleep(150);

      // A monitoring endpoint polling the state must not consume the OPEN -> HALF_OPEN transition
      for (int i = 0; i < 5; i++) {
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
      }

      // The trial call still gets through afterwards
      assertThat(circuitBreaker.execute(() -> "trial")).isEqualTo("trial");
      assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }
  }

  @Nested
  @DisplayName("HALF_OPEN trial permits")
  class HalfOpenPermitTests {

    @Test
    @DisplayName("should admit at most permittedCallsInHalfOpenState concurrent trials")
    void shouldLimitConcurrentTrialCalls() throws Exception {
      CircuitBreakerConfig cbConfig =
          CircuitBreakerConfig.builder()
              .enabled(true)
              .failureThreshold(2)
              .waitDurationInOpenState(Duration.ofMillis(50))
              .permittedCallsInHalfOpenState(2)
              .build();
      DefaultCircuitBreaker breaker = new DefaultCircuitBreaker("half-open-permits", cbConfig);

      for (int i = 0; i < 2; i++) {
        breaker.executeWithFallback(
            () -> {
              throw new RuntimeException("failure");
            },
            () -> "fallback");
      }
      assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
      Thread.sleep(80);

      int callers = 6;
      AtomicInteger admitted = new AtomicInteger(0);
      CountDownLatch trialsEntered = new CountDownLatch(2);
      CountDownLatch rejectedCalls = new CountDownLatch(callers - 2);
      CountDownLatch holdTrials = new CountDownLatch(1);
      CountDownLatch allDone = new CountDownLatch(callers);

      ExecutorService pool = Executors.newFixedThreadPool(callers);
      try {
        for (int i = 0; i < callers; i++) {
          pool.execute(
              () -> {
                try {
                  breaker.executeWithFallback(
                      () -> {
                        admitted.incrementAndGet();
                        trialsEntered.countDown();
                        await(holdTrials);
                        return "trial";
                      },
                      () -> {
                        rejectedCalls.countDown();
                        return "fallback";
                      });
                } finally {
                  allDone.countDown();
                }
              });
        }

        // Exactly two callers hold a trial permit; every other caller is served the fallback
        assertThat(trialsEntered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(rejectedCalls.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(admitted.get()).isEqualTo(2);

        holdTrials.countDown();
        assertThat(allDone.await(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        holdTrials.countDown();
        pool.shutdownNow();
      }

      // Two successful trials are enough to close the circuit again
      assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("should reopen and hand out fresh permits after a failed trial")
    void shouldReopenAndResetPermitsAfterFailedTrial() throws Exception {
      // Open the circuit
      for (int i = 0; i < 3; i++) {
        try {
          circuitBreaker.execute(
              () -> {
                throw new RuntimeException("failure");
              });
        } catch (Exception ignored) {
        }
      }

      Thread.sleep(150);
      assertThat(circuitBreaker.tryTransitionToHalfOpen()).isTrue();

      // One failing trial reopens the circuit even though a second permit was still free
      try {
        circuitBreaker.execute(
            () -> {
              throw new RuntimeException("still broken");
            });
      } catch (Exception ignored) {
      }
      assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
      assertThatThrownBy(() -> circuitBreaker.execute(() -> "should not execute"))
          .isInstanceOf(CircuitBreakerOpenException.class);

      // After the next wait duration the full set of permits is available again
      Thread.sleep(150);
      assertThat(circuitBreaker.execute(() -> "trial-1")).isEqualTo("trial-1");
      assertThat(circuitBreaker.execute(() -> "trial-2")).isEqualTo("trial-2");
      assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private void await(CountDownLatch latch) {
      try {
        if (!latch.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("latch was not released in time");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  @Nested
  @DisplayName("Sliding window failure rate")
  class SlidingWindowTests {

    private DefaultCircuitBreaker rateBreaker() {
      return new DefaultCircuitBreaker(
          "rate",
          CircuitBreakerConfig.builder()
              .enabled(true)
              .slidingWindowSize(10)
              .minimumNumberOfCalls(10)
              .failureRateThreshold(50)
              .build());
    }

    @Test
    @DisplayName("should open on intermittent failures that a consecutive counter would miss")
    void shouldOpenOnIntermittentFailures() {
      DefaultCircuitBreaker breaker = rateBreaker();

      // Alternating success/failure: the consecutive counter never exceeds 1, and the window needs
      // 10 recorded calls before the 50% rate is evaluated on the next failure
      for (int i = 0; i < 12; i++) {
        boolean fail = i % 2 == 0;
        breaker.executeWithFallback(
            () -> {
              if (fail) {
                throw new RuntimeException("intermittent failure");
              }
              return "ok";
            },
            () -> "fallback");
      }

      assertThat(breaker.getFailureCount()).isLessThanOrEqualTo(1);
      assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("should not open before minimumNumberOfCalls is reached")
    void shouldNotOpenBeforeMinimumNumberOfCalls() {
      DefaultCircuitBreaker breaker = rateBreaker();

      // 100% failure rate, but only 9 recorded calls
      for (int i = 0; i < 9; i++) {
        breaker.executeWithFallback(
            () -> {
              throw new RuntimeException("failure");
            },
            () -> "fallback");
      }

      assertThat(breaker.getRecordedCalls()).isEqualTo(9);
      assertThat(breaker.getFailureRate()).isEqualTo(100.0);
      assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

      breaker.executeWithFallback(
          () -> {
            throw new RuntimeException("failure");
          },
          () -> "fallback");

      assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("should keep the legacy absolute threshold when set explicitly")
    void shouldHonourExplicitAbsoluteThreshold() {
      // failureThreshold(3) with a window that would need 10 calls: the absolute rule wins
      for (int i = 0; i < 3; i++) {
        circuitBreaker.executeWithFallback(
            () -> {
              throw new RuntimeException("failure");
            },
            () -> "fallback");
      }

      assertThat(circuitBreaker.getConfig().isFailureThresholdExplicit()).isTrue();
      assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
  }

  @Nested
  @DisplayName("reset")
  class ResetTests {

    @Test
    @DisplayName("should reset to CLOSED state")
    void shouldResetToClosedState() {
      // Open the circuit
      for (int i = 0; i < 3; i++) {
        try {
          circuitBreaker.execute(
              () -> {
                throw new RuntimeException("failure");
              });
        } catch (Exception ignored) {
        }
      }

      assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

      circuitBreaker.reset();

      assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
      assertThat(circuitBreaker.getFailureCount()).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Disabled CircuitBreaker")
  class DisabledTests {

    @Test
    @DisplayName("should always execute when disabled")
    void shouldAlwaysExecuteWhenDisabled() throws Exception {
      CircuitBreakerConfig disabledConfig = CircuitBreakerConfig.disabled();
      DefaultCircuitBreaker disabledCb = new DefaultCircuitBreaker("disabled", disabledConfig);

      // Even after many failures, should still execute
      for (int i = 0; i < 10; i++) {
        try {
          disabledCb.execute(
              () -> {
                throw new RuntimeException("failure");
              });
        } catch (RuntimeException ignored) {
        }
      }

      // Should still execute (not throw CircuitBreakerOpenException)
      assertThatThrownBy(
              () ->
                  disabledCb.execute(
                      () -> {
                        throw new RuntimeException("test");
                      }))
          .isInstanceOf(RuntimeException.class)
          .hasMessage("test");
    }
  }

  @Nested
  @DisplayName("CircuitBreakerConfig")
  class ConfigTests {

    @Test
    @DisplayName("should expose sliding window defaults")
    void shouldExposeSlidingWindowDefaults() {
      CircuitBreakerConfig defaults = CircuitBreakerConfig.defaults();

      assertThat(defaults.getSlidingWindowSize()).isEqualTo(20);
      assertThat(defaults.getFailureRateThreshold()).isEqualTo(50);
      assertThat(defaults.getMinimumNumberOfCalls()).isEqualTo(10);
      assertThat(defaults.isFailureThresholdExplicit()).isFalse();
    }

    @Test
    @DisplayName("should reject a null waitDurationInOpenState")
    void shouldRejectNullWaitDuration() {
      assertThatThrownBy(() -> CircuitBreakerConfig.builder().waitDurationInOpenState(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("waitDurationInOpenState must not be null");
    }

    @Test
    @DisplayName("should reject a negative waitDurationInOpenState")
    void shouldRejectNegativeWaitDuration() {
      assertThatThrownBy(
              () -> CircuitBreakerConfig.builder().waitDurationInOpenState(Duration.ofSeconds(-1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be negative");
    }

    @Test
    @DisplayName("should reject minimumNumberOfCalls larger than the window")
    void shouldRejectMinimumLargerThanWindow() {
      assertThatThrownBy(
              () ->
                  CircuitBreakerConfig.builder()
                      .slidingWindowSize(5)
                      .minimumNumberOfCalls(6)
                      .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("minimumNumberOfCalls must not exceed slidingWindowSize");
    }

    @Test
    @DisplayName("should compare by value")
    void shouldCompareByValue() {
      CircuitBreakerConfig left = CircuitBreakerConfig.builder().enabled(true).build();
      CircuitBreakerConfig right = CircuitBreakerConfig.builder().enabled(true).build();
      CircuitBreakerConfig other =
          CircuitBreakerConfig.builder().enabled(true).failureRateThreshold(90).build();

      assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);
      assertThat(left).isNotEqualTo(other);
      assertThat(left.toString()).contains("slidingWindowSize=20", "failureRateThreshold=50");
    }
  }

  @Nested
  @DisplayName("NoOpCircuitBreaker")
  class NoOpCircuitBreakerTests {

    @Test
    @DisplayName("should always return CLOSED state")
    void shouldAlwaysReturnClosedState() {
      CircuitBreaker noOp = NoOpCircuitBreaker.getInstance();

      assertThat(noOp.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("should execute without tracking failures")
    void shouldExecuteWithoutTrackingFailures() {
      CircuitBreaker noOp = NoOpCircuitBreaker.getInstance();

      for (int i = 0; i < 100; i++) {
        try {
          noOp.execute(
              () -> {
                throw new RuntimeException("failure");
              });
        } catch (Exception ignored) {
        }
      }

      // Still CLOSED
      assertThat(noOp.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
  }
}
