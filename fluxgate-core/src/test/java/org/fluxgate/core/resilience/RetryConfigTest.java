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
        @DisplayName("should retry timeout exceptions")
        void shouldRetryTimeoutExceptions() {
            RetryConfig config = RetryConfig.defaults();

            assertThat(config.shouldRetry(new FluxgateTimeoutException("timeout"))).isTrue();
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
    }

    @Nested
    @DisplayName("calculateBackoff")
    class CalculateBackoffTests {

        @Test
        @DisplayName("should return initial backoff for first attempt")
        void shouldReturnInitialBackoffForFirstAttempt() {
            RetryConfig config =
                    RetryConfig.builder().initialBackoff(Duration.ofMillis(100)).build();

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

            assertThat(config.calculateBackoff(3)).isEqualTo(Duration.ofMillis(500));
            assertThat(config.calculateBackoff(10)).isEqualTo(Duration.ofMillis(500));
        }
    }
}
