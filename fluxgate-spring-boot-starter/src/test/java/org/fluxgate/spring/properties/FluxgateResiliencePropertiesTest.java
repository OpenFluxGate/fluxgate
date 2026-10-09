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
package org.fluxgate.spring.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.fluxgate.core.resilience.CircuitBreakerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FluxgateResilienceProperties")
class FluxgateResiliencePropertiesTest {

  @Nested
  @DisplayName("Retry Properties")
  class RetryPropertiesTests {

    @Test
    @DisplayName("should have default values")
    void shouldHaveDefaultValues() {
      FluxgateResilienceProperties props = new FluxgateResilienceProperties();
      FluxgateResilienceProperties.Retry retry = props.getRetry();

      assertThat(retry.isEnabled()).isTrue();
      assertThat(retry.getMaxAttempts()).isEqualTo(3);
      assertThat(retry.getInitialBackoff()).isEqualTo(Duration.ofMillis(100));
      assertThat(retry.getMultiplier()).isEqualTo(2.0);
      assertThat(retry.getMaxBackoff()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("should allow setting values")
    void shouldAllowSettingValues() {
      FluxgateResilienceProperties props = new FluxgateResilienceProperties();
      FluxgateResilienceProperties.Retry retry = props.getRetry();

      retry.setEnabled(false);
      retry.setMaxAttempts(5);
      retry.setInitialBackoff(Duration.ofMillis(200));
      retry.setMultiplier(3.0);
      retry.setMaxBackoff(Duration.ofSeconds(10));

      assertThat(retry.isEnabled()).isFalse();
      assertThat(retry.getMaxAttempts()).isEqualTo(5);
      assertThat(retry.getInitialBackoff()).isEqualTo(Duration.ofMillis(200));
      assertThat(retry.getMultiplier()).isEqualTo(3.0);
      assertThat(retry.getMaxBackoff()).isEqualTo(Duration.ofSeconds(10));
    }
  }

  @Nested
  @DisplayName("Circuit Breaker Properties")
  class CircuitBreakerPropertiesTests {

    @Test
    @DisplayName("should have default values")
    void shouldHaveDefaultValues() {
      FluxgateResilienceProperties props = new FluxgateResilienceProperties();
      FluxgateResilienceProperties.CircuitBreaker cb = props.getCircuitBreaker();

      assertThat(cb.isEnabled()).isFalse();
      assertThat(cb.getFailureThreshold()).isEqualTo(5);
      assertThat(cb.getWaitDurationInOpenState()).isEqualTo(Duration.ofSeconds(30));
      assertThat(cb.getPermittedCallsInHalfOpenState()).isEqualTo(3);
      assertThat(cb.getFallback()).isEqualTo(CircuitBreakerConfig.FallbackStrategy.FAIL_OPEN);
    }

    @Test
    @DisplayName("should allow setting values")
    void shouldAllowSettingValues() {
      FluxgateResilienceProperties props = new FluxgateResilienceProperties();
      FluxgateResilienceProperties.CircuitBreaker cb = props.getCircuitBreaker();

      cb.setEnabled(true);
      cb.setFailureThreshold(10);
      cb.setWaitDurationInOpenState(Duration.ofMinutes(1));
      cb.setPermittedCallsInHalfOpenState(5);
      cb.setFallback(CircuitBreakerConfig.FallbackStrategy.FAIL_CLOSED);

      assertThat(cb.isEnabled()).isTrue();
      assertThat(cb.getFailureThreshold()).isEqualTo(10);
      assertThat(cb.getWaitDurationInOpenState()).isEqualTo(Duration.ofMinutes(1));
      assertThat(cb.getPermittedCallsInHalfOpenState()).isEqualTo(5);
      assertThat(cb.getFallback()).isEqualTo(CircuitBreakerConfig.FallbackStrategy.FAIL_CLOSED);
    }
  }
}
