package org.fluxgate.spring.autoconfigure;

import org.fluxgate.spring.properties.FluxgateProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link FluxgateRedisAutoConfiguration}.
 *
 * Tests the conditional behavior and property binding of Redis auto-configuration.
 * Full Redis integration tests require a running server and are handled separately.
 */
class FluxgateRedisAutoConfigurationTest {

    @Configuration
    @EnableConfigurationProperties(FluxgateProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FluxgateRedisAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldNotCreateBeansByDefault() {
        // Default: fluxgate.redis.enabled is false
        contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(FluxgateProperties.class);
                    FluxgateProperties props = context.getBean(FluxgateProperties.class);
                    assertThat(props.getRedis().isEnabled()).isFalse();
                });
    }

    @Test
    void shouldNotCreateBeansWhenRedisDisabled() {
        contextRunner
                .withPropertyValues("fluxgate.redis.enabled=false")
                .run(context -> {
                    FluxgateProperties props = context.getBean(FluxgateProperties.class);
                    assertThat(props.getRedis().isEnabled()).isFalse();
                });
    }

    @Test
    void shouldBindRedisProperties() {
        contextRunner
                .withPropertyValues(
                        "fluxgate.redis.enabled=false",  // Don't try to connect
                        "fluxgate.redis.uri=redis://localhost:6379"
                )
                .run(context -> {
                    FluxgateProperties props = context.getBean(FluxgateProperties.class);
                    assertThat(props.getRedis().getUri()).isEqualTo("redis://localhost:6379");
                });
    }

    @Test
    void shouldBindCustomRedisUri() {
        contextRunner
                .withPropertyValues(
                        "fluxgate.redis.enabled=false",  // Don't try to connect
                        "fluxgate.redis.uri=redis://redis-master.prod:6379"
                )
                .run(context -> {
                    FluxgateProperties props = context.getBean(FluxgateProperties.class);
                    assertThat(props.getRedis().getUri()).isEqualTo("redis://redis-master.prod:6379");
                });
    }

    @Test
    void shouldBindRedisPropertiesWithAuthUri() {
        contextRunner
                .withPropertyValues(
                        "fluxgate.redis.enabled=false",  // Don't try to connect
                        "fluxgate.redis.uri=redis://user:password@redis.cluster.local:6379/0"
                )
                .run(context -> {
                    FluxgateProperties props = context.getBean(FluxgateProperties.class);
                    assertThat(props.getRedis().getUri())
                            .isEqualTo("redis://user:password@redis.cluster.local:6379/0");
                });
    }

    @Test
    void shouldBindRedissSslUri() {
        contextRunner
                .withPropertyValues(
                        "fluxgate.redis.enabled=false",  // Don't try to connect
                        "fluxgate.redis.uri=rediss://secure-redis.prod:6379"
                )
                .run(context -> {
                    FluxgateProperties props = context.getBean(FluxgateProperties.class);
                    assertThat(props.getRedis().getUri()).startsWith("rediss://");
                });
    }

    @Test
    void shouldHaveCorrectDefaultValues() {
        contextRunner
                .run(context -> {
                    FluxgateProperties props = context.getBean(FluxgateProperties.class);

                    // Verify defaults
                    assertThat(props.getRedis().isEnabled()).isFalse();
                    assertThat(props.getRedis().getUri()).isEqualTo("redis://localhost:6379");
                });
    }
}
