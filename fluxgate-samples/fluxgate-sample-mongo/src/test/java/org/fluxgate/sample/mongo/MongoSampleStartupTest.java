package org.fluxgate.sample.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The sample starts with its shipped {@code application.yml}, as {@code ./mvnw spring-boot:run}
 * does: only the MongoDB address is pointed at a disposable container.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MongoSampleStartupTest {

  @Container
  static final GenericContainer<?> MONGO =
      new GenericContainer<>(DockerImageName.parse("mongo:7.0"))
          .withExposedPorts(27017)
          .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
          .withStartupTimeout(Duration.ofSeconds(120));

  @DynamicPropertySource
  static void connections(DynamicPropertyRegistry registry) {
    registry.add(
        "fluxgate.mongo.uri",
        () -> "mongodb://" + MONGO.getHost() + ":" + MONGO.getMappedPort(27017) + "/fluxgate");
  }

  @Autowired private ApplicationContext context;

  @Test
  void startsWithTheShippedConfiguration() {
    assertThat(context.getBeansOfType(RateLimitRuleRepository.class)).isNotEmpty();
  }
}
