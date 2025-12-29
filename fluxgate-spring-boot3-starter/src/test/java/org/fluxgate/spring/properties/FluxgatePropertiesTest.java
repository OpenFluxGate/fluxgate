package org.fluxgate.spring.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/** Tests for {@link FluxgateProperties} configuration property binding. */
class FluxgatePropertiesTest {

  @Test
  void shouldHaveDefaultValues() {
    FluxgateProperties properties = new FluxgateProperties();

    // Mongo defaults
    assertThat(properties.getMongo().isEnabled()).isFalse();
    assertThat(properties.getMongo().getUri()).isNull(); // URI is null by default, use getEffectiveUri()
    assertThat(properties.getMongo().getEffectiveUri()).isEqualTo("mongodb://localhost:27017/fluxgate");
    assertThat(properties.getMongo().getDatabase()).isEqualTo("fluxgate");
    assertThat(properties.getMongo().getRuleCollection()).isEqualTo("rate_limit_rules");
    assertThat(properties.getMongo().getEventCollection()).isNull(); // Optional, null by default
    assertThat(properties.getMongo().hasEventCollection()).isFalse();
    assertThat(properties.getMongo().getDdlAuto())
        .isEqualTo(FluxgateProperties.DdlAuto.VALIDATE); // Default is VALIDATE

    // Redis defaults
    assertThat(properties.getRedis().isEnabled()).isFalse();
    assertThat(properties.getRedis().getUri()).isNull(); // URI is null by default, use getEffectiveUri()
    assertThat(properties.getRedis().getEffectiveUri()).isEqualTo("redis://localhost:6379");

    // RateLimit defaults
    assertThat(properties.getRatelimit().isEnabled()).isTrue();
    assertThat(properties.getRatelimit().isFilterEnabled()).isFalse();
    assertThat(properties.getRatelimit().getDefaultRuleSetId()).isNull();
    assertThat(properties.getRatelimit().getFilterOrder()).isEqualTo(Integer.MIN_VALUE + 100);
    assertThat(properties.getRatelimit().getIncludePatterns()).containsExactly("/*");
    assertThat(properties.getRatelimit().getExcludePatterns()).isEmpty();
    assertThat(properties.getRatelimit().getClientIpHeader()).isEqualTo("X-Forwarded-For");
    assertThat(properties.getRatelimit().isTrustClientIpHeader()).isTrue();
    assertThat(properties.getRatelimit().isIncludeHeaders()).isTrue();
  }

  @Test
  void shouldSetMongoProperties() {
    FluxgateProperties properties = new FluxgateProperties();
    FluxgateProperties.MongoProperties mongo = properties.getMongo();

    mongo.setEnabled(true);
    mongo.setUri("mongodb://user:pass@mongo.internal:27017/mydb");
    mongo.setDatabase("mydb");
    mongo.setRuleCollection("custom_rules");
    mongo.setEventCollection("custom_events");

    assertThat(mongo.isEnabled()).isTrue();
    assertThat(mongo.getUri()).isEqualTo("mongodb://user:pass@mongo.internal:27017/mydb");
    assertThat(mongo.getDatabase()).isEqualTo("mydb");
    assertThat(mongo.getRuleCollection()).isEqualTo("custom_rules");
    assertThat(mongo.getEventCollection()).isEqualTo("custom_events");
  }

  @Test
  void shouldSetRedisProperties() {
    FluxgateProperties properties = new FluxgateProperties();
    FluxgateProperties.RedisProperties redis = properties.getRedis();

    redis.setEnabled(true);
    redis.setUri("redis://redis.internal:6379");
    redis.setMode("standalone");
    redis.setTimeoutMs(10000);

    assertThat(redis.isEnabled()).isTrue();
    assertThat(redis.getUri()).isEqualTo("redis://redis.internal:6379");
    assertThat(redis.getMode()).isEqualTo("standalone");
    assertThat(redis.getTimeoutMs()).isEqualTo(10000);
  }

  @Test
  void redisProperties_getEffectiveMode_shouldReturnClusterWhenModeIsCluster() {
    FluxgateProperties.RedisProperties redis = new FluxgateProperties.RedisProperties();
    redis.setMode("cluster");
    assertThat(redis.getEffectiveMode()).isEqualTo("cluster");
    assertThat(redis.isClusterMode()).isTrue();
  }

  @Test
  void redisProperties_getEffectiveMode_shouldReturnClusterWhenModeIsClusterUppercase() {
    FluxgateProperties.RedisProperties redis = new FluxgateProperties.RedisProperties();
    redis.setMode("CLUSTER");
    assertThat(redis.getEffectiveMode()).isEqualTo("cluster");
    assertThat(redis.isClusterMode()).isTrue();
  }

