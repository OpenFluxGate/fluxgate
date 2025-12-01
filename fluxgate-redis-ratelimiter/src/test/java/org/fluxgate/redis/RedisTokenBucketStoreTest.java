package org.fluxgate.redis;

import io.lettuce.core.api.sync.RedisCommands;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.store.BucketState;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for RedisTokenBucketStore using local Redis.
 * <p>
 * Requires local Redis running on localhost:6379
 * Start Redis: docker run -d -p 6379:6379 redis:7-alpine
 */
class RedisTokenBucketStoreTest {

    private static final String REDIS_URI =
            System.getProperty(
                    "fluxgate.redis.uri",
                    System.getenv().getOrDefault("FLUXGATE_REDIS_URI",
                            "redis://localhost:6379")
            );

    private static RedisRateLimiterConfig config;
    private static RedisTokenBucketStore store;
    private static RedisCommands<String, String> redisCommands;

    @BeforeAll
    static void setUp() throws IOException {
        config = new RedisRateLimiterConfig(REDIS_URI);
        store = new RedisTokenBucketStore(config.getSyncCommands());
        redisCommands = config.getSyncCommands();
    }

    @AfterAll
    static void tearDown() {
        if (config != null) {
            config.close();
        }
    }

    @BeforeEach
    void cleanRedis() {
        // Clear Redis before each test
        //redisCommands.flushdb();
    }

    @Test
    @DisplayName("Should consume tokens successfully when available")
    void shouldConsumeWhenAvailable() {
        // given
        RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(1), 10)
                .label("test")
                .build();

        String bucketKey = "test:bucket:1";

        // when
        BucketState state = store.tryConsume(bucketKey, band, 3);

        // then
        assertThat(state.consumed()).isTrue();
        assertThat(state.remainingTokens()).isEqualTo(7); // 10 - 3 = 7
        assertThat(state.nanosToWaitForRefill()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should reject when not enough tokens")
    void shouldRejectWhenNotEnoughTokens() {
        // given
        RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(1), 5)
                .label("test")
                .build();

        String bucketKey = "test:bucket:2";

        // when: consume all tokens
        store.tryConsume(bucketKey, band, 5);

        // then: next request should be rejected
        BucketState rejectedState = store.tryConsume(bucketKey, band, 1);
        assertThat(rejectedState.consumed()).isFalse();
        assertThat(rejectedState.remainingTokens()).isEqualTo(0);
        assertThat(rejectedState.nanosToWaitForRefill()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should refill tokens over time")
    void shouldRefillOverTime() throws InterruptedException {
        // given: 10 tokens per second = refills quickly
        RateLimitBand band = RateLimitBand.builder(Duration.ofMillis(100), 10)
                .label("test")
                .build();

        String bucketKey = "test:bucket:3";

        // when: consume all tokens
        BucketState state1 = store.tryConsume(bucketKey, band, 10);
        assertThat(state1.consumed()).isTrue();
        assertThat(state1.remainingTokens()).isEqualTo(0);

        // wait for refill
        Thread.sleep(150); // Wait longer than window

        // then: tokens should be refilled
        BucketState state2 = store.tryConsume(bucketKey, band, 5);
        assertThat(state2.consumed()).isTrue();
        assertThat(state2.remainingTokens()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Should set TTL on bucket keys")
    void shouldSetTtl() {
        // given
        RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 100)
                .label("test")
                .build();

        String bucketKey = "test:bucket:ttl";

        // when
        store.tryConsume(bucketKey, band, 1);

        // then: key should have TTL set
        Long ttl = redisCommands.ttl(bucketKey);
        assertThat(ttl).isGreaterThan(0);
        assertThat(ttl).isLessThanOrEqualTo(60);
    }
}
