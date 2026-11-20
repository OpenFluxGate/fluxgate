package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.spring.actuator.FluxgateHealthIndicator.HealthStatus;
import org.fluxgate.spring.actuator.FluxgateHealthIndicator.RedisHealthChecker;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * R6: the Redis health check reads the connection state; it never creates the lazy Redis
 * configuration, which would open a blocking connection on every probe (and, when Redis is down,
 * retry and leak one on every probe).
 */
class FluxgateRedisHealthCheckerTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(FluxgateProperties.class)
  static class TestConfig {}

  private final AtomicInteger creations = new AtomicInteger();

  /** A lazy RedisRateLimiterConfig whose creation is counted and always fails, like Redis down. */
  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FluxgateRedisAutoConfiguration.class))
        .withUserConfiguration(TestConfig.class)
        .withPropertyValues(
            "fluxgate.redis.enabled=true",
            // keep the Redis limiter (which connects in the background) out of this test
            "fluxgate.ratelimit.mode=IN_MEMORY")
        .withBean(
            "fluxgateRedisConfig",
            RedisRateLimiterConfig.class,
            () -> {
              creations.incrementAndGet();
              throw new IllegalStateException("Connection refused: 127.0.0.1:1");
            },
            definition -> definition.setLazyInit(true));
  }

  @Test
  void healthCheckDoesNotCreateTheRedisConfiguration() {
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              RedisHealthChecker checker = context.getBean(RedisHealthChecker.class);

              HealthStatus first = checker.check();
              HealthStatus second = checker.check();

              assertThat(first.isHealthy()).isFalse();
              assertThat(first.status()).isEqualTo("DOWN");
              assertThat(second.isHealthy()).isFalse();
              assertThat(creations).hasValue(0);
            });
  }

  @Test
  void healthCheckDoesNotRetryAFailedCreation() {
    runner()
        .run(
            context -> {
              // Something else (the limiter's reconnect, a fail-fast probe) tried and failed once.
              try {
                context.getBean(RedisRateLimiterConfig.class);
              } catch (RuntimeException expected) {
                // Redis is down
              }
              assertThat(creations).hasValue(1);

              context.getBean(RedisHealthChecker.class).check();
              context.getBean(RedisHealthChecker.class).check();

              assertThat(creations).hasValue(1);
            });
  }
}