  @Test
  void redisProperties_getEffectiveMode_shouldReturnStandaloneWhenModeIsStandalone() {
    FluxgateProperties.RedisProperties redis = new FluxgateProperties.RedisProperties();
    redis.setMode("standalone");
    assertThat(redis.getEffectiveMode()).isEqualTo("standalone");
    assertThat(redis.isClusterMode()).isFalse();
  }

  @Test
  void redisProperties_getEffectiveMode_shouldReturnStandaloneWhenModeIsStandaloneUppercase() {
    FluxgateProperties.RedisProperties redis = new FluxgateProperties.RedisProperties();
    redis.setMode("STANDALONE");
    assertThat(redis.getEffectiveMode()).isEqualTo("standalone");
    assertThat(redis.isClusterMode()).isFalse();
  }

  @Test
  void redisProperties_getEffectiveMode_shouldAutoDetectClusterFromCommaUri() {
    FluxgateProperties.RedisProperties redis = new FluxgateProperties.RedisProperties();
    redis.setMode("auto");
    redis.setUri("redis://node1:6379,redis://node2:6379,redis://node3:6379");
    assertThat(redis.getEffectiveMode()).isEqualTo("cluster");
    assertThat(redis.isClusterMode()).isTrue();
  }

  @Test
  void redisProperties_getEffectiveMode_shouldAutoDetectStandaloneFromSingleUri() {
    FluxgateProperties.RedisProperties redis = new FluxgateProperties.RedisProperties();
    redis.setMode("auto");
    redis.setUri("redis://localhost:6379");
    assertThat(redis.getEffectiveMode()).isEqualTo("standalone");
    assertThat(redis.isClusterMode()).isFalse();
  }

  @Test
  void redisProperties_getEffectiveMode_shouldReturnStandaloneWhenUriIsNull() {
    FluxgateProperties.RedisProperties redis = new FluxgateProperties.RedisProperties();
    redis.setMode("auto");
    redis.setUri(null);
    assertThat(redis.getEffectiveMode()).isEqualTo("standalone");
    assertThat(redis.isClusterMode()).isFalse();
  }

  @Test
  void shouldSetRateLimitProperties() {
    FluxgateProperties properties = new FluxgateProperties();
    FluxgateProperties.RateLimitProperties rateLimit = properties.getRatelimit();

    rateLimit.setEnabled(false);
    rateLimit.setFilterEnabled(true);
    rateLimit.setDefaultRuleSetId("my-rules");
    rateLimit.setFilterOrder(-100);
    rateLimit.setIncludePatterns(new String[] {"/api/*", "/v1/*"});
    rateLimit.setExcludePatterns(new String[] {"/health", "/actuator/*"});
    rateLimit.setClientIpHeader("X-Real-IP");
    rateLimit.setTrustClientIpHeader(false);
    rateLimit.setIncludeHeaders(false);

    assertThat(rateLimit.isEnabled()).isFalse();
    assertThat(rateLimit.isFilterEnabled()).isTrue();
    assertThat(rateLimit.getDefaultRuleSetId()).isEqualTo("my-rules");
    assertThat(rateLimit.getFilterOrder()).isEqualTo(-100);
    assertThat(rateLimit.getIncludePatterns()).containsExactly("/api/*", "/v1/*");
    assertThat(rateLimit.getExcludePatterns()).containsExactly("/health", "/actuator/*");
    assertThat(rateLimit.getClientIpHeader()).isEqualTo("X-Real-IP");
    assertThat(rateLimit.isTrustClientIpHeader()).isFalse();
    assertThat(rateLimit.isIncludeHeaders()).isFalse();
  }

  @Test
  void shouldSetWaitForRefillProperties() {
    FluxgateProperties properties = new FluxgateProperties();
    FluxgateProperties.RateLimitProperties rateLimit = properties.getRatelimit();
    FluxgateProperties.WaitForRefillProperties waitForRefill = rateLimit.getWaitForRefill();

    // Default values
    assertThat(waitForRefill.isEnabled()).isFalse();
    assertThat(waitForRefill.getMaxWaitTimeMs()).isEqualTo(5000);
    assertThat(waitForRefill.getMaxConcurrentWaits()).isEqualTo(100);

    // Set custom values
    waitForRefill.setEnabled(true);
    waitForRefill.setMaxWaitTimeMs(10000);
    waitForRefill.setMaxConcurrentWaits(50);

    assertThat(waitForRefill.isEnabled()).isTrue();
    assertThat(waitForRefill.getMaxWaitTimeMs()).isEqualTo(10000);
    assertThat(waitForRefill.getMaxConcurrentWaits()).isEqualTo(50);

    // Set via parent
    FluxgateProperties.WaitForRefillProperties newWaitForRefill =
        new FluxgateProperties.WaitForRefillProperties();
    newWaitForRefill.setEnabled(false);
    rateLimit.setWaitForRefill(newWaitForRefill);
    assertThat(rateLimit.getWaitForRefill().isEnabled()).isFalse();
  }

