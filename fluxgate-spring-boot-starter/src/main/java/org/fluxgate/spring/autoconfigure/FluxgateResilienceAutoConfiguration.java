package org.fluxgate.spring.autoconfigure;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for FluxGate resilience features.
 *
 * <p>This configuration provides retry and circuit breaker beans based on the configured
 * properties.
 */
@AutoConfiguration
@EnableConfigurationProperties(FluxgateResilienceProperties.class)
public class FluxgateResilienceAutoConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(FluxgateResilienceAutoConfiguration.class);

  /**
   * Creates a RetryConfig from properties.
   *
   * @param properties the resilience properties
   * @return the retry configuration
   */
  @Bean
  @ConditionalOnMissingBean
  public RetryConfig fluxgateRetryConfig(FluxgateResilienceProperties properties) {
    FluxgateResilienceProperties.Retry retryProps = properties.getRetry();

    RetryConfig config =
        RetryConfig.builder()
            .enabled(retryProps.isEnabled())
            .maxAttempts(retryProps.getMaxAttempts())
            .initialBackoff(retryProps.getInitialBackoff())
            .multiplier(retryProps.getMultiplier())
            .maxBackoff(retryProps.getMaxBackoff())
            .build();

    log.info(
        "FluxGate retry configured: enabled={}, maxAttempts={}, initialBackoff={}ms",
        retryProps.isEnabled(),
        retryProps.getMaxAttempts(),
        retryProps.getInitialBackoff().toMillis());

    return config;
  }

  /**
   * Creates a CircuitBreakerConfig from properties.
   *
   * @param properties the resilience properties
   * @return the circuit breaker configuration
   */
  @Bean
  @ConditionalOnMissingBean
  public CircuitBreakerConfig fluxgateCircuitBreakerConfig(
      FluxgateResilienceProperties properties) {
    FluxgateResilienceProperties.CircuitBreaker cbProps = properties.getCircuitBreaker();

    CircuitBreakerConfig config =
        CircuitBreakerConfig.builder()
            .enabled(cbProps.isEnabled())
            .failureThreshold(cbProps.getFailureThreshold())
            .waitDurationInOpenState(cbProps.getWaitDurationInOpenState())
            .permittedCallsInHalfOpenState(cbProps.getPermittedCallsInHalfOpenState())
            .fallbackStrategy(cbProps.getFallback())
            .build();

    log.info(
        "FluxGate circuit breaker configured: enabled={}, failureThreshold={}, "
            + "waitDuration={}s, fallback={}",
        cbProps.isEnabled(),
        cbProps.getFailureThreshold(),
        cbProps.getWaitDurationInOpenState().toSeconds(),
        cbProps.getFallback());

    return config;
  }

  /**
   * Creates a RetryExecutor bean.
   *
   * @param config the retry configuration
   * @return the retry executor
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "fluxgate.resilience.retry",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public RetryExecutor fluxgateRetryExecutor(RetryConfig config) {
    return new DefaultRetryExecutor(config);
  }

  /**
   * Creates a no-op RetryExecutor when retry is disabled.
   *
   * @return the no-op retry executor
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "fluxgate.resilience.retry",
      name = "enabled",
      havingValue = "false")
  public RetryExecutor fluxgateNoOpRetryExecutor() {
    log.info("FluxGate retry is disabled");
    return NoOpRetryExecutor.getInstance();
  }

  /**
   * Creates a CircuitBreaker bean when enabled.
   *
   * @param config the circuit breaker configuration
   * @return the circuit breaker
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "fluxgate.resilience.circuit-breaker",
      name = "enabled",
      havingValue = "true")
  public CircuitBreaker fluxgateCircuitBreaker(CircuitBreakerConfig config) {
    return new DefaultCircuitBreaker("fluxgate", config);
  }

  /**
   * Creates a no-op CircuitBreaker when disabled.
   *
   * @return the no-op circuit breaker
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "fluxgate.resilience.circuit-breaker",
      name = "enabled",
      havingValue = "false",
      matchIfMissing = true)
  public CircuitBreaker fluxgateNoOpCircuitBreaker() {
    log.debug("FluxGate circuit breaker is disabled");
    return NoOpCircuitBreaker.getInstance();
  }

  /**
   * Creates a ResilientExecutor that combines retry and circuit breaker.
   *
   * @param retryExecutor the retry executor
   * @param circuitBreaker the circuit breaker
   * @return the resilient executor
   */
  @Bean
  @ConditionalOnMissingBean
  public ResilientExecutor fluxgateResilientExecutor(
      RetryExecutor retryExecutor, CircuitBreaker circuitBreaker) {
    return new ResilientExecutor(retryExecutor, circuitBreaker);
  }
}
