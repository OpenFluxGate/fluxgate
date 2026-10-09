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
import org.fluxgate.core.exception.FluxgateConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ResilientExecutor")
class ResilientExecutorTest {

  private static RetryConfig noRetry() {
    return RetryConfig.builder().maxAttempts(1).build();
  }

  private static CircuitBreakerConfig breakerConfig(int failureThreshold) {
    return CircuitBreakerConfig.builder()
        .enabled(true)
        .failureThreshold(failureThreshold)
        .waitDurationInOpenState(Duration.ofSeconds(30))
        .build();
  }

  @Nested
  @DisplayName("executeWithFallback")
  class ExecuteWithFallbackTests {

    @Test
    @DisplayName("should let the circuit breaker see failures so that the circuit opens")
    void shouldOpenTheCircuitWhenTheOperationKeepsFailing() {
      ResilientExecutor executor =
          new ResilientExecutor(noRetry(), breakerConfig(3), "resilient-open");
      CircuitBreaker breaker = executor.getCircuitBreaker();
      AtomicInteger invocations = new AtomicInteger(0);

      for (int i = 0; i < 3; i++) {
        String result =
            executor.executeWithFallback(
                "consume",
                () -> {
                  invocations.incrementAndGet();
                  throw new FluxgateConnectionException("redis is down");
                },
                () -> "fallback");

        assertThat(result).isEqualTo("fallback");
      }

      // Before the fix the inner lambda swallowed the failure, so the breaker recorded a success
      // on every call and never reached its threshold.
      assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

      // With the circuit open the action is not attempted at all
      String shortCircuited =
          executor.executeWithFallback(
              "consume",
              () -> {
                invocations.incrementAndGet();
                return "should not run";
              },
              () -> "fallback");

      assertThat(shortCircuited).isEqualTo("fallback");
      assertThat(invocations.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("should return the result when the operation succeeds")
    void shouldReturnResultOnSuccess() {
      ResilientExecutor executor =
          new ResilientExecutor(noRetry(), breakerConfig(3), "resilient-success");

      assertThat(executor.executeWithFallback("consume", () -> "ok", () -> "fallback"))
          .isEqualTo("ok");
      assertThat(executor.getCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("should retry before falling back")
    void shouldRetryBeforeFallingBack() {
      RetryConfig retryConfig =
          RetryConfig.builder().maxAttempts(3).initialBackoff(Duration.ofMillis(1)).build();
      ResilientExecutor executor =
          new ResilientExecutor(retryConfig, breakerConfig(3), "resilient-retry");
      AtomicInteger attempts = new AtomicInteger(0);

      String result =
          executor.executeWithFallback(
              "consume",
              () -> {
                if (attempts.incrementAndGet() < 3) {
                  throw new FluxgateConnectionException("transient");
                }
                return "recovered";
              },
              () -> "fallback");

      assertThat(result).isEqualTo("recovered");
      assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("should reject a null fallback")
    void shouldRejectNullFallback() {
      ResilientExecutor executor = ResilientExecutor.disabled();

      assertThatThrownBy(() -> executor.executeWithFallback("consume", () -> "ok", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("fallback must not be null");
    }
  }

  @Nested
  @DisplayName("execute")
  class ExecuteTests {

    @Test
    @DisplayName("should propagate the original unchecked exception")
    void shouldPropagateOriginalException() {
      ResilientExecutor executor =
          new ResilientExecutor(noRetry(), CircuitBreakerConfig.disabled(), "resilient-throw");

      assertThatThrownBy(
              () ->
                  executor.execute(
                      "consume",
                      () -> {
                        throw new FluxgateConnectionException("redis is down");
                      }))
          .isInstanceOf(FluxgateConnectionException.class)
          .hasMessage("redis is down");
    }

    @Test
    @DisplayName("should throw CircuitBreakerOpenException once the circuit is open")
    void shouldThrowWhenCircuitIsOpen() {
      ResilientExecutor executor =
          new ResilientExecutor(noRetry(), breakerConfig(2), "resilient-open-throw");

      for (int i = 0; i < 2; i++) {
        try {
          executor.execute(
              "consume",
              () -> {
                throw new FluxgateConnectionException("redis is down");
              });
        } catch (Exception ignored) {
        }
      }

      assertThatThrownBy(() -> executor.execute("consume", () -> "should not run"))
          .isInstanceOf(CircuitBreakerOpenException.class);
    }
  }
}
