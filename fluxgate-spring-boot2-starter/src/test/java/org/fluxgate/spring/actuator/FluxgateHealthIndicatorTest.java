package org.fluxgate.spring.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import org.fluxgate.spring.actuator.FluxgateHealthIndicator.HealthStatus;
import org.fluxgate.spring.actuator.FluxgateHealthIndicator.MongoHealthChecker;
import org.fluxgate.spring.actuator.FluxgateHealthIndicator.RedisHealthChecker;
import org.fluxgate.spring.handler.RedisConnectionState;
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
      assertThat(health.getDetails().get("dependencyIssues")).isEqualTo(false);
    }

    @Test
    void shouldIncludeRateLimitingAndSecurityStatus() {
      // given - filterEnabled now reflects the actual bean, not this legacy flag
      properties.getRatelimit().setEnabled(true);
      FluxgateHealthIndicator indicator =
          new FluxgateHealthIndicator(properties, null, null, () -> true, () -> false);

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getDetails().get("rateLimitingEnabled")).isEqualTo(true);
      assertThat(health.getDetails().get("filterEnabled")).isEqualTo(true);
      assertThat(health.getDetails().get("aspectEnabled")).isEqualTo(false);
      assertThat(health.getDetails().get("failureBehavior")).isEqualTo("DENY");
      assertThat(health.getDetails().get("missingRuleBehavior")).isEqualTo("DENY");
      assertThat(health.getDetails().get("trustClientIpHeader")).isEqualTo(false);
      assertThat(health.getDetails().get("defaultRuleSetIdConfigured")).isEqualTo(false);
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
      // N-6b: the message names the Mongo host:port, so it is log-only unless opted in
      assertThat(health.getDetails()).doesNotContainKey("mongo.message");
    }

    @Test
    void shouldIncludeMongoDetailsWhenEndpointDetailsAreEnabled() {
      // given - N-6b: an operator can opt back in behind show-details=when_authorized
      properties.getActuator().getHealth().setIncludeEndpointDetails(true);
      MongoHealthChecker checker =
          () -> HealthStatus.up("Connected", java.util.Map.of("host", "mongo:27017"));
      FluxgateHealthIndicator indicator = new FluxgateHealthIndicator(properties, checker, null);

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getDetails().get("mongo.message")).isEqualTo("Connected");
      assertThat(health.getDetails().get("mongo.host")).isEqualTo("mongo:27017");
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
      assertThat(health.getDetails().get("mongo.error")).isEqualTo("RuntimeException");
      // N-6b: the exception type classifies the failure, the message stays in the log
      assertThat(health.getDetails()).doesNotContainKey("mongo.message");
      assertThat(health.getDetails().get("dependencyIssues")).isEqualTo(true);
      assertThat(health.getDetails().get("statusReason"))
          .isEqualTo("One or more enabled FluxGate dependencies are unhealthy");
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
      // N-6b: the message names the Redis host:port, so it is log-only unless opted in
      assertThat(health.getDetails()).doesNotContainKey("redis.message");
    }

    @Test
    void shouldIncludeRedisDetailsWhenEndpointDetailsAreEnabled() {
      // given - N-6b
      properties.getActuator().getHealth().setIncludeEndpointDetails(true);
      RedisHealthChecker checker =
          () -> HealthStatus.up("PONG", java.util.Map.of("host", "redis:6379"));
      FluxgateHealthIndicator indicator = new FluxgateHealthIndicator(properties, null, checker);

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getDetails().get("redis.message")).isEqualTo("PONG");
      assertThat(health.getDetails().get("redis.host")).isEqualTo("redis:6379");
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
      assertThat(health.getDetails().get("redis.error")).isEqualTo("RuntimeException");
      // N-6b: the exception type classifies the failure, the message stays in the log
      assertThat(health.getDetails()).doesNotContainKey("redis.message");
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

  @Nested
  class ComponentPresenceReporting {

    @Test
    void shouldReportNoFilterOrAspectWhenNonePresent() {
      // given - H15: the flag used to be echoed from a property that controlled nothing
      properties.getRatelimit().setFilterEnabled(true);
      FluxgateHealthIndicator indicator = new FluxgateHealthIndicator(properties, null, null);

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getDetails().get("filterEnabled")).isEqualTo(false);
      assertThat(health.getDetails().get("aspectEnabled")).isEqualTo(false);
    }

    @Test
    void shouldReportBothWhenPresent() {
      // given
      FluxgateHealthIndicator indicator =
          new FluxgateHealthIndicator(properties, null, null, () -> true, () -> true);

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getDetails().get("filterEnabled")).isEqualTo(true);
      assertThat(health.getDetails().get("aspectEnabled")).isEqualTo(true);
    }

    @Test
    void shouldNotFailWhenAProbeThrows() {
      // given - a missing optional dependency must not take the health endpoint down
      FluxgateHealthIndicator indicator =
          new FluxgateHealthIndicator(
              properties,
              null,
              null,
              () -> {
                throw new IllegalStateException("bean lookup failed");
              },
              () -> false);

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getStatus()).isEqualTo(Status.UP);
      assertThat(health.getDetails().get("filterEnabled")).isEqualTo(false);
    }
  }

  @Nested
  class RateLimiterReadinessReporting {

    @Test
    void shouldReportNothingWhenTheLimiterIsNotRedisBacked() {
      // given - an in-memory limiter exposes no RedisConnectionState bean
      FluxgateHealthIndicator indicator = new FluxgateHealthIndicator(properties, null, null);

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getStatus()).isEqualTo(Status.UP);
      assertThat(health.getDetails()).doesNotContainKey("rateLimiter.ready");
    }

    @Test
    void shouldReportUpWhenTheLimiterIsReady() {
      // given
      FluxgateHealthIndicator indicator = indicatorWith(state(true, null, 0));

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getStatus()).isEqualTo(Status.UP);
      assertThat(health.getDetails().get("rateLimiter.ready")).isEqualTo(true);
      assertThat(health.getDetails()).doesNotContainKey("rateLimiter.lastError");
    }

    @Test
    void shouldReportDegradedWhileTheLimiterIsStillConnecting() {
      // given - M25: Redis being down at startup no longer fails the boot, so the endpoint must not
      // claim UP while every request is falling back
      FluxgateHealthIndicator indicator =
          indicatorWith(state(false, "Connection refused: localhost/127.0.0.1:6379", 3));

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getStatus()).isEqualTo(new Status("DEGRADED"));
      assertThat(health.getDetails().get("rateLimiter.ready")).isEqualTo(false);
      assertThat(health.getDetails().get("rateLimiter.failedAttempts")).isEqualTo(3);
      // N-6b: the failure message names the Redis host:port, so it stays in the log
      assertThat(health.getDetails()).doesNotContainKey("rateLimiter.lastError");
      assertThat(health.getDetails().get("dependencyIssues")).isEqualTo(true);
    }

    @Test
    void shouldExposeTheLastErrorWhenEndpointDetailsAreEnabled() {
      // given - N-6b
      properties.getActuator().getHealth().setIncludeEndpointDetails(true);
      FluxgateHealthIndicator indicator =
          indicatorWith(state(false, "Connection refused: localhost/127.0.0.1:6379", 3));

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getDetails().get("rateLimiter.lastError"))
          .isEqualTo("Connection refused: localhost/127.0.0.1:6379");
    }

    @Test
    void shouldOmitTheErrorDetailWhenThereIsNoMessage() {
      // given
      FluxgateHealthIndicator indicator = indicatorWith(state(false, null, 1));

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getStatus()).isEqualTo(new Status("DEGRADED"));
      assertThat(health.getDetails()).doesNotContainKey("rateLimiter.lastError");
    }

    @Test
    void shouldDegradeRatherThanFailWhenTheStateProbeThrows() {
      // given
      FluxgateHealthIndicator indicator =
          indicatorWith(
              new RedisConnectionState() {
                @Override
                public boolean isReady() {
                  throw new IllegalStateException("limiter not initialized");
                }

                @Override
                public String getLastErrorMessage() {
                  return null;
                }

                @Override
                public int getFailedAttempts() {
                  return 0;
                }
              });

      // when
      Health health = indicator.health();

      // then
      assertThat(health.getStatus()).isEqualTo(new Status("DEGRADED"));
      assertThat(health.getDetails().get("rateLimiter.ready")).isEqualTo(false);
      assertThat(health.getDetails().get("rateLimiter.error")).isEqualTo("IllegalStateException");
    }

    private FluxgateHealthIndicator indicatorWith(RedisConnectionState state) {
      return new FluxgateHealthIndicator(properties, null, null, () -> true, () -> false, state);
    }

    private RedisConnectionState state(boolean ready, String lastError, int failedAttempts) {
      return new RedisConnectionState() {
        @Override
        public boolean isReady() {
          return ready;
        }

        @Override
        public String getLastErrorMessage() {
          return lastError;
        }

        @Override
        public int getFailedAttempts() {
          return failedAttempts;
        }
      };
    }
  }
}
