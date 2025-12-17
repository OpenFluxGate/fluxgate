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
package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.fluxgate.core.resilience.CircuitBreaker;
import org.fluxgate.core.resilience.CircuitBreakerConfig;
import org.fluxgate.core.resilience.DefaultCircuitBreaker;
import org.fluxgate.core.resilience.DefaultRetryExecutor;
import org.fluxgate.core.resilience.NoOpCircuitBreaker;
import org.fluxgate.core.resilience.NoOpRetryExecutor;
import org.fluxgate.core.resilience.ResilientExecutor;
import org.fluxgate.core.resilience.RetryConfig;
import org.fluxgate.core.resilience.RetryExecutor;
import org.fluxgate.spring.properties.FluxgateResilienceProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("FluxgateResilienceAutoConfiguration")
class FluxgateResilienceAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(FluxgateResilienceAutoConfiguration.class));

  @Nested
  @DisplayName("Default Configuration")
  class DefaultConfigurationTests {

    @Test
    @DisplayName("should create default beans")
    void shouldCreateDefaultBeans() {
      contextRunner.run(
          context -> {
            assertThat(context).hasSingleBean(RetryConfig.class);
            assertThat(context).hasSingleBean(CircuitBreakerConfig.class);
            assertThat(context).hasSingleBean(RetryExecutor.class);
            assertThat(context).hasSingleBean(CircuitBreaker.class);
            assertThat(context).hasSingleBean(ResilientExecutor.class);
          });
    }

    @Test
    @DisplayName("should create enabled retry executor by default")
    void shouldCreateEnabledRetryExecutorByDefault() {
      contextRunner.run(
          context -> {
            RetryExecutor executor = context.getBean(RetryExecutor.class);
            assertThat(executor).isInstanceOf(DefaultRetryExecutor.class);
            assertThat(executor.getConfig().isEnabled()).isTrue();
          });
    }

    @Test
    @DisplayName("should create disabled circuit breaker by default")
    void shouldCreateDisabledCircuitBreakerByDefault() {
      contextRunner.run(
          context -> {
            CircuitBreaker cb = context.getBean(CircuitBreaker.class);
            assertThat(cb).isInstanceOf(NoOpCircuitBreaker.class);
          });
    }
  }

  @Nested
  @DisplayName("Retry Configuration")
  class RetryConfigurationTests {

    @Test
    @DisplayName("should configure retry from properties")
    void shouldConfigureRetryFromProperties() {
      contextRunner
          .withPropertyValues(
              "fluxgate.resilience.retry.enabled=true",
              "fluxgate.resilience.retry.max-attempts=5",
              "fluxgate.resilience.retry.initial-backoff=200ms",
              "fluxgate.resilience.retry.multiplier=3.0",
              "fluxgate.resilience.retry.max-backoff=5s")
          .run(
              context -> {
                RetryConfig config = context.getBean(RetryConfig.class);
                assertThat(config.getMaxAttempts()).isEqualTo(5);
                assertThat(config.getInitialBackoff().toMillis()).isEqualTo(200);
                assertThat(config.getMultiplier()).isEqualTo(3.0);
                assertThat(config.getMaxBackoff().toSeconds()).isEqualTo(5);
              });
    }

    @Test
    @DisplayName("should create no-op retry executor when disabled")
    void shouldCreateNoOpRetryExecutorWhenDisabled() {
      contextRunner
          .withPropertyValues("fluxgate.resilience.retry.enabled=false")
          .run(
              context -> {
                RetryExecutor executor = context.getBean(RetryExecutor.class);
                assertThat(executor).isInstanceOf(NoOpRetryExecutor.class);
              });
    }
  }

  @Nested
  @DisplayName("Circuit Breaker Configuration")
  class CircuitBreakerConfigurationTests {

    @Test
    @DisplayName("should create enabled circuit breaker when configured")
    void shouldCreateEnabledCircuitBreakerWhenConfigured() {
      contextRunner
          .withPropertyValues("fluxgate.resilience.circuit-breaker.enabled=true")
          .run(
              context -> {
                CircuitBreaker cb = context.getBean(CircuitBreaker.class);
                assertThat(cb).isInstanceOf(DefaultCircuitBreaker.class);
              });
    }

    @Test
    @DisplayName("should configure circuit breaker from properties")
    void shouldConfigureCircuitBreakerFromProperties() {
      contextRunner
          .withPropertyValues(
              "fluxgate.resilience.circuit-breaker.enabled=true",
              "fluxgate.resilience.circuit-breaker.failure-threshold=10",
              "fluxgate.resilience.circuit-breaker.wait-duration-in-open-state=60s",
              "fluxgate.resilience.circuit-breaker.permitted-calls-in-half-open-state=5",
              "fluxgate.resilience.circuit-breaker.fallback=FAIL_CLOSED")
          .run(
              context -> {
                CircuitBreakerConfig config = context.getBean(CircuitBreakerConfig.class);
                assertThat(config.getFailureThreshold()).isEqualTo(10);
                assertThat(config.getWaitDurationInOpenState().toSeconds()).isEqualTo(60);
                assertThat(config.getPermittedCallsInHalfOpenState()).isEqualTo(5);
                assertThat(config.getFallbackStrategy())
                    .isEqualTo(CircuitBreakerConfig.FallbackStrategy.FAIL_CLOSED);
              });
    }
  }

  @Nested
  @DisplayName("Resilient Executor")
  class ResilientExecutorTests {

    @Test
    @DisplayName("should create resilient executor with both components")
    void shouldCreateResilientExecutorWithBothComponents() {
      contextRunner
          .withPropertyValues(
              "fluxgate.resilience.retry.enabled=true",
              "fluxgate.resilience.circuit-breaker.enabled=true")
          .run(
              context -> {
                ResilientExecutor executor = context.getBean(ResilientExecutor.class);
                assertThat(executor.getRetryExecutor()).isInstanceOf(DefaultRetryExecutor.class);
                assertThat(executor.getCircuitBreaker()).isInstanceOf(DefaultCircuitBreaker.class);
              });
    }
  }

  @Nested
  @DisplayName("Properties")
  class PropertiesTests {

    @Test
    @DisplayName("should bind properties to FluxgateResilienceProperties")
    void shouldBindPropertiesToFluxgateResilienceProperties() {
      contextRunner
          .withPropertyValues(
              "fluxgate.resilience.retry.enabled=true",
              "fluxgate.resilience.retry.max-attempts=4",
              "fluxgate.resilience.circuit-breaker.enabled=true",
              "fluxgate.resilience.circuit-breaker.failure-threshold=7")
          .run(
              context -> {
                FluxgateResilienceProperties props =
                    context.getBean(FluxgateResilienceProperties.class);
                assertThat(props.getRetry().isEnabled()).isTrue();
                assertThat(props.getRetry().getMaxAttempts()).isEqualTo(4);
                assertThat(props.getCircuitBreaker().isEnabled()).isTrue();
                assertThat(props.getCircuitBreaker().getFailureThreshold()).isEqualTo(7);
              });
    }
  }
}
