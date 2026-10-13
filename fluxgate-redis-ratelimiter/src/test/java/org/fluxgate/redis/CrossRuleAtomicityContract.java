package org.fluxgate.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.QuotaPeriod;
import org.fluxgate.core.config.RateLimitAlgorithm;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.fluxgate.redis.support.RedisContainerSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Cross-rule atomicity of {@link RedisRateLimiter}, shared by the standalone (Testcontainers) and
 * the Redis Cluster integration tests.
 *
 * <p>Rule {@code a-first} (per IP, capacity 3) is evaluated before rule {@code b-second} (global,
 * capacity 1). Once {@code b-second} is exhausted every request is rejected by it, and {@code
 * a-first} must not pay for those rejections: probing {@code a-first} alone afterwards must still
 * find exactly the 2 permits the one allowed request left. Before the fix each rejected request
 * drained {@code a-first} by one.
 *
 * <p>Each case runs with both strategies: {@link Strategy#AUTO} (one script call wherever the keys
 * allow it) and {@link Strategy#COMPENSATION} (rule-by-rule with refunds, forced by a store that
 * never reports keys as co-located).
 */
abstract class CrossRuleAtomicityContract {

  static final String RUN_ID = RedisContainerSupport.newRunId();

  /** How the limiter under test is allowed to evaluate several rules. */
  enum Strategy {
    AUTO,
    COMPENSATION
  }

  private static RedisRateLimiterConfig config;

  /** Opens the shared config; subclasses call it from {@code @BeforeAll}. */
  static void connect(String uri) {
    config = new RedisRateLimiterConfig(uri);
  }

  static RedisConnectionProvider redis() {
    return config.getConnectionProvider();
  }

  @AfterAll
  static void closeConnection() {
    if (config != null) {
      RedisContainerSupport.deleteKeys(
          redis(), RedisContainerSupport.KEY_PREFIX + "*" + RUN_ID + "*");
      config.close();
      config = null;
    }
  }

  static RedisRateLimiter limiter(Strategy strategy) {
    if (strategy == Strategy.AUTO) {
      return new RedisRateLimiter(config.getTokenBucketStore());
    }
    RedisTokenBucketStore neverTogether =
        new RedisTokenBucketStore(redis()) {
          @Override
          public boolean canEvaluateAtomically(Collection<String> bucketKeys) {
            return false;
          }
        };
    return new RedisRateLimiter(neverTogether);
  }

  static Stream<Arguments> cases() {
    Stream.Builder<Arguments> cases = Stream.builder();
    for (Strategy strategy : Strategy.values()) {
      for (String algorithm :
          List.of(
              "TOKEN_BUCKET", "SLIDING_WINDOW", "FIXED_WINDOW", "FIXED_WINDOW_DAILY", "MULTI")) {
        cases.add(Arguments.of(strategy, algorithm));
      }
    }
    return cases.build();
  }

  @ParameterizedTest(name = "{0} / {1}")
  @MethodSource("cases")
  @DisplayName("A rejection by a later rule leaves the earlier rule's remaining unchanged")
  void laterRejectionDoesNotDrainEarlierRule(Strategy strategy, String algorithm) {
    String ruleSetId =
        "xrule-" + algorithm.toLowerCase() + "-" + strategy.name().toLowerCase() + "-" + RUN_ID;
    RateLimitRule first = rule("a-first", ruleSetId, LimitScope.PER_IP, bands(algorithm, 3));
    RateLimitRule second = rule("b-second", ruleSetId, LimitScope.GLOBAL, bands(algorithm, 1));
    assertRejectionsCostNothing(limiter(strategy), ruleSetId, first, second);
  }

  @ParameterizedTest(name = "{0}")
  @org.junit.jupiter.params.provider.EnumSource(Strategy.class)
  @DisplayName("When several rules reject, Retry-After is the longest wait of them")
  void severalRejectingRulesReportTheLongestWait(Strategy strategy) {
    String ruleSetId = "maxwait-" + strategy.name().toLowerCase() + "-" + RUN_ID;
    // a-first: 1 per second (wait <= 1 s); b-second: 1 per hour (wait about an hour)
    RateLimitRule first =
        rule(
            "a-first",
            ruleSetId,
            LimitScope.PER_IP,
            List.of(RateLimitBand.builder(Duration.ofSeconds(1), 1).label("tb").build()));
    RateLimitRule second = rule("b-second", ruleSetId, LimitScope.GLOBAL, bands("TOKEN_BUCKET", 1));
    RedisRateLimiter limiter = limiter(strategy);
    RequestContext context = context("10.9.8.7");
    RateLimitRuleSet both = ruleSet(ruleSetId, first, second);

    assertThat(limiter.tryConsume(context, both, 1).isAllowed()).isTrue();
    RateLimitResult rejected = limiter.tryConsume(context, both, 1);

    assertThat(rejected.isAllowed()).isFalse();
    assertThat(rejected.getMatchedRule().getId()).isEqualTo("b-second");
    assertThat(rejected.getNanosToWaitForRefill())
        .as("waiting for a-first alone would be rejected again by b-second")
        .isGreaterThan(Duration.ofMinutes(59).toNanos());
  }

