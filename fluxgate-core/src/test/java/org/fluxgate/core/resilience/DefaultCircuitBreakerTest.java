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

            // Next state check should transition to HALF_OPEN
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
        @DisplayName("should return null when OPEN with FAIL_OPEN")
        void shouldReturnNullWhenOpenWithFailOpen() throws Exception {
            CircuitBreakerConfig failOpenConfig =
                    CircuitBreakerConfig.builder()
                            .enabled(true)
                            .failureThreshold(3)
                            .fallbackStrategy(CircuitBreakerConfig.FallbackStrategy.FAIL_OPEN)
                            .build();
            DefaultCircuitBreaker failOpenCb = new DefaultCircuitBreaker("test-fail-open", failOpenConfig);

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

            // Should return null (fail-open behavior)
            String result = failOpenCb.execute(() -> "should not execute");
            assertThat(result).isNull();
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
