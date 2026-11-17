package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import org.fluxgate.spring.handler.LazyRedisRateLimiter;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Every failed reconnect attempt of {@link LazyRedisRateLimiter} closes the connection it opened.
 *
 * <p>The Redis server accepts connections but has {@code SCRIPT} disabled, so each attempt gets as
 * far as connecting and then fails to load the Lua scripts - the case in which {@code
 * RedisRateLimiterConfig} used to leak its Lettuce client. The number of clients connected to the
 * server must stay flat however many attempts fail.
 */
@DisplayName("LazyRedisRateLimiter reconnects without leaking connections (Testcontainers Redis)")
class LazyRedisReconnectLeakIntegrationTest {

  private static GenericContainer<?> redis;
  private static RedisClient probeClient;
  private static StatefulRedisConnection<String, String> probe;

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(FluxgateProperties.class)
  static class TestConfig {}

  @BeforeAll
  static void startRedis() {
    boolean docker;
    try {
      docker = DockerClientFactory.instance().isDockerAvailable();
    } catch (RuntimeException | LinkageError e) {
      docker = false;
    }
    Assumptions.assumeTrue(docker, "Docker is required for the Redis reconnect test");
    redis =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--rename-command", "SCRIPT", "\"\"");
    redis.start();
    probeClient = RedisClient.create(uri());
    probe = probeClient.connect();
  }

  @AfterAll
  static void stopRedis() {
    if (probe != null) {
      probe.close();
    }
    if (probeClient != null) {
      probeClient.shutdown();
    }
    if (redis != null) {
      redis.stop();
    }
  }

  private static String uri() {
    return "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
  }

  /** Clients connected to the server, without the probe itself. */
  private static long otherClients() {
    return probe.sync().clientList().lines().filter(line -> !line.isEmpty()).count() - 1;
  }

  @Test
  void failedReconnectAttemptsCloseTheirConnections() throws Exception {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FluxgateRedisAutoConfiguration.class))
        .withUserConfiguration(TestConfig.class)
        .withPropertyValues(
            "fluxgate.redis.enabled=true",
            "fluxgate.redis.uri=" + uri(),
            "fluxgate.redis.timeout-ms=50")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              LazyRedisRateLimiter limiter = context.getBean(LazyRedisRateLimiter.class);

              long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
              while (limiter.getFailedAttempts() < 4 && System.nanoTime() < deadline) {
                Thread.sleep(20);
              }

              assertThat(limiter.isReady()).isFalse();
              assertThat(limiter.getFailedAttempts()).isGreaterThanOrEqualTo(4);
              assertThat(limiter.getLastErrorMessage()).containsIgnoringCase("script");
              // At most the attempt that may be running right now; a leak keeps one per attempt.
              assertThat(otherClients()).isLessThanOrEqualTo(1);
            });
  }
}
