package org.fluxgate.sample.standalone.java11;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.fluxgate.core.reload.RuleReloadStrategy;
import org.fluxgate.spring.reload.strategy.PollingReloadStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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
@SpringBootTest
@AutoConfigureMockMvc
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
    registry.add("spring.redis.host", REDIS::getHost);
    registry.add("spring.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("fluxgate.mongo.uri", StandaloneSampleStartupTest::mongoUri);
    registry.add("spring.data.mongodb.uri", StandaloneSampleStartupTest::mongoUri);
    registry.add("fluxgate.reload.pubsub.secret", () -> "");
    registry.add("fluxgate.reload.pubsub.allow-unsigned", () -> "false");
  }

  private static String mongoUri() {
    return "mongodb://" + MONGO.getHost() + ":" + MONGO.getMappedPort(27017) + "/fluxgate";
  }

  @Autowired private ApplicationContext context;

  @Autowired private MockMvc mockMvc;

  @Test
  void startsWithTheShippedConfiguration() throws Exception {
    // AUTO with Redis enabled but neither a secret nor allow-unsigned falls back to polling.
    assertThat(context.getBean(RuleReloadStrategy.class)).isInstanceOf(PollingReloadStrategy.class);
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  /**
   * {@code multiFilterApiFilter} enforces {@code multi-filter-rules} (10 req/min) on {@code
   * /api/test/multi-filter}. {@code standalone-rules} is never created here, so a request that fell
   * through to {@code apiFilter} would be denied at once ({@code missing-rule-behavior: DENY}).
   */
  @Test
  void multiFilterEndpointIsLimitedByMultiFilterRules() throws Exception {
    mockMvc.perform(post("/api/admin/rules/multi-filter")).andExpect(status().isOk());

    assertTenAllowedThenRejected("/api/test/multi-filter");
  }

  /**
   * {@code compositeKeyApiFilter} enforces {@code composite-key-rules} (10 req/min per IP+User) on
   * {@code /api/test/composite}.
   */
  @Test
  void compositeEndpointIsLimitedByCompositeKeyRules() throws Exception {
    mockMvc.perform(post("/api/admin/rules/composite")).andExpect(status().isOk());

    assertTenAllowedThenRejected("/api/test/composite?userId=alice");
  }

  private void assertTenAllowedThenRejected(String uri) throws Exception {
    for (int i = 0; i < 10; i++) {
      mockMvc.perform(get(uri)).andExpect(status().isOk());
    }
    mockMvc.perform(get(uri)).andExpect(status().isTooManyRequests());
  }
}
