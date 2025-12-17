package org.fluxgate.spring.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import org.fluxgate.spring.actuator.FluxgateHealthIndicator.HealthStatus;
import org.fluxgate.spring.actuator.FluxgateHealthIndicator.MongoHealthChecker;
import org.fluxgate.spring.actuator.FluxgateHealthIndicator.RedisHealthChecker;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/** Unit tests for {@link FluxgateHealthIndicator}. */
class FluxgateHealthIndicatorTest {

  private FluxgateProperties properties;

  @BeforeEach
  void setUp() {
    properties = new FluxgateProperties();
  }

  @Nested
  class WhenAllDisabled {

    @Test
    void shouldReturnUpStatus() {
      // given
      FluxgateHealthIndicator indicator = new FluxgateHealthIndicator(properties, null, null);

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getStatus()).isEqualTo(Status.UP);
      assertThat(health.getDetails().get("mongo.status")).isEqualTo("DISABLED");
      assertThat(health.getDetails().get("redis.status")).isEqualTo("DISABLED");
    }

    @Test
    void shouldIncludeRateLimitingStatus() {
      // given
      properties.getRatelimit().setEnabled(true);
      properties.getRatelimit().setFilterEnabled(true);
      FluxgateHealthIndicator indicator = new FluxgateHealthIndicator(properties, null, null);

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getDetails().get("rateLimitingEnabled")).isEqualTo(true);
      assertThat(health.getDetails().get("filterEnabled")).isEqualTo(true);
    }
  }

  @Nested
  class WhenMongoEnabled {

    @BeforeEach
    void setUp() {
      properties.getMongo().setEnabled(true);
    }

    @Test
    void shouldReturnUpWhenMongoHealthy() {
      // given
      MongoHealthChecker checker = () -> HealthStatus.up("Connected");
      FluxgateHealthIndicator indicator = new FluxgateHealthIndicator(properties, checker, null);

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getStatus()).isEqualTo(Status.UP);
      assertThat(health.getDetails().get("mongo.status")).isEqualTo("UP");
      assertThat(health.getDetails().get("mongo.message")).isEqualTo("Connected");
    }

    @Test
    void shouldReturnDegradedWhenMongoDown() {
      // given
      MongoHealthChecker checker = () -> HealthStatus.down("Connection refused");
      FluxgateHealthIndicator indicator = new FluxgateHealthIndicator(properties, checker, null);

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
      assertThat(health.getDetails().get("mongo.status")).isEqualTo("DOWN");
    }

    @Test
    void shouldReturnDegradedWhenMongoCheckerThrows() {
      // given
      MongoHealthChecker checker =
          () -> {
            throw new RuntimeException("Connection timeout");
          };
      FluxgateHealthIndicator indicator = new FluxgateHealthIndicator(properties, checker, null);

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
      assertThat(health.getDetails().get("mongo.status")).isEqualTo("ERROR");
      assertThat(health.getDetails().get("mongo.message")).isEqualTo("Connection timeout");
    }

    @Test
    void shouldHandleNullMongoChecker() {
      // given
      FluxgateHealthIndicator indicator = new FluxgateHealthIndicator(properties, null, null);

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
      assertThat(health.getDetails().get("mongo.status")).isEqualTo("UNKNOWN");
    }
  }

  @Nested
  class WhenRedisEnabled {

    @BeforeEach
    void setUp() {
      properties.getRedis().setEnabled(true);
    }

    @Test
    void shouldReturnUpWhenRedisHealthy() {
      // given
      RedisHealthChecker checker = () -> HealthStatus.up("PONG");
      FluxgateHealthIndicator indicator = new FluxgateHealthIndicator(properties, null, checker);

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getStatus()).isEqualTo(Status.UP);
      assertThat(health.getDetails().get("redis.status")).isEqualTo("UP");
      assertThat(health.getDetails().get("redis.message")).isEqualTo("PONG");
    }

    @Test
    void shouldReturnDegradedWhenRedisDown() {
      // given
      RedisHealthChecker checker = () -> HealthStatus.down("Connection refused");
      FluxgateHealthIndicator indicator = new FluxgateHealthIndicator(properties, null, checker);

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
      assertThat(health.getDetails().get("redis.status")).isEqualTo("DOWN");
    }

    @Test
    void shouldReturnDegradedWhenRedisCheckerThrows() {
      // given
      RedisHealthChecker checker =
          () -> {
            throw new RuntimeException("Redis error");
          };
      FluxgateHealthIndicator indicator = new FluxgateHealthIndicator(properties, null, checker);

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
      assertThat(health.getDetails().get("redis.status")).isEqualTo("ERROR");
    }

    @Test
    void shouldHandleNullRedisChecker() {
      // given
      FluxgateHealthIndicator indicator = new FluxgateHealthIndicator(properties, null, null);

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
      assertThat(health.getDetails().get("redis.status")).isEqualTo("UNKNOWN");
    }
  }

  @Nested
  class WhenBothEnabled {

    @BeforeEach
    void setUp() {
      properties.getMongo().setEnabled(true);
      properties.getRedis().setEnabled(true);
    }

    @Test
    void shouldReturnUpWhenBothHealthy() {
      // given
      MongoHealthChecker mongoChecker = () -> HealthStatus.up("Mongo OK");
      RedisHealthChecker redisChecker = () -> HealthStatus.up("Redis OK");
      FluxgateHealthIndicator indicator =
          new FluxgateHealthIndicator(properties, mongoChecker, redisChecker);

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void shouldReturnDegradedWhenOneDown() {
      // given
      MongoHealthChecker mongoChecker = () -> HealthStatus.up("Mongo OK");
      RedisHealthChecker redisChecker = () -> HealthStatus.down("Redis down");
      FluxgateHealthIndicator indicator =
          new FluxgateHealthIndicator(properties, mongoChecker, redisChecker);

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
    }
  }

  @Nested
  class HealthStatusTests {

    @Test
    void shouldCreateUpStatus() {
      HealthStatus status = HealthStatus.up("All good");

      assertThat(status.status()).isEqualTo("UP");
      assertThat(status.message()).isEqualTo("All good");
      assertThat(status.isHealthy()).isTrue();
    }

    @Test
    void shouldCreateDownStatus() {
      HealthStatus status = HealthStatus.down("Connection failed");

      assertThat(status.status()).isEqualTo("DOWN");
      assertThat(status.message()).isEqualTo("Connection failed");
      assertThat(status.isHealthy()).isFalse();
    }

    @Test
    void shouldCreateUnknownStatus() {
      HealthStatus status = HealthStatus.unknown("Not configured");

      assertThat(status.status()).isEqualTo("UNKNOWN");
      assertThat(status.message()).isEqualTo("Not configured");
      assertThat(status.isHealthy()).isFalse();
    }
  }
}
