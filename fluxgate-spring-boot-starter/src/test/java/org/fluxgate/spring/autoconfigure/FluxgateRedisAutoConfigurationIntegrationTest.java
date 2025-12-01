package org.fluxgate.spring.autoconfigure;

import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.redis.RedisRateLimiter;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link FluxgateRedisAutoConfiguration}.
 *
 * These tests require a running Redis server at localhost:6379.
 * Run with: docker run -d -p 6379:6379 redis:7
 */
class FluxgateRedisAutoConfigurationIntegrationTest {

    @Configuration
    @EnableConfigurationProperties(FluxgateProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FluxgateRedisAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldCreateRedisBeansWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "fluxgate.redis.enabled=true",
                        "fluxgate.redis.uri=redis://localhost:6379"
                )
                .run(context -> {
                    // Verify all Redis beans are created
                    assertThat(context).hasSingleBean(RedisRateLimiterConfig.class);
                    assertThat(context).hasSingleBean(RedisTokenBucketStore.class);
                    assertThat(context).hasSingleBean(RateLimiter.class);

                    // Verify RateLimiter is RedisRateLimiter
                    RateLimiter rateLimiter = context.getBean(RateLimiter.class);
                    assertThat(rateLimiter).isInstanceOf(RedisRateLimiter.class);
                });
    }

    @Test
    void shouldConnectToRedisSuccessfully() {
        contextRunner
                .withPropertyValues(
                        "fluxgate.redis.enabled=true",
                        "fluxgate.redis.uri=redis://localhost:6379"
                )
                .run(context -> {
                    // Get the config and verify connection works
                    RedisRateLimiterConfig config = context.getBean(RedisRateLimiterConfig.class);

                    // Test PING to verify connection
                    String pong = config.getRedisCommands().ping();
                    assertThat(pong).isEqualTo("PONG");
                });
    }

    @Test
    void shouldCreateTokenBucketStoreWithWorkingLuaScripts() {
        contextRunner
                .withPropertyValues(
                        "fluxgate.redis.enabled=true",
                        "fluxgate.redis.uri=redis://localhost:6379"
                )
                .run(context -> {
                    RedisTokenBucketStore store = context.getBean(RedisTokenBucketStore.class);
                    RedisRateLimiterConfig config = context.getBean(RedisRateLimiterConfig.class);

                    // Clean up test key first
                    String testKey = "test:spring-boot-starter:integration";
                    config.getRedisCommands().del(testKey);

                    // Create a test band
                    org.fluxgate.core.config.RateLimitBand band =
                            org.fluxgate.core.config.RateLimitBand.builder(
                                    java.time.Duration.ofMinutes(1), 10
                            ).build();

                    // First consumption should succeed
                    var result1 = store.tryConsume(testKey, band, 1);
                    assertThat(result1.consumed()).isTrue();
                    assertThat(result1.remainingTokens()).isEqualTo(9);

                    // Consume remaining tokens
                    for (int i = 0; i < 9; i++) {
                        store.tryConsume(testKey, band, 1);
                    }

                    // 11th request should be rejected
                    var result11 = store.tryConsume(testKey, band, 1);
                    assertThat(result11.consumed()).isFalse();
                    assertThat(result11.remainingTokens()).isEqualTo(0);
                    assertThat(result11.nanosToWaitForRefill()).isGreaterThan(0);

                    // Clean up
                    config.getRedisCommands().del(testKey);
                });
    }

    @Test
    void shouldRespectCustomRedisUri() {
        // Test with explicit localhost:6379
        contextRunner
                .withPropertyValues(
                        "fluxgate.redis.enabled=true",
                        "fluxgate.redis.uri=redis://127.0.0.1:6379"
                )
                .run(context -> {
                    FluxgateProperties props = context.getBean(FluxgateProperties.class);
                    assertThat(props.getRedis().getUri()).isEqualTo("redis://127.0.0.1:6379");

                    // Verify connection works with this URI too
                    RedisRateLimiterConfig config = context.getBean(RedisRateLimiterConfig.class);
                    assertThat(config.getRedisCommands().ping()).isEqualTo("PONG");
                });
    }
}
