package org.fluxgate.sample.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.fluxgate.core.ratelimiter.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The sample starts with its shipped {@code application.yml}, as {@code ./mvnw spring-boot:run}
 * does: only the Redis address is pointed at a disposable container.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RedisSampleStartupTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  @DynamicPropertySource
  static void connections(DynamicPropertyRegistry registry) {
    registry.add(
        "fluxgate.redis.uri", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
  }

  @Autowired private ApplicationContext context;

  @Test
  void startsWithTheShippedConfiguration() {
    assertThat(context.getBeansOfType(RateLimiter.class)).isNotEmpty();
  }
}
