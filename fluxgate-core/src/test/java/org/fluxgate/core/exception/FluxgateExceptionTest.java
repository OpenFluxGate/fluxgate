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
package org.fluxgate.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FluxGate Exception Hierarchy")
class FluxgateExceptionTest {

    @Nested
    @DisplayName("FluxgateConfigurationException")
    class ConfigurationExceptionTests {

        @Test
        @DisplayName("should not be retryable")
        void shouldNotBeRetryable() {
            FluxgateConfigurationException exception =
                    new FluxgateConfigurationException("config error");

            assertThat(exception.isRetryable()).isFalse();
            assertThat(exception.getMessage()).isEqualTo("config error");
        }

        @Test
        @DisplayName("should support cause")
        void shouldSupportCause() {
            Throwable cause = new RuntimeException("root cause");
            FluxgateConfigurationException exception =
                    new FluxgateConfigurationException("config error", cause);

            assertThat(exception.getCause()).isEqualTo(cause);
        }
    }

    @Nested
    @DisplayName("InvalidRuleConfigException")
    class InvalidRuleConfigTests {

        @Test
        @DisplayName("should include rule ID in message")
        void shouldIncludeRuleIdInMessage() {
            InvalidRuleConfigException exception =
                    new InvalidRuleConfigException("capacity must be > 0", "rule-123");

            assertThat(exception.getMessage()).contains("capacity must be > 0");
            assertThat(exception.getMessage()).contains("rule-123");
            assertThat(exception.getRuleId()).isEqualTo("rule-123");
        }

        @Test
        @DisplayName("should work without rule ID")
        void shouldWorkWithoutRuleId() {
            InvalidRuleConfigException exception =
                    new InvalidRuleConfigException("invalid configuration");

            assertThat(exception.getRuleId()).isNull();
        }

        @Test
        @DisplayName("should support cause with rule ID")
        void shouldSupportCauseWithRuleId() {
            Throwable cause = new RuntimeException("root cause");
            InvalidRuleConfigException exception =
                    new InvalidRuleConfigException("invalid config", "rule-456", cause);

            assertThat(exception.getCause()).isEqualTo(cause);
            assertThat(exception.getRuleId()).isEqualTo("rule-456");
        }
    }

    @Nested
    @DisplayName("MissingConfigurationException")
    class MissingConfigurationTests {

        @Test
        @DisplayName("should include property name")
        void shouldIncludePropertyName() {
            MissingConfigurationException exception =
                    new MissingConfigurationException(
                            "fluxgate.redis.uri", "Redis URI is required");

            assertThat(exception.getMessage()).contains("fluxgate.redis.uri");
            assertThat(exception.getPropertyName()).isEqualTo("fluxgate.redis.uri");
        }

        @Test
        @DisplayName("should work with simple message")
        void shouldWorkWithSimpleMessage() {
            MissingConfigurationException exception =
                    new MissingConfigurationException("Configuration is incomplete");

            assertThat(exception.getPropertyName()).isNull();
        }
    }

    @Nested
    @DisplayName("FluxgateConnectionException")
    class ConnectionExceptionTests {

        @Test
        @DisplayName("should be retryable")
        void shouldBeRetryable() {
            FluxgateConnectionException exception =
                    new FluxgateConnectionException("connection failed");

            assertThat(exception.isRetryable()).isTrue();
        }
    }

    @Nested
    @DisplayName("RedisConnectionException")
    class RedisConnectionTests {

        @Test
        @DisplayName("should be retryable")
        void shouldBeRetryable() {
            RedisConnectionException exception =
                    new RedisConnectionException("Redis connection failed");

            assertThat(exception.isRetryable()).isTrue();
        }

        @Test
        @DisplayName("should include Redis URI")
        void shouldIncludeRedisUri() {
            RedisConnectionException exception =
                    new RedisConnectionException(
                            "connection failed",
                            "redis://***@localhost:6379",
                            new RuntimeException("timeout"));

            assertThat(exception.getMessage()).contains("redis://***@localhost:6379");
            assertThat(exception.getRedisUri()).isEqualTo("redis://***@localhost:6379");
        }
    }

    @Nested
    @DisplayName("MongoConnectionException")
    class MongoConnectionTests {

        @Test
        @DisplayName("should be retryable")
        void shouldBeRetryable() {
            MongoConnectionException exception =
                    new MongoConnectionException("MongoDB connection failed");

            assertThat(exception.isRetryable()).isTrue();
        }