  /**
   * Exhausts {@code second}, sends rejected requests, then probes {@code first} on its own: it must
   * still hold capacity - 1 permits.
   */
  static void assertRejectionsCostNothing(
      RedisRateLimiter limiter, String ruleSetId, RateLimitRule first, RateLimitRule second) {
    RequestContext context = context("10.1.2.3");
    RateLimitRuleSet both = ruleSet(ruleSetId, first, second);

    RateLimitResult allowed = limiter.tryConsume(context, both, 1);
    assertThat(allowed.isAllowed()).isTrue();

    for (int i = 0; i < 5; i++) {
      RateLimitResult rejected = limiter.tryConsume(context, both, 1);
      assertThat(rejected.isAllowed()).as("request %d", i + 2).isFalse();
      assertThat(rejected.getMatchedRule().getId()).isEqualTo(second.getId());
    }

    // The same rule set id, rule id and key address the same buckets; only the first rule is left.
    RateLimitRuleSet onlyFirst = ruleSet(ruleSetId, first);
    assertThat(limiter.tryConsume(context, onlyFirst, 1).isAllowed()).isTrue();
    assertThat(limiter.tryConsume(context, onlyFirst, 1).isAllowed()).isTrue();
    assertThat(limiter.tryConsume(context, onlyFirst, 1).isAllowed())
        .as("rule %s: 3 - 1 allowed - 2 probes = 0 left", first.getId())
        .isFalse();
  }

  /** The bands of one rule: a single band of the given algorithm, or a mixed multi-band rule. */
  static List<RateLimitBand> bands(String algorithm, long capacity) {
    Duration hour = Duration.ofHours(1);
    switch (algorithm) {
      case "TOKEN_BUCKET":
        return List.of(RateLimitBand.builder(hour, capacity).label("tb").build());
      case "SLIDING_WINDOW":
        return List.of(
            RateLimitBand.builder(hour, capacity)
                .label("sw")
                .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
                .slidingWindowBuckets(6)
                .build());
      case "FIXED_WINDOW":
        return List.of(
            RateLimitBand.builder(hour, capacity)
                .label("fw")
                .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                .build());
      case "FIXED_WINDOW_DAILY":
        return List.of(
            RateLimitBand.builder(Duration.ofDays(1), capacity)
                .label("daily")
                .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                .quotaPeriod(QuotaPeriod.DAILY)
                .build());
      case "MULTI":
        // The binding band is the smallest one; the larger bands must be refunded too.
        return List.of(
            RateLimitBand.builder(hour, capacity + 10).label("tb").build(),
            RateLimitBand.builder(hour, capacity)
                .label("sw")
                .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
                .slidingWindowBuckets(4)
                .build(),
            RateLimitBand.builder(hour, capacity + 20)
                .label("fw")
                .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                .build());
      default:
        throw new IllegalArgumentException(algorithm);
    }
  }

  static RateLimitRule rule(
      String id, String ruleSetId, LimitScope scope, List<RateLimitBand> bands) {
    RateLimitRule.Builder builder =
        RateLimitRule.builder(id)
            .name(id)
            .enabled(true)
            .scope(scope)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .ruleSetId(ruleSetId);
    for (RateLimitBand band : bands) {
      builder.addBand(band);
    }
    return builder.build();
  }

  static RateLimitRuleSet ruleSet(String id, RateLimitRule... rules) {
    return RateLimitRuleSet.builder(id)
        .keyResolver(new LimitScopeKeyResolver())
        .rules(List.of(rules))
        .build();
  }

  static RequestContext context(String ip) {
    return RequestContext.builder().clientIp(ip).endpoint("/api/test").method("GET").build();
  }
}