  @Test
  void shouldHaveDefaultMissingRuleBehavior() {
    FluxgateProperties properties = new FluxgateProperties();
    FluxgateProperties.RateLimitProperties rateLimit = properties.getRatelimit();

    // Default should be ALLOW
    assertThat(rateLimit.getMissingRuleBehavior())
        .isEqualTo(FluxgateProperties.MissingRuleBehavior.ALLOW);
    assertThat(rateLimit.isDenyWhenRuleMissing()).isFalse();
  }

  @Test
  void shouldSetMissingRuleBehaviorToDeny() {
    FluxgateProperties properties = new FluxgateProperties();
    FluxgateProperties.RateLimitProperties rateLimit = properties.getRatelimit();

    rateLimit.setMissingRuleBehavior(FluxgateProperties.MissingRuleBehavior.DENY);

    assertThat(rateLimit.getMissingRuleBehavior())
        .isEqualTo(FluxgateProperties.MissingRuleBehavior.DENY);
    assertThat(rateLimit.isDenyWhenRuleMissing()).isTrue();
  }

  @Test
  void shouldSetMissingRuleBehaviorToAllow() {
    FluxgateProperties properties = new FluxgateProperties();
    FluxgateProperties.RateLimitProperties rateLimit = properties.getRatelimit();

    // First set to DENY
    rateLimit.setMissingRuleBehavior(FluxgateProperties.MissingRuleBehavior.DENY);
    assertThat(rateLimit.isDenyWhenRuleMissing()).isTrue();

    // Then set back to ALLOW
    rateLimit.setMissingRuleBehavior(FluxgateProperties.MissingRuleBehavior.ALLOW);
    assertThat(rateLimit.getMissingRuleBehavior())
        .isEqualTo(FluxgateProperties.MissingRuleBehavior.ALLOW);
    assertThat(rateLimit.isDenyWhenRuleMissing()).isFalse();
  }

  @Test
  void missingRuleBehaviorEnumShouldHaveCorrectValues() {
    FluxgateProperties.MissingRuleBehavior[] values =
        FluxgateProperties.MissingRuleBehavior.values();

    assertThat(values).hasSize(2);
    assertThat(values)
        .contains(
            FluxgateProperties.MissingRuleBehavior.ALLOW,
            FluxgateProperties.MissingRuleBehavior.DENY);
  }

  @Test
  void missingRuleBehaviorEnumValueOf() {
    assertThat(FluxgateProperties.MissingRuleBehavior.valueOf("ALLOW"))
        .isEqualTo(FluxgateProperties.MissingRuleBehavior.ALLOW);
    assertThat(FluxgateProperties.MissingRuleBehavior.valueOf("DENY"))
        .isEqualTo(FluxgateProperties.MissingRuleBehavior.DENY);
  }

  @Test
  void shouldSetActuatorProperties() {
    FluxgateProperties properties = new FluxgateProperties();
    FluxgateProperties.ActuatorProperties actuator = properties.getActuator();
    FluxgateProperties.ActuatorProperties.HealthProperties health = actuator.getHealth();

    // Default values
    assertThat(health.isEnabled()).isTrue();

    // Set custom values
    health.setEnabled(false);
    assertThat(health.isEnabled()).isFalse();

    // Set health properties via setter
    FluxgateProperties.ActuatorProperties.HealthProperties newHealth =
        new FluxgateProperties.ActuatorProperties.HealthProperties();
    newHealth.setEnabled(true);
    actuator.setHealth(newHealth);
    assertThat(actuator.getHealth().isEnabled()).isTrue();
  }

  @Test
  void shouldSetMetricsProperties() {
    FluxgateProperties properties = new FluxgateProperties();
    FluxgateProperties.MetricsProperties metrics = properties.getMetrics();

    // Default values
    assertThat(metrics.isEnabled()).isTrue();
    assertThat(metrics.isIncludeEndpoint()).isTrue();

    // Set custom values
    metrics.setEnabled(false);
    metrics.setIncludeEndpoint(false);

    assertThat(metrics.isEnabled()).isFalse();
    assertThat(metrics.isIncludeEndpoint()).isFalse();

    // Set via parent
    FluxgateProperties.MetricsProperties newMetrics = new FluxgateProperties.MetricsProperties();
    newMetrics.setEnabled(true);
    properties.setMetrics(newMetrics);
    assertThat(properties.getMetrics().isEnabled()).isTrue();

    // Set actuator via parent
    FluxgateProperties.ActuatorProperties newActuator = new FluxgateProperties.ActuatorProperties();
    properties.setActuator(newActuator);
    assertThat(properties.getActuator()).isNotNull();
  }

