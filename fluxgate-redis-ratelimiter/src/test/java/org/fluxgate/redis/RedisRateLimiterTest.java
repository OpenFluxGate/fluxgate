package org.fluxgate.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.junit.jupiter.api.*;

/**
 * Integration tests for RedisRateLimiter using local Redis.
 *
 * <p>Requires local Redis running on localhost:6379 Start Redis: docker run -d -p 6379:6379
 * redis:7-alpine
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisRateLimiterTest {

  private static final String REDIS_URI =
      System.getProperty(
          "fluxgate.redis.uri",
          System.getenv().getOrDefault("FLUXGATE_REDIS_URI", "redis://localhost:6379"));

  private static RedisRateLimiterConfig config;
  private static RedisRateLimiter rateLimiter;

  @BeforeAll
  static void setUp() throws IOException {
    config = new RedisRateLimiterConfig(REDIS_URI);
    rateLimiter = new RedisRateLimiter(config.getTokenBucketStore());
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
    config.getConnectionProvider().flushdb();
  }

  @Test
  @Order(1)
  @DisplayName("Should allow requests within rate limit")
  void shouldAllowWithinLimit() {
    // given: 3 requests per second rule
    RateLimitRuleSet ruleSet = createRuleSet("test-allow", 1, 3);
    RequestContext context = createContext("192.168.1.1");

    // when: make 3 requests
    for (int i = 0; i < 3; i++) {
      RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

      // then: all should be allowed
      assertThat(result.isAllowed()).isTrue();
      assertThat(result.getRemainingTokens()).isGreaterThanOrEqualTo(0);
    }
  }

  @Test
  @Order(2)
  @DisplayName("Should reject requests exceeding rate limit")
  void shouldRejectExceedingLimit() {
    // given: 3 requests per second rule
    RateLimitRuleSet ruleSet = createRuleSet("test-reject", 1, 3);
    RequestContext context = createContext("192.168.1.2");

    // when: make 3 allowed requests
    for (int i = 0; i < 3; i++) {
      RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);
      assertThat(result.isAllowed()).isTrue();
    }

    // then: 4th request should be rejected
    RateLimitResult rejectedResult = rateLimiter.tryConsume(context, ruleSet, 1);
    assertThat(rejectedResult.isAllowed()).isFalse();
    assertThat(rejectedResult.getNanosToWaitForRefill()).isGreaterThan(0);
  }

  @Test
  @Order(3)
  @DisplayName("Should isolate rate limits per IP")
  void shouldIsolatePerIp() {
    // given
    RateLimitRuleSet ruleSet = createRuleSet("test-isolation", 1, 5);
    RequestContext context1 = createContext("10.0.0.1");
    RequestContext context2 = createContext("10.0.0.2");

    // when: exhaust limit for IP1
    for (int i = 0; i < 5; i++) {
      rateLimiter.tryConsume(context1, ruleSet, 1);
    }

    RateLimitResult rejected = rateLimiter.tryConsume(context1, ruleSet, 1);
    assertThat(rejected.isAllowed()).isFalse();

    // then: IP2 should still be allowed
    RateLimitResult allowed = rateLimiter.tryConsume(context2, ruleSet, 1);
    assertThat(allowed.isAllowed()).isTrue();
  }

  private RateLimitRuleSet createRuleSet(String ruleSetId, int windowSeconds, long capacity) {
    RateLimitBand band =
        RateLimitBand.builder(Duration.ofSeconds(windowSeconds), capacity)
            .label("test-band")
            .build();

    RateLimitRule rule =
        RateLimitRule.builder("rule-1")
            .name("Test Rule")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .keyStrategyId("clientIp")
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(band)
            .ruleSetId(ruleSetId)
            .build();

    return RateLimitRuleSet.builder(ruleSetId)
        .keyResolver(new LimitScopeKeyResolver())
        .rules(List.of(rule))
        .build();
  }

  private RequestContext createContext(String ip) {
    return RequestContext.builder().clientIp(ip).endpoint("/api/test").method("GET").build();
  }
}
