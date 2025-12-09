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
    assertThat(properties.getMongo().getUri()).isEqualTo("mongodb://localhost:27017/fluxgate");
    assertThat(properties.getMongo().getDatabase()).isEqualTo("fluxgate");
    assertThat(properties.getMongo().getRuleCollection()).isEqualTo("rate_limit_rules");
    assertThat(properties.getMongo().getEventCollection()).isNull(); // Optional, null by default
    assertThat(properties.getMongo().hasEventCollection()).isFalse();
    assertThat(properties.getMongo().getDdlAuto())
        .isEqualTo(FluxgateProperties.DdlAuto.VALIDATE); // Default is VALIDATE

    // Redis defaults
    assertThat(properties.getRedis().isEnabled()).isFalse();
    assertThat(properties.getRedis().getUri()).isEqualTo("redis://localhost:6379");

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

    assertThat(redis.isEnabled()).isTrue();
    assertThat(redis.getUri()).isEqualTo("redis://redis.internal:6379");
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