  @Test
  void shouldSetRedisClusterProperties() {
    FluxgateProperties properties = new FluxgateProperties();
    FluxgateProperties.RedisProperties redis = properties.getRedis();

    // Test mode
    redis.setMode("cluster");
    assertThat(redis.getMode()).isEqualTo("cluster");
    assertThat(redis.getEffectiveMode()).isEqualTo("cluster");
    assertThat(redis.isClusterMode()).isTrue();

    // Test standalone mode
    redis.setMode("standalone");
    assertThat(redis.getEffectiveMode()).isEqualTo("standalone");
    assertThat(redis.isClusterMode()).isFalse();

    // Test auto mode with single URI
    redis.setMode("auto");
    redis.setUri("redis://localhost:6379");
    assertThat(redis.getEffectiveMode()).isEqualTo("standalone");

    // Test auto mode with multiple URIs (comma-separated)
    redis.setUri("redis://node1:6379,redis://node2:6379");
    assertThat(redis.getEffectiveMode()).isEqualTo("cluster");

    // Test timeout
    redis.setTimeoutMs(10000);
    assertThat(redis.getTimeoutMs()).isEqualTo(10000);
  }

  @Test
  void shouldHaveCorrectDdlAutoValues() {
    // Test all enum values
    assertThat(FluxgateProperties.DdlAuto.VALIDATE.name()).isEqualTo("VALIDATE");
    assertThat(FluxgateProperties.DdlAuto.CREATE.name()).isEqualTo("CREATE");
  }

  @Test
  void shouldSetNestedPropertiesDirectly() {
    FluxgateProperties properties = new FluxgateProperties();

    // Test setMongo
    FluxgateProperties.MongoProperties mongo = new FluxgateProperties.MongoProperties();
    mongo.setEnabled(true);
    properties.setMongo(mongo);
    assertThat(properties.getMongo().isEnabled()).isTrue();

    // Test setRedis
    FluxgateProperties.RedisProperties redis = new FluxgateProperties.RedisProperties();
    redis.setEnabled(true);
    properties.setRedis(redis);
    assertThat(properties.getRedis().isEnabled()).isTrue();

    // Test setRatelimit
    FluxgateProperties.RateLimitProperties ratelimit = new FluxgateProperties.RateLimitProperties();
    ratelimit.setEnabled(false);
    properties.setRatelimit(ratelimit);
    assertThat(properties.getRatelimit().isEnabled()).isFalse();

    // Test setMetrics
    FluxgateProperties.MetricsProperties metrics = new FluxgateProperties.MetricsProperties();
    metrics.setEnabled(false);
    properties.setMetrics(metrics);
    assertThat(properties.getMetrics().isEnabled()).isFalse();

    // Test setActuator
    FluxgateProperties.ActuatorProperties actuator = new FluxgateProperties.ActuatorProperties();
    properties.setActuator(actuator);
    assertThat(properties.getActuator()).isNotNull();
  }

  /** Integration test for Spring Boot property binding. */
  @SpringBootTest(classes = FluxgatePropertiesBindingTest.TestConfig.class)
  @TestPropertySource(
      properties = {
        "fluxgate.mongo.enabled=true",
        "fluxgate.mongo.uri=mongodb://test:27017/testdb",
        "fluxgate.mongo.database=testdb",
        "fluxgate.redis.enabled=true",
        "fluxgate.redis.uri=redis://test:6379",
        "fluxgate.ratelimit.filter-enabled=true",
        "fluxgate.ratelimit.default-rule-set-id=test-rules",
        "fluxgate.ratelimit.include-patterns=/api/*,/v2/*",
        "fluxgate.ratelimit.exclude-patterns=/health,/metrics"
      })
  static class FluxgatePropertiesBindingTest {

    @EnableConfigurationProperties(FluxgateProperties.class)
    static class TestConfig {}

    @Autowired private FluxgateProperties properties;

    @Test
    void shouldBindPropertiesFromYaml() {
      assertThat(properties.getMongo().isEnabled()).isTrue();
      assertThat(properties.getMongo().getUri()).isEqualTo("mongodb://test:27017/testdb");
      assertThat(properties.getMongo().getDatabase()).isEqualTo("testdb");

      assertThat(properties.getRedis().isEnabled()).isTrue();
      assertThat(properties.getRedis().getUri()).isEqualTo("redis://test:6379");

      assertThat(properties.getRatelimit().isFilterEnabled()).isTrue();
      assertThat(properties.getRatelimit().getDefaultRuleSetId()).isEqualTo("test-rules");
      assertThat(properties.getRatelimit().getIncludePatterns()).containsExactly("/api/*", "/v2/*");
      assertThat(properties.getRatelimit().getExcludePatterns())
          .containsExactly("/health", "/metrics");
    }
  }
}
