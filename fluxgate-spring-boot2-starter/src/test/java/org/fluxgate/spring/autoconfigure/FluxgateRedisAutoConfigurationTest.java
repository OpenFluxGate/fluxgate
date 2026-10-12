package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.fluxgate.redis.connection.RedisUriUtils;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Tests for {@link FluxgateRedisAutoConfiguration}.
 *
 * <p>Tests the conditional behavior and property binding of Redis auto-configuration. Full Redis
 * integration tests require a running server and are handled separately.
 */
class FluxgateRedisAutoConfigurationTest {

  @Configuration
  @EnableConfigurationProperties(FluxgateProperties.class)
  static class TestConfig {}

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(FluxgateRedisAutoConfiguration.class))
          .withUserConfiguration(TestConfig.class);

  @Test
  void shouldDelegateUriMaskingToTheRedisModule() {
    // N-10: the starter used to mask with its own regex, which let
    // "redis://h:6379?password=SECRET" through in clear text and mangled the tail of URIs whose
    // password contained an '@'. RedisUriUtils parses with Lettuce and renders host:port[/db] only,
    // which is what SECURITY.md promises.
    assertThat(FluxgateRedisAutoConfiguration.class.getDeclaredMethods())
        .extracting(Method::getName)
        .doesNotContain("maskUri");

    assertThat(RedisUriUtils.mask("redis://h:6379?password=SECRET"))
        .doesNotContain("SECRET")
        .isEqualTo("h:6379");
    assertThat(RedisUriUtils.mask("redis://user:p@ssw0rd@h:6379/2"))
        .doesNotContain("ssw0rd")
        .doesNotContain("user");
  }

  @Test
  void shouldNotCreateBeansByDefault() {
    // Default: fluxgate.redis.enabled is false
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(FluxgateProperties.class);
          FluxgateProperties props = context.getBean(FluxgateProperties.class);
          assertThat(props.getRedis().isEnabled()).isFalse();
        });
  }

  @Test
  void shouldNotCreateBeansWhenRedisDisabled() {
    contextRunner
        .withPropertyValues("fluxgate.redis.enabled=false")
        .run(
            context -> {
              FluxgateProperties props = context.getBean(FluxgateProperties.class);
              assertThat(props.getRedis().isEnabled()).isFalse();
            });
  }

  @Test
  void shouldBindRedisProperties() {
    contextRunner
        .withPropertyValues(
            "fluxgate.redis.enabled=false", // Don't try to connect
            "fluxgate.redis.uri=redis://localhost:6379")
        .run(
            context -> {
              FluxgateProperties props = context.getBean(FluxgateProperties.class);
              assertThat(props.getRedis().getUri()).isEqualTo("redis://localhost:6379");
            });
  }

  @Test
  void shouldBindCustomRedisUri() {
    contextRunner
        .withPropertyValues(
            "fluxgate.redis.enabled=false", // Don't try to connect
            "fluxgate.redis.uri=redis://redis-master.prod:6379")
        .run(
            context -> {
              FluxgateProperties props = context.getBean(FluxgateProperties.class);
              assertThat(props.getRedis().getUri()).isEqualTo("redis://redis-master.prod:6379");
            });
  }

  @Test
  void shouldBindRedisPropertiesWithAuthUri() {
    contextRunner
        .withPropertyValues(
            "fluxgate.redis.enabled=false", // Don't try to connect
            "fluxgate.redis.uri=redis://user:password@redis.cluster.local:6379/0")
        .run(
            context -> {
              FluxgateProperties props = context.getBean(FluxgateProperties.class);
              assertThat(props.getRedis().getUri())
                  .isEqualTo("redis://user:password@redis.cluster.local:6379/0");
            });
  }

  @Test
  void shouldBindRedissSslUri() {
    contextRunner
        .withPropertyValues(
            "fluxgate.redis.enabled=false", // Don't try to connect
            "fluxgate.redis.uri=rediss://secure-redis.prod:6379")
        .run(
            context -> {
              FluxgateProperties props = context.getBean(FluxgateProperties.class);
              assertThat(props.getRedis().getUri()).startsWith("rediss://");
            });
  }

  @Test
  void shouldHaveCorrectDefaultValues() {
    contextRunner.run(
        context -> {
          FluxgateProperties props = context.getBean(FluxgateProperties.class);

          // Verify defaults
          assertThat(props.getRedis().isEnabled()).isFalse();
          assertThat(props.getRedis().getUri()).isEqualTo("redis://localhost:6379");
        });
  }
}
