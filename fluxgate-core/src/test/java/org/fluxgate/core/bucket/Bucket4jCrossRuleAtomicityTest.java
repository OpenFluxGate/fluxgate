package org.fluxgate.core.bucket;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitAlgorithm;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.key.MissingKeyBehavior;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Cross-rule atomicity of {@link Bucket4jRateLimiter}: a request rejected by a later rule must not
 * cost the rules evaluated before it - the same contract as the Redis limiter.
 *
 * <p>Rule {@code a-first} (per IP, capacity 3) is evaluated before {@code b-second} (global,
 * capacity 1). Once {@code b-second} is exhausted every request is rejected by it; probing {@code
 * a-first} alone afterwards must still find the 2 permits the one allowed request left.
 */
class Bucket4jCrossRuleAtomicityTest {

  private static final String RULE_SET = "xrule";

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"TOKEN_BUCKET", "SLIDING_WINDOW", "FIXED_WINDOW", "MULTI"})
  @DisplayName("A rejection by a later rule leaves the earlier rule's remaining unchanged")
  void laterRejectionDoesNotDrainEarlierRule(String algorithm) {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();
    RateLimitRule first = rule("a-first", LimitScope.PER_IP, bands(algorithm, 3));
    RateLimitRule second = rule("b-second", LimitScope.GLOBAL, bands(algorithm, 1));
    RateLimitRuleSet both = ruleSet(new LimitScopeKeyResolver(), first, second);
    RequestContext context = context("10.1.2.3");

    assertThat(limiter.tryConsume(context, both, 1).isAllowed()).isTrue();
    for (int i = 0; i < 5; i++) {
      RateLimitResult rejected = limiter.tryConsume(context, both, 1);
      assertThat(rejected.isAllowed()).isFalse();
      assertThat(rejected.getMatchedRule().getId()).isEqualTo("b-second");
    }

    assertRemaining(limiter, ruleSet(new LimitScopeKeyResolver(), first), context, 2);
  }

  @Test
  @DisplayName("A key that cannot be resolved for a later rule charges nothing")
  void missingKeyOfALaterRuleChargesNothing() {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();
    RateLimitRule first = rule("a-first", LimitScope.PER_IP, bands("TOKEN_BUCKET", 3));
    RateLimitRule second = rule("b-second", LimitScope.PER_USER, bands("TOKEN_BUCKET", 3));
    LimitScopeKeyResolver rejecting = new LimitScopeKeyResolver(MissingKeyBehavior.REJECT);
    RequestContext noUser = context("10.1.2.3");

    for (int i = 0; i < 5; i++) {
      RateLimitResult result = limiter.tryConsume(noUser, ruleSet(rejecting, first, second), 1);
      assertThat(result.isAllowed()).isFalse();
      assertThat(result.getKey().value()).isEqualTo("missing-key:b-second");
    }

    assertRemaining(limiter, ruleSet(rejecting, first), noUser, 3);
  }

  @Test
  @DisplayName("Under concurrency, only allowed requests are charged to the earlier rule")
  void concurrentRequestsChargeOnlyWhenAllRulesAllow() throws Exception {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();
    RateLimitRule first = rule("a-first", LimitScope.PER_IP, bands("TOKEN_BUCKET", 100));
    RateLimitRule second = rule("b-second", LimitScope.GLOBAL, bands("TOKEN_BUCKET", 10));
    RateLimitRuleSet both = ruleSet(new LimitScopeKeyResolver(), first, second);
    RequestContext context = context("10.9.9.9");

    int threads = 16;
    int perThread = 20;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> futures = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      futures.add(
          pool.submit(
              () -> {
                start.await();
                int allowed = 0;
                for (int i = 0; i < perThread; i++) {
                  if (limiter.tryConsume(context, both, 1).isAllowed()) {
                    allowed++;
                  }
                }
                return allowed;
              }));
    }
    start.countDown();
    int allowed = 0;
    for (Future<Integer> f : futures) {
      allowed += f.get(30, TimeUnit.SECONDS);
    }
    pool.shutdown();

    assertThat(allowed).isEqualTo(10);
    // 100 - 10 allowed; the 310 rejections must not have touched the per-IP rule
    assertRemaining(limiter, ruleSet(new LimitScopeKeyResolver(), first), context, 90);
  }

  @Test
  @DisplayName("The rejected result describes the rejecting rule")
  void rejectedResultNamesTheRejectingRule() {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();
    RateLimitRule first = rule("a-first", LimitScope.PER_IP, bands("TOKEN_BUCKET", 3));
    RateLimitRule second = rule("b-second", LimitScope.GLOBAL, bands("TOKEN_BUCKET", 1));
    RateLimitRuleSet both = ruleSet(new LimitScopeKeyResolver(), first, second);
    RequestContext context = context("10.1.2.3");

    RateLimitResult allowed = limiter.tryConsume(context, both, 1);
    assertThat(allowed.getMatchedRule().getId()).isEqualTo("b-second");
    assertThat(allowed.getRemainingTokens()).isZero();

    RateLimitResult rejected = limiter.tryConsume(context, both, 1);
    assertThat(rejected.getKey().value()).isEqualTo("global");
    assertThat(rejected.getLimit()).isEqualTo(1L);
    assertThat(rejected.getRemainingTokens()).isZero();
    assertThat(rejected.getNanosToWaitForRefill()).isPositive();
  }

  // ===== Helpers =====

  /** Exactly {@code expected} more single-permit requests fit, and the next one is rejected. */
  private static void assertRemaining(
      Bucket4jRateLimiter limiter, RateLimitRuleSet ruleSet, RequestContext context, int expected) {
    for (int i = 0; i < expected; i++) {
      assertThat(limiter.tryConsume(context, ruleSet, 1).isAllowed()).as("probe %d", i).isTrue();
    }
    assertThat(limiter.tryConsume(context, ruleSet, 1).isAllowed())
        .as("probe after %d", expected)
        .isFalse();
  }

  private static List<RateLimitBand> bands(String algorithm, long capacity) {
    Duration hour = Duration.ofHours(1);
    switch (algorithm) {
      case "TOKEN_BUCKET":
        return List.of(RateLimitBand.builder(hour, capacity).label("tb").build());
      case "SLIDING_WINDOW":
        return List.of(
            RateLimitBand.builder(hour, capacity)
                .label("sw")
                .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
                .build());
      case "FIXED_WINDOW":
        return List.of(
            RateLimitBand.builder(hour, capacity)
                .label("fw")
                .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                .build());
      case "MULTI":
        return List.of(
            RateLimitBand.builder(hour, capacity + 10).label("tb").build(),
            RateLimitBand.builder(hour, capacity)
                .label("sw")
                .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
                .build(),
            RateLimitBand.builder(hour, capacity + 20)
                .label("fw")
                .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                .build());
      default:
        throw new IllegalArgumentException(algorithm);
    }
  }

  private static RateLimitRule rule(String id, LimitScope scope, List<RateLimitBand> bands) {
    RateLimitRule.Builder builder =
        RateLimitRule.builder(id)
            .name(id)
            .enabled(true)
            .scope(scope)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .ruleSetId(RULE_SET);
    for (RateLimitBand band : bands) {
      builder.addBand(band);
    }
    return builder.build();
  }

  private static RateLimitRuleSet ruleSet(LimitScopeKeyResolver resolver, RateLimitRule... rules) {
    return RateLimitRuleSet.builder(RULE_SET).keyResolver(resolver).rules(List.of(rules)).build();
  }

  private static RequestContext context(String ip) {
    return RequestContext.builder().clientIp(ip).endpoint("/api/test").method("GET").build();
  }
}
