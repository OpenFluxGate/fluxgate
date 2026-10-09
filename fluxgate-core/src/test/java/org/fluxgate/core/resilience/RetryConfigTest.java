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

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.fluxgate.core.exception.FluxgateConfigurationException;
import org.fluxgate.core.exception.FluxgateConnectionException;
import org.fluxgate.core.exception.FluxgateTimeoutException;
import org.fluxgate.core.exception.RedisConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RetryConfig")
class RetryConfigTest {

  @Nested
  @DisplayName("Builder")
  class BuilderTests {

    @Test
    @DisplayName("should create default configuration")
    void shouldCreateDefaultConfiguration() {
      RetryConfig config = RetryConfig.defaults();

      assertThat(config.isEnabled()).isTrue();
      assertThat(config.getMaxAttempts()).isEqualTo(3);
      assertThat(config.getInitialBackoff()).isEqualTo(Duration.ofMillis(100));
      assertThat(config.getMultiplier()).isEqualTo(2.0);
      assertThat(config.getMaxBackoff()).isEqualTo(Duration.ofSeconds(2));
      assertThat(config.getJitterFactor()).isEqualTo(0.2);
      assertThat(config.isRetryOnTimeout()).isFalse();
    }

    @Test
    @DisplayName("should create disabled configuration")
    void shouldCreateDisabledConfiguration() {
      RetryConfig config = RetryConfig.disabled();

      assertThat(config.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("should accept custom values")
    void shouldAcceptCustomValues() {
      RetryConfig config =
          RetryConfig.builder()
              .enabled(true)
              .maxAttempts(5)
              .initialBackoff(Duration.ofMillis(200))
              .multiplier(1.5)
              .maxBackoff(Duration.ofSeconds(5))
              .build();

      assertThat(config.getMaxAttempts()).isEqualTo(5);
      assertThat(config.getInitialBackoff()).isEqualTo(Duration.ofMillis(200));
      assertThat(config.getMultiplier()).isEqualTo(1.5);
      assertThat(config.getMaxBackoff()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("should reject invalid maxAttempts")
    void shouldRejectInvalidMaxAttempts() {
      assertThatThrownBy(() -> RetryConfig.builder().maxAttempts(0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("maxAttempts must be >= 1");
    }

    @Test
    @DisplayName("should reject invalid multiplier")
    void shouldRejectInvalidMultiplier() {
      assertThatThrownBy(() -> RetryConfig.builder().multiplier(0.5))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("multiplier must be >= 1.0");
    }

    @Test
    @DisplayName("should allow adding custom retryable exceptions")
    void shouldAllowAddingCustomRetryableExceptions() {
      RetryConfig config = RetryConfig.builder().retryOn(IOException.class).build();

      assertThat(config.shouldRetry(new IOException("test"))).isTrue();
    }

    @Test
    @DisplayName("should reject invalid jitterFactor")
    void shouldRejectInvalidJitterFactor() {
      assertThatThrownBy(() -> RetryConfig.builder().jitterFactor(1.0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("jitterFactor must be >= 0.0 and < 1.0");
      assertThatThrownBy(() -> RetryConfig.builder().jitterFactor(-0.1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("jitterFactor must be >= 0.0 and < 1.0");
    }

    @Test
    @DisplayName("should reject null durations")
    void shouldRejectNullDurations() {
      assertThatThrownBy(() -> RetryConfig.builder().initialBackoff(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("initialBackoff must not be null");
      assertThatThrownBy(() -> RetryConfig.builder().maxBackoff(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("maxBackoff must not be null");
    }

    @Test
    @DisplayName("should reject negative durations")
    void shouldRejectNegativeDurations() {
      assertThatThrownBy(() -> RetryConfig.builder().initialBackoff(Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("initialBackoff must not be negative");
      assertThatThrownBy(() -> RetryConfig.builder().maxBackoff(Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("maxBackoff must not be negative");
    }

    @Test
    @DisplayName("should compare by value")
    void shouldCompareByValue() {
      RetryConfig left = RetryConfig.builder().maxAttempts(4).jitterFactor(0.1).build();
      RetryConfig right = RetryConfig.builder().maxAttempts(4).jitterFactor(0.1).build();
      RetryConfig other = RetryConfig.builder().maxAttempts(4).jitterFactor(0.3).build();

      assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);
      assertThat(left).isNotEqualTo(other);
      assertThat(left.toString()).contains("maxAttempts=4", "retryOnTimeout=false");
    }
  }

  @Nested
  @DisplayName("shouldRetry")
  class ShouldRetryTests {

    @Test
    @DisplayName("should return false when disabled")
    void shouldReturnFalseWhenDisabled() {
      RetryConfig config = RetryConfig.disabled();

      assertThat(config.shouldRetry(new FluxgateConnectionException("error"))).isFalse();
    }

    @Test
    @DisplayName("should retry connection exceptions")
    void shouldRetryConnectionExceptions() {
      RetryConfig config = RetryConfig.defaults();

      assertThat(config.shouldRetry(new FluxgateConnectionException("error"))).isTrue();
      assertThat(config.shouldRetry(new RedisConnectionException("error"))).isTrue();
    }

    @Test
    @DisplayName("should not retry timeout exceptions by default")
    void shouldNotRetryTimeoutExceptionsByDefault() {
      RetryConfig config = RetryConfig.defaults();

      // A timed-out consume may already have been applied, so retrying it would double-charge
      assertThat(config.shouldRetry(new FluxgateTimeoutException("timeout"))).isFalse();
      assertThat(config.shouldRetry(new TimeoutException("timeout"))).isFalse();
    }

    @Test
    @DisplayName("should retry timeout exceptions when retryOnTimeout is enabled")
    void shouldRetryTimeoutExceptionsWhenEnabled() {
      RetryConfig config = RetryConfig.builder().retryOnTimeout(true).build();

      assertThat(config.shouldRetry(new FluxgateTimeoutException("timeout"))).isTrue();
      assertThat(config.shouldRetry(new TimeoutException("timeout"))).isFalse();
    }

    @Test
    @DisplayName("should not retry configuration exceptions")
    void shouldNotRetryConfigurationExceptions() {
      RetryConfig config = RetryConfig.defaults();

      assertThat(config.shouldRetry(new FluxgateConfigurationException("error"))).isFalse();
    }

    @Test
    @DisplayName("should check isRetryable on FluxgateException")
    void shouldCheckIsRetryableOnFluxgateException() {
      RetryConfig config = RetryConfig.defaults();

      // RedisConnectionException.isRetryable() returns true
      assertThat(config.shouldRetry(new RedisConnectionException("error"))).isTrue();
    }

    @Test
    @DisplayName("should let isRetryable=false win over the class allow-list")
    void shouldLetIsRetryableFalseWinOverAllowList() {
      // The class is explicitly allow-listed, yet this instance declares itself non-retryable
      RetryConfig config =
          RetryConfig.builder().retryOn(NonRetryableConnectionException.class).build();

      assertThat(config.shouldRetry(new NonRetryableConnectionException("permanent"))).isFalse();
      assertThat(config.shouldRetry(new FluxgateConnectionException("transient"))).isTrue();
    }
  }

  /** A FluxGate exception whose instance-level verdict contradicts the class allow-list. */
  private static final class NonRetryableConnectionException extends FluxgateConnectionException {

    private NonRetryableConnectionException(String message) {
      super(message);
    }

    @Override
    public boolean isRetryable() {
      return false;
    }
  }

  @Nested
  @DisplayName("calculateBackoff")
  class CalculateBackoffTests {

    @Test
    @DisplayName("should return initial backoff for first attempt without jitter")
    void shouldReturnInitialBackoffForFirstAttempt() {
      RetryConfig config =
          RetryConfig.builder().initialBackoff(Duration.ofMillis(100)).jitterFactor(0.0).build();

      assertThat(config.calculateBackoff(1)).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("should apply exponential backoff")
    void shouldApplyExponentialBackoff() {
      RetryConfig config =
          RetryConfig.builder()
              .initialBackoff(Duration.ofMillis(100))
              .multiplier(2.0)
              .maxBackoff(Duration.ofSeconds(10))
              .jitterFactor(0.0)
              .build();

      assertThat(config.calculateBackoff(1)).isEqualTo(Duration.ofMillis(100));
      assertThat(config.calculateBackoff(2)).isEqualTo(Duration.ofMillis(200));
      assertThat(config.calculateBackoff(3)).isEqualTo(Duration.ofMillis(400));
    }

    @Test
    @DisplayName("should not exceed max backoff")
    void shouldNotExceedMaxBackoff() {
      RetryConfig config =
          RetryConfig.builder()
              .initialBackoff(Duration.ofMillis(100))
              .multiplier(10.0)
              .maxBackoff(Duration.ofMillis(500))
              .build();

      // Jitter can only shorten a backoff that already sits at the cap
      for (int i = 0; i < 200; i++) {
        assertThat(config.calculateBackoff(3)).isLessThanOrEqualTo(Duration.ofMillis(500));
        assertThat(config.calculateBackoff(10)).isLessThanOrEqualTo(Duration.ofMillis(500));
      }
    }

    @Test
    @DisplayName("should keep jittered backoff within the configured bounds")
    void shouldKeepJitterWithinBounds() {
      RetryConfig config =
          RetryConfig.builder()
              .initialBackoff(Duration.ofMillis(1000))
              .multiplier(2.0)
              .maxBackoff(Duration.ofSeconds(30))
              .jitterFactor(0.2)
              .build();

      // attempt 2 -> base 2000ms, so every value must land inside [1600ms, 2400ms]
      boolean sawSpread = false;
      for (int i = 0; i < 500; i++) {
        Duration backoff = config.calculateBackoff(2);

        assertThat(backoff).isBetween(Duration.ofMillis(1600), Duration.ofMillis(2400));
        if (!backoff.equals(Duration.ofMillis(2000))) {
          sawSpread = true;
        }
      }

      assertThat(sawSpread).as("jitter should actually vary the backoff").isTrue();
    }

    @Test
    @DisplayName("should never return a negative backoff")
    void shouldNeverReturnNegativeBackoff() {
      RetryConfig config =
          RetryConfig.builder()
              .initialBackoff(Duration.ofMillis(1))
              .maxBackoff(Duration.ofMillis(5))
              .jitterFactor(0.9)
              .build();

      for (int i = 0; i < 200; i++) {
        assertThat(config.calculateBackoff(1)).isGreaterThanOrEqualTo(Duration.ZERO);
      }
    }
  }
}
