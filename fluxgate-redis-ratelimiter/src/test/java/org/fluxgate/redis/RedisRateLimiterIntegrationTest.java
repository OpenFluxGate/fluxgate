package org.fluxgate.redis;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.support.RedisContainerSupport;
import org.junit.jupiter.api.*;

/**
 * Integration tests for {@link RedisRateLimiter} against a real Redis.
 *
 * <p>The target comes from {@link RedisContainerSupport}: a supplied {@code FLUXGATE_REDIS_URI}, a
 * Testcontainers {@code redis:7-alpine}, or the test is skipped. Every rule set id carries a run id
 * so a rerun cannot inherit buckets, and only those keys are deleted afterwards.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisRateLimiterIntegrationTest {

  private static final String RUN_ID = RedisContainerSupport.newRunId();

  private static RedisRateLimiterConfig config;
  private static RedisConnectionProvider connectionProvider;
  private static RedisRateLimiter rateLimiter;

  @BeforeAll
  static void setUp() {
    config = new RedisRateLimiterConfig(RedisContainerSupport.redisUri());
    connectionProvider = config.getConnectionProvider();
    rateLimiter = new RedisRateLimiter(config.getTokenBucketStore());
  }

  @AfterAll
  static void tearDown() {
    if (config != null) {
      RedisContainerSupport.deleteKeys(
          connectionProvider, RedisContainerSupport.KEY_PREFIX + "*" + RUN_ID + "*");
      config.close();
    }
  }

  @Test
  @Order(1)
  @DisplayName("Should allow requests within rate limit")
  void shouldAllowWithinLimit() {
    // given: 3 requests per minute rule
    RateLimitRuleSet ruleSet = createRuleSet(ruleSetId("allow"), 60, 3);
    RequestContext context = createContext("192.168.1.1");

    // when: make 3 requests
    for (int i = 0; i < 3; i++) {
      RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

      // then: all should be allowed, with headers' worth of information
      assertThat(result.isAllowed()).isTrue();
      assertThat(result.getRemainingTokens()).isGreaterThanOrEqualTo(0);
      assertThat(result.getLimit()).isEqualTo(3);
      assertThat(result.getBandLabel()).isEqualTo("test-band");
      assertThat(result.getPolicy()).isEqualTo(OnLimitExceedPolicy.REJECT_REQUEST);
      assertThat(result.getResetTimeMillis()).isGreaterThan(0);
    }
  }

  @Test
  @Order(2)
  @DisplayName("Should reject requests exceeding rate limit")
  void shouldRejectExceedingLimit() {
    // given: 3 requests per minute rule
    RateLimitRuleSet ruleSet = createRuleSet(ruleSetId("reject"), 60, 3);
    RequestContext context = createContext("192.168.1.2");

    // when: make 3 allowed requests
    for (int i = 0; i < 3; i++) {
      RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);
      assertThat(result.isAllowed()).isTrue();
    }

    // then: 4th request should be rejected and carry the real remaining tokens
    RateLimitResult rejectedResult = rateLimiter.tryConsume(context, ruleSet, 1);
    assertThat(rejectedResult.isAllowed()).isFalse();
    assertThat(rejectedResult.getNanosToWaitForRefill()).isGreaterThan(0);
    assertThat(rejectedResult.getRemainingTokens()).isZero();
    assertThat(rejectedResult.getLimit()).isEqualTo(3);
    assertThat(rejectedResult.getBandLabel()).isEqualTo("test-band");
    assertThat(rejectedResult.getResetTimeMillis()).isGreaterThan(System.currentTimeMillis());
  }

  @Test
  @Order(3)
  @DisplayName("Should isolate rate limits per IP")
  void shouldIsolatePerIp() {
    // given
    RateLimitRuleSet ruleSet = createRuleSet(ruleSetId("isolation"), 60, 5);
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

  @Test
  @Order(4)
  @DisplayName("Bucket keys carry the hash tag and are matched by bucketKeyPattern")
  void shouldWriteKeysThatTheResetPatternMatches() {
    String ruleSetId = ruleSetId("keys");
    RateLimitRuleSet ruleSet = createRuleSet(ruleSetId, 60, 5);

    rateLimiter.tryConsume(createContext("172.16.0.9"), ruleSet, 1);

    List<String> keys =
        connectionProvider.scanKeys(RedisRateLimiter.bucketKeyPattern(ruleSetId), 500);

    assertThat(keys)
        .containsExactly(
            RedisRateLimiter.BUCKET_KEY_PREFIX
                + "{"
                + ruleSetId
                + ":rule-1:ip:172.16.0.9}:test-band");
  }

  @Test
  @Order(5)
  @DisplayName("A request rejected by the slow band does not drain the fast band")
  void rejectedRequestsShouldNotDrainTheFastBand() {
    String ruleSetId = ruleSetId("multi-band");
    RateLimitBand fast = RateLimitBand.builder(Duration.ofSeconds(1), 10).label("fast").build();
    RateLimitBand slow = RateLimitBand.builder(Duration.ofMinutes(1), 2).label("slow").build();

    RateLimitRule rule =
        RateLimitRule.builder("rule-1")
            .name("Two bands")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .keyStrategyId("clientIp")
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(fast)
            .addBand(slow)
            .ruleSetId(ruleSetId)
            .build();

    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder(ruleSetId)
            .keyResolver(new LimitScopeKeyResolver())
            .rules(List.of(rule))
            .build();

    RequestContext context = createContext("10.1.2.3");

    assertThat(rateLimiter.tryConsume(context, ruleSet, 1).isAllowed()).isTrue();
    assertThat(rateLimiter.tryConsume(context, ruleSet, 1).isAllowed()).isTrue();

    String fastKey =
        RedisRateLimiter.BUCKET_KEY_PREFIX + "{" + ruleSetId + ":rule-1:ip:10.1.2.3}:fast";
    long fastTokensBefore = tokens(fastKey);

    for (int i = 0; i < 5; i++) {
      RateLimitResult rejected = rateLimiter.tryConsume(context, ruleSet, 1);
      assertThat(rejected.isAllowed()).isFalse();
      assertThat(rejected.getBandLabel()).isEqualTo("slow");
      assertThat(rejected.getLimit()).isEqualTo(2);
    }

    assertThat(tokens(fastKey))
        .as("the per-second band keeps its tokens while the per-minute band rejects")
        .isEqualTo(fastTokensBefore);
  }

  @Test
  @Order(6)
  @DisplayName("A rule set whose rules are all disabled reports no rule and no quota")
  void shouldAllowWithoutRuleWhenEverythingIsDisabled() {
    String ruleSetId = ruleSetId("disabled");
    RateLimitRule disabled =
        RateLimitRule.builder("rule-1")
            .name("Disabled")
            .enabled(false)
            .scope(LimitScope.PER_IP)
            .keyStrategyId("clientIp")
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 1).label("test-band").build())
            .ruleSetId(ruleSetId)
            .build();

    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder(ruleSetId)
            .keyResolver(new LimitScopeKeyResolver())
            .rules(List.of(disabled))
            .build();

    RateLimitResult result = rateLimiter.tryConsume(createContext("10.9.9.9"), ruleSet, 1);

    assertThat(result.isAllowed()).isTrue();
    assertThat(result.hasRule()).isFalse();
    assertThat(result.getRemainingTokens()).isEqualTo(-1);
    assertThat(connectionProvider.scanKeys(RedisRateLimiter.bucketKeyPattern(ruleSetId), 500))
        .isEmpty();
  }

  private static long tokens(String key) {
    return Long.parseLong(connectionProvider.hgetall(key).get("tokens"));
  }

  /** Builds a rule set id that is unique to this JVM run, so buckets are never reused. */
  private String ruleSetId(String name) {
    return "it-" + RUN_ID + "-" + name;
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