        @Test
        @DisplayName("should include MongoDB URI")
        void shouldIncludeMongoUri() {
            MongoConnectionException exception =
                    new MongoConnectionException(
                            "connection failed",
                            "mongodb://***@localhost:27017",
                            new RuntimeException("timeout"));

            assertThat(exception.getMessage()).contains("mongodb://***@localhost:27017");
            assertThat(exception.getMongoUri()).isEqualTo("mongodb://***@localhost:27017");
        }
    }

    @Nested
    @DisplayName("FluxgateOperationException")
    class OperationExceptionTests {

        @Test
        @DisplayName("should not be retryable by default")
        void shouldNotBeRetryableByDefault() {
            FluxgateOperationException exception =
                    new FluxgateOperationException("operation failed");

            assertThat(exception.isRetryable()).isFalse();
        }

        @Test
        @DisplayName("should respect retryable flag")
        void shouldRespectRetryableFlag() {
            FluxgateOperationException retryable =
                    new FluxgateOperationException(
                            "operation failed", new RuntimeException(), true);

            FluxgateOperationException notRetryable =
                    new FluxgateOperationException(
                            "operation failed", new RuntimeException(), false);

            assertThat(retryable.isRetryable()).isTrue();
            assertThat(notRetryable.isRetryable()).isFalse();
        }
    }

    @Nested
    @DisplayName("RateLimitExecutionException")
    class RateLimitExecutionTests {

        @Test
        @DisplayName("should be retryable")
        void shouldBeRetryable() {
            RateLimitExecutionException exception =
                    new RateLimitExecutionException("execution failed", new RuntimeException());

            assertThat(exception.isRetryable()).isTrue();
        }

        @Test
        @DisplayName("should include context information")
        void shouldIncludeContextInformation() {
            RateLimitExecutionException exception =
                    new RateLimitExecutionException(
                            "execution failed", "ruleset-1", "127.0.0.1", new RuntimeException());

            assertThat(exception.getMessage()).contains("ruleset-1");
            assertThat(exception.getMessage()).contains("127.0.0.1");
            assertThat(exception.getRuleSetId()).isEqualTo("ruleset-1");
            assertThat(exception.getKey()).isEqualTo("127.0.0.1");
        }
    }

    @Nested
    @DisplayName("ScriptExecutionException")
    class ScriptExecutionTests {

        @Test
        @DisplayName("should be retryable")
        void shouldBeRetryable() {
            ScriptExecutionException exception =
                    new ScriptExecutionException("script failed", new RuntimeException());

            assertThat(exception.isRetryable()).isTrue();
        }

        @Test
        @DisplayName("should include script name")
        void shouldIncludeScriptName() {
            ScriptExecutionException exception =
                    new ScriptExecutionException(
                            "execution failed", "token_bucket.lua", new RuntimeException());

            assertThat(exception.getMessage()).contains("token_bucket.lua");
            assertThat(exception.getScriptName()).isEqualTo("token_bucket.lua");
        }
    }

    @Nested
    @DisplayName("FluxgateTimeoutException")
    class TimeoutExceptionTests {

        @Test
        @DisplayName("should be retryable")
        void shouldBeRetryable() {
            FluxgateTimeoutException exception = new FluxgateTimeoutException("operation timed out");

            assertThat(exception.isRetryable()).isTrue();
        }

        @Test
        @DisplayName("should include timeout information")
        void shouldIncludeTimeoutInformation() {
            Duration timeout = Duration.ofMillis(500);
            FluxgateTimeoutException exception =
                    new FluxgateTimeoutException("redis-connect", timeout);

            assertThat(exception.getMessage()).contains("redis-connect");
            assertThat(exception.getMessage()).contains("500ms");
            assertThat(exception.getOperation()).isEqualTo("redis-connect");
            assertThat(exception.getTimeout()).isEqualTo(timeout);
        }

        @Test
        @DisplayName("should support cause")
        void shouldSupportCause() {
            Duration timeout = Duration.ofSeconds(1);
            Throwable cause = new RuntimeException("underlying timeout");
            FluxgateTimeoutException exception =
                    new FluxgateTimeoutException("operation", timeout, cause);

            assertThat(exception.getCause()).isEqualTo(cause);
        }
    }
}
