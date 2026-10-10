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
      assertThat(retry.getJitterFactor()).isEqualTo(0.2);
      assertThat(retry.isRetryOnTimeout()).isFalse();
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
      retry.setJitterFactor(0.5);
      retry.setRetryOnTimeout(true);

      assertThat(retry.isEnabled()).isFalse();
      assertThat(retry.getMaxAttempts()).isEqualTo(5);
      assertThat(retry.getInitialBackoff()).isEqualTo(Duration.ofMillis(200));
      assertThat(retry.getMultiplier()).isEqualTo(3.0);
      assertThat(retry.getMaxBackoff()).isEqualTo(Duration.ofSeconds(10));
      assertThat(retry.getJitterFactor()).isEqualTo(0.5);
      assertThat(retry.isRetryOnTimeout()).isTrue();
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

      // Enabled by default: retry is too, so without a breaker a failing backend was called
      // max-attempts times per request with nothing to stop the amplification.
      assertThat(cb.isEnabled()).isTrue();
      // Unset by default so the sliding-window failure rate rule stays in charge.
      assertThat(cb.getFailureThreshold()).isNull();
      assertThat(cb.getSlidingWindowSize()).isEqualTo(20);
      assertThat(cb.getFailureRateThreshold()).isEqualTo(50);
      assertThat(cb.getMinimumNumberOfCalls()).isEqualTo(10);
      assertThat(cb.getWaitDurationInOpenState()).isEqualTo(Duration.ofSeconds(30));
      assertThat(cb.getPermittedCallsInHalfOpenState()).isEqualTo(3);
      assertThat(cb.getFallback()).isEqualTo(CircuitBreakerConfig.FallbackStrategy.FAIL_OPEN);
    }

    @Test
    @DisplayName("should allow setting values")
    void shouldAllowSettingValues() {
      FluxgateResilienceProperties props = new FluxgateResilienceProperties();
      FluxgateResilienceProperties.CircuitBreaker cb = props.getCircuitBreaker();

      cb.setEnabled(false);
      cb.setFailureThreshold(10);
      cb.setSlidingWindowSize(50);
      cb.setFailureRateThreshold(75);
      cb.setMinimumNumberOfCalls(20);
      cb.setWaitDurationInOpenState(Duration.ofMinutes(1));
      cb.setPermittedCallsInHalfOpenState(5);
      cb.setFallback(CircuitBreakerConfig.FallbackStrategy.FAIL_CLOSED);

      assertThat(cb.isEnabled()).isFalse();
      assertThat(cb.getFailureThreshold()).isEqualTo(10);
      assertThat(cb.getSlidingWindowSize()).isEqualTo(50);
      assertThat(cb.getFailureRateThreshold()).isEqualTo(75);
      assertThat(cb.getMinimumNumberOfCalls()).isEqualTo(20);
      assertThat(cb.getWaitDurationInOpenState()).isEqualTo(Duration.ofMinutes(1));
      assertThat(cb.getPermittedCallsInHalfOpenState()).isEqualTo(5);
      assertThat(cb.getFallback()).isEqualTo(CircuitBreakerConfig.FallbackStrategy.FAIL_CLOSED);
    }
  }
}
