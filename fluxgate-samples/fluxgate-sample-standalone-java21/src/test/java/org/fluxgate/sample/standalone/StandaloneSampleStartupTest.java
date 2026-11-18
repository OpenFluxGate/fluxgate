package org.fluxgate.sample.standalone;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.fluxgate.core.reload.RuleReloadStrategy;
import org.fluxgate.spring.reload.strategy.PollingReloadStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The sample starts with its shipped {@code application.yml}, as {@code ./mvnw spring-boot:run}
 * does: only the Redis and MongoDB addresses are pointed at disposable containers. No reload secret
 * is set, so the documented quick start must not require one. The secret and {@code allow-unsigned}
 * are pinned so a {@code FLUXGATE_RELOAD_*} variable in the environment cannot change which reload
 * strategy is tested.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "server.address=127.0.0.1")
@Testcontainers(disabledWithoutDocker = true)
class StandaloneSampleStartupTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  @Container
  static final GenericContainer<?> MONGO =
      new GenericContainer<>(DockerImageName.parse("mongo:7.0"))
          .withExposedPorts(27017)
          .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
          .withStartupTimeout(Duration.ofSeconds(120));

  @DynamicPropertySource
  static void connections(DynamicPropertyRegistry registry) {
    registry.add(
        "fluxgate.redis.uri", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("fluxgate.mongo.uri", StandaloneSampleStartupTest::mongoUri);
    registry.add("spring.data.mongodb.uri", StandaloneSampleStartupTest::mongoUri);
    registry.add("fluxgate.reload.pubsub.secret", () -> "");
    registry.add("fluxgate.reload.pubsub.allow-unsigned", () -> "false");
  }

  private static String mongoUri() {
    return "mongodb://" + MONGO.getHost() + ":" + MONGO.getMappedPort(27017) + "/fluxgate";
  }

  @Autowired private ApplicationContext context;

  @Autowired private TestRestTemplate http;

  @Test
  void startsWithTheShippedConfiguration() {
    // AUTO with Redis enabled but neither a secret nor allow-unsigned falls back to polling.
    assertThat(context.getBean(RuleReloadStrategy.class)).isInstanceOf(PollingReloadStrategy.class);
    assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  void generatesApiDocumentationWithTheShippedControllerAdvice() {
    ResponseEntity<JsonNode> response = http.getForEntity("/v3/api-docs", JsonNode.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().path("openapi").asText()).isNotEmpty();
    assertThat(response.getBody().path("paths").size()).isPositive();
  }

  @Test
  void multiFilterEndpointIsLimitedByMultiFilterRules() {
    assertThat(
            http.postForEntity("/api/admin/rules/multi-filter", null, String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    String uri = "/api/test/multi-filter";
    for (int i = 0; i < 10; i++) {
      assertThat(http.getForEntity(uri, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    assertThat(http.getForEntity(uri, String.class).getStatusCode())
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }
}
