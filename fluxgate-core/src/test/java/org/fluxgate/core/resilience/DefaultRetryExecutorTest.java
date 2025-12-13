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

import org.fluxgate.core.exception.FluxgateConfigurationException;
import org.fluxgate.core.exception.FluxgateConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultRetryExecutor")
class DefaultRetryExecutorTest {

    @Nested
    @DisplayName("execute")
    class ExecuteTests {

        @Test
        @DisplayName("should return result on success")
        void shouldReturnResultOnSuccess() throws Exception {
            RetryExecutor executor = new DefaultRetryExecutor(RetryConfig.defaults());

            String result = executor.execute(() -> "success");

            assertThat(result).isEqualTo("success");
        }

        @Test
        @DisplayName("should retry on retryable exception")
        void shouldRetryOnRetryableException() throws Exception {
            AtomicInteger attempts = new AtomicInteger(0);
            RetryConfig config =
                    RetryConfig.builder()
                            .maxAttempts(3)
                            .initialBackoff(Duration.ofMillis(10))
                            .build();
            RetryExecutor executor = new DefaultRetryExecutor(config);

            String result =
                    executor.execute(
                            "test-op",
                            () -> {
                                if (attempts.incrementAndGet() < 3) {
                                    throw new FluxgateConnectionException("connection failed");
                                }
                                return "success";
                            });

            assertThat(result).isEqualTo("success");
            assertThat(attempts.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("should not retry on non-retryable exception")
        void shouldNotRetryOnNonRetryableException() {
            AtomicInteger attempts = new AtomicInteger(0);
            RetryExecutor executor = new DefaultRetryExecutor(RetryConfig.defaults());

            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            () -> {
                                                attempts.incrementAndGet();
                                                throw new FluxgateConfigurationException(
                                                        "config error");
                                            }))
                    .isInstanceOf(FluxgateConfigurationException.class);

            assertThat(attempts.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should throw after max attempts exceeded")
        void shouldThrowAfterMaxAttemptsExceeded() {
            AtomicInteger attempts = new AtomicInteger(0);
            RetryConfig config =
                    RetryConfig.builder()
                            .maxAttempts(3)
                            .initialBackoff(Duration.ofMillis(10))
                            .build();
            RetryExecutor executor = new DefaultRetryExecutor(config);

            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            () -> {
                                                attempts.incrementAndGet();
                                                throw new FluxgateConnectionException(
                                                        "always fails");
                                            }))
                    .isInstanceOf(FluxgateConnectionException.class);

            assertThat(attempts.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("should not retry when disabled")
        void shouldNotRetryWhenDisabled() {
            AtomicInteger attempts = new AtomicInteger(0);
            RetryExecutor executor = new DefaultRetryExecutor(RetryConfig.disabled());

            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            () -> {
                                                attempts.incrementAndGet();
                                                throw new FluxgateConnectionException("error");
                                            }))
                    .isInstanceOf(FluxgateConnectionException.class);

            assertThat(attempts.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("executeVoid")
    class ExecuteVoidTests {

        @Test
        @DisplayName("should execute runnable")
        void shouldExecuteRunnable() throws Exception {
            AtomicInteger counter = new AtomicInteger(0);
            RetryExecutor executor = new DefaultRetryExecutor(RetryConfig.defaults());

            executor.executeVoid(counter::incrementAndGet);

            assertThat(counter.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should retry runnable on failure")
        void shouldRetryRunnableOnFailure() throws Exception {
            AtomicInteger attempts = new AtomicInteger(0);
            RetryConfig config =
                    RetryConfig.builder()
                            .maxAttempts(3)
                            .initialBackoff(Duration.ofMillis(10))
                            .build();
            RetryExecutor executor = new DefaultRetryExecutor(config);

            executor.executeVoid(
                    "test-op",
                    () -> {
                        if (attempts.incrementAndGet() < 3) {
                            throw new FluxgateConnectionException("connection failed");
                        }
                    });

            assertThat(attempts.get()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("NoOpRetryExecutor")
    class NoOpRetryExecutorTests {

        @Test
        @DisplayName("should execute without retry")
        void shouldExecuteWithoutRetry() {
            AtomicInteger attempts = new AtomicInteger(0);
            RetryExecutor executor = NoOpRetryExecutor.getInstance();

            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            () -> {
                                                attempts.incrementAndGet();
                                                throw new FluxgateConnectionException("error");
                                            }))
                    .isInstanceOf(FluxgateConnectionException.class);

            assertThat(attempts.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should return disabled config")
        void shouldReturnDisabledConfig() {
            RetryExecutor executor = NoOpRetryExecutor.getInstance();

            assertThat(executor.getConfig().isEnabled()).isFalse();
        }
    }
}
