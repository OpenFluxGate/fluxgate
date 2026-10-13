package org.fluxgate.core.bucket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.exception.InvalidRuleConfigException;
import org.fluxgate.core.exception.MissingRateLimitKeyException;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.junit.jupiter.api.Test;

class Bucket4jRateLimiterTest {

  private final RateLimiter rateLimiter = new Bucket4jRateLimiter();

  // --- Helper: Create common RuleSet configuration ----------------------

  private RateLimitRuleSet createSimpleRuleSet(
      KeyResolver keyResolver, RateLimitMetricsRecorder metricsRecorder) {

    RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 5).label("1m-5req").build();

    RateLimitRule rule =
        RateLimitRule.builder("TEST_RULE")
            .scope(LimitScope.GLOBAL)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(band)
            .build();

    return RateLimitRuleSet.builder("auth-api-default")
        .description("1m 5req global limit for testing")
        .rules(List.of(rule))
        .keyResolver(keyResolver)
        .metricsRecorder(metricsRecorder)
        .build();
  }

  private RequestContext createRequestContext(String clientIp, String userId) {
    return RequestContext.builder()
        .clientIp(clientIp)
        .userId(userId)
        .endpoint("/api/test")
        .method("GET")
        .build();
  }

  private RateLimitKey ipKey(String ip) {
    return RateLimitKey.of(ip);
  }

  // --- 1) Basic behavior test -------------------------------------------

  @Test
  void should_allow_within_capacity_and_reject_after() {
    // given
    KeyResolver keyResolver = (ctx, rule) -> ipKey(ctx.getClientIp());
    RateLimitRuleSet ruleSet = createSimpleRuleSet(keyResolver, null);

    RequestContext ctx = createRequestContext("127.0.0.1", "user-1");

    // when & then
    for (int i = 1; i <= 5; i++) {
      RateLimitResult result = rateLimiter.tryConsume(ctx, ruleSet, 1);
      assertThat(result.isAllowed()).as("request %s should be allowed", i).isTrue();
    }

    RateLimitResult sixth = rateLimiter.tryConsume(ctx, ruleSet, 1);

    // then
    assertThat(sixth.isAllowed()).isFalse();
    assertThat(sixth.getNanosToWaitForRefill())
        .as("should indicate wait time until refill")
        .isGreaterThan(0L);
  }

  // --- 2) Different keys should have independent buckets ----------------

  @Test
  void differentKeysShouldHaveIndependentBuckets() {
    // given
    KeyResolver keyResolver = (ctx, rule) -> ipKey(ctx.getClientIp());
    RateLimitRuleSet ruleSet = createSimpleRuleSet(keyResolver, null);

    RequestContext ip1 = createRequestContext("10.0.0.1", "user-1");
    RequestContext ip2 = createRequestContext("10.0.0.2", "user-2");

    // ip1: consume 5 times
    for (int i = 0; i < 5; i++) {
      RateLimitResult result = rateLimiter.tryConsume(ip1, ruleSet, 1);
      assertThat(result.isAllowed()).isTrue();
    }
    // ip1: 6th request should be rejected
    RateLimitResult ip1Sixth = rateLimiter.tryConsume(ip1, ruleSet, 1);
    assertThat(ip1Sixth.isAllowed()).isFalse();

    // ip2: should have new bucket, so allowed up to 5 times again
    for (int i = 0; i < 5; i++) {
      RateLimitResult result = rateLimiter.tryConsume(ip2, ruleSet, 1);
      assertThat(result.isAllowed()).as("ip2 should have its own quota").isTrue();
    }
  }

  // --- 3) Metrics hook invocation verification --------------------------

  @Test
  void metricsRecorderShouldBeCalled() {
    // given
    AtomicReference<RateLimitResult> lastRecorded = new AtomicReference<>();

    RateLimitMetricsRecorder recorder =
        (ctx, result) -> {
          lastRecorded.set(result);
        };

    KeyResolver keyResolver = (ctx, rule) -> ipKey(ctx.getClientIp());
    RateLimitRuleSet ruleSet = createSimpleRuleSet(keyResolver, recorder);
    RequestContext ctx = createRequestContext("192.168.0.10", "user-123");

    // when
    RateLimitResult result = rateLimiter.tryConsume(ctx, ruleSet, 1);

    // then
    assertThat(result.isAllowed()).isTrue();
    assertThat(lastRecorded.get())
        .as("metrics recorder should receive the same result")
        .isNotNull()
        .isSameAs(result);
  }

  // --- 4) Two-phase consumption across bands ----------------------------

  /**
   * Builds a rule with a slow band A (5 per minute) and a fast band B (3 per 500ms), so that band B
   * is the first to reject and then refills quickly enough to expose what band A has left.
   */
  private RateLimitRuleSet createTwoBandRuleSet(KeyResolver keyResolver) {
    RateLimitBand bandA =
        RateLimitBand.builder(Duration.ofMinutes(1), 5).label("A-5-per-1m").build();
    RateLimitBand bandB =
        RateLimitBand.builder(Duration.ofMillis(500), 3).label("B-3-per-500ms").build();

    RateLimitRule rule =
        RateLimitRule.builder("TWO_BAND_RULE")
            .scope(LimitScope.GLOBAL)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(bandA)
            .addBand(bandB)
            .build();

    return RateLimitRuleSet.builder("two-band-ruleset")
        .rules(List.of(rule))
        .keyResolver(keyResolver)
        .build();
  }

  @Test
  void rejectedRequestsShouldNotDrainTheOtherBands() throws Exception {
    // given: band A allows 5/min, band B allows 3 per 500ms
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();
    RateLimitRuleSet ruleSet = createTwoBandRuleSet((ctx, rule) -> ipKey(ctx.getClientIp()));
    RequestContext ctx = createRequestContext("10.1.1.1", "user-1");

    // when: three requests exhaust band B, leaving 2 tokens in band A
    for (int i = 1; i <= 3; i++) {
      assertThat(limiter.tryConsume(ctx, ruleSet, 1).isAllowed())
          .as("request %s should be allowed", i)
          .isTrue();
    }

    // and: three more requests are rejected by band B
    for (int i = 4; i <= 6; i++) {
      RateLimitResult rejected = limiter.tryConsume(ctx, ruleSet, 1);

      assertThat(rejected.isAllowed()).as("request %s should be rejected", i).isFalse();
      assertThat(rejected.getBandLabel()).isEqualTo("B-3-per-500ms");
      assertThat(rejected.getLimit()).isEqualTo(3L);
      assertThat(rejected.getNanosToWaitForRefill()).isGreaterThan(0L);
      assertThat(rejected.getPolicy()).isEqualTo(OnLimitExceedPolicy.REJECT_REQUEST);
      assertThat(rejected.getResetTimeMillis()).isGreaterThan(System.currentTimeMillis());
    }

    // then: once band B has refilled, band A must still hold the 2 tokens it had. Draining it on
    // rejection would have emptied it and this request would be refused.
    Thread.sleep(600);
    RateLimitResult afterRefill = limiter.tryConsume(ctx, ruleSet, 1);

    assertThat(afterRefill.isAllowed())
        .as("the 3 rejected requests must not have drained band A")
        .isTrue();
    // The multi-bandwidth implementation identifies the binding band by smallest capacity
    // (heuristic), so Band B (cap=3) is reported even though Band A has fewer tokens remaining.
    // getRemainingTokens() is the probe minimum across all bandwidths (= Band A's 1 token).
    assertThat(afterRefill.getBandLabel()).isEqualTo("B-3-per-500ms");
    assertThat(afterRefill.getRemainingTokens()).isEqualTo(1L);
    assertThat(afterRefill.getLimit()).isEqualTo(3L);
  }

  @Test
  void allowedResultShouldCarryTheMostRestrictiveBand() {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();
    RateLimitRuleSet ruleSet = createTwoBandRuleSet((ctx, rule) -> ipKey(ctx.getClientIp()));
    RequestContext ctx = createRequestContext("10.1.1.2", "user-2");

    // Band B (3 tokens) runs out before band A (5 tokens), so it is the one worth reporting
    RateLimitResult result = limiter.tryConsume(ctx, ruleSet, 3);

    assertThat(result.isAllowed()).isTrue();
    assertThat(result.getBandLabel()).isEqualTo("B-3-per-500ms");
    assertThat(result.getLimit()).isEqualTo(3L);
    assertThat(result.getRemainingTokens()).isZero();
    assertThat(result.getResetTimeMillis()).isGreaterThan(System.currentTimeMillis());
  }

  @Test
  void rejectedRequestsShouldNotDrainTheOtherRules() throws Exception {
    // given: a slow per-IP cap of 4/min and a per-user burst of 2 per 100ms. The old
    // implementation consumed from every rule in turn, so a request refused by the burst rule
    // still charged the slow cap.
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();

    RateLimitRule slowCap =
        RateLimitRule.builder("slow-cap")
            .scope(LimitScope.PER_IP)
            .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 4).label("cap-4-per-1m").build())
            .build();
    RateLimitRule burst =
        RateLimitRule.builder("burst")
            .scope(LimitScope.PER_USER)
            .addBand(
                RateLimitBand.builder(Duration.ofMillis(100), 2).label("burst-2-per-100ms").build())
            .build();

    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("multi-rule-ruleset")
            .rules(List.of(slowCap, burst))
            .keyResolver(
                (ctx, rule) ->
                    "burst".equals(rule.getId())
                        ? RateLimitKey.of(ctx.getUserId())
                        : ipKey(ctx.getClientIp()))
            .build();

    RequestContext ctx = createRequestContext("10.4.0.1", "user-multi");

    // when: the burst rule is exhausted, leaving 2 tokens on the slow cap
    for (int i = 1; i <= 2; i++) {
      assertThat(limiter.tryConsume(ctx, ruleSet, 1).isAllowed()).isTrue();
    }

    // and: two requests are refused by the burst rule
    for (int i = 3; i <= 4; i++) {
      RateLimitResult rejected = limiter.tryConsume(ctx, ruleSet, 1);

      assertThat(rejected.isAllowed()).isFalse();
      assertThat(rejected.getBandLabel()).isEqualTo("burst-2-per-100ms");
    }

    // then: after the burst window rolls over, the slow cap still has its 2 remaining tokens
    Thread.sleep(150);
    assertThat(limiter.tryConsume(ctx, ruleSet, 1).isAllowed())
        .as("the refused requests must not have charged the slow cap")
        .isTrue();
    assertThat(limiter.tryConsume(ctx, ruleSet, 1).isAllowed()).isTrue();

    // and the slow cap is the rule that refuses the 5th request, exactly as configured
    Thread.sleep(150);
    RateLimitResult capped = limiter.tryConsume(ctx, ruleSet, 1);

    assertThat(capped.isAllowed()).isFalse();
    assertThat(capped.getBandLabel()).isEqualTo("cap-4-per-1m");
    assertThat(capped.getLimit()).isEqualTo(4L);
  }

  // --- 5) Guard rails ---------------------------------------------------

  @Test
  void permitsExceedingBandCapacityShouldBeRejectedAsMisconfiguration() {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();
    RateLimitRuleSet ruleSet = createSimpleRuleSet((ctx, rule) -> ipKey(ctx.getClientIp()), null);
    RequestContext ctx = createRequestContext("10.1.1.3", "user-3");

    assertThatThrownBy(() -> limiter.tryConsume(ctx, ruleSet, 6))
        .isInstanceOf(InvalidRuleConfigException.class)
        .hasMessageContaining("exceed the capacity of band");
  }

  @Test
  void missingRateLimitKeyShouldProduceARejectedResult() {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();
    KeyResolver rejectingResolver =
        (ctx, rule) -> {
          throw new MissingRateLimitKeyException(rule.getId(), rule.getScope());
        };
    RateLimitRuleSet ruleSet = createSimpleRuleSet(rejectingResolver, null);
    RequestContext ctx = createRequestContext(null, null);

    RateLimitResult result = limiter.tryConsume(ctx, ruleSet, 1);

    assertThat(result.isAllowed()).isFalse();
    assertThat(result.getKey().value()).isEqualTo("missing-key:TEST_RULE");
    assertThat(result.getNanosToWaitForRefill()).isZero();
    assertThat(result.getLimit()).isEqualTo(-1L);
  }

  @Test
  void missingKeySyntheticKeyShouldKeepItsPrefixWhenTheRuleIdIsRewritten() {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();
    RateLimitRule rule =
        RateLimitRule.builder("rule one")
            .scope(LimitScope.GLOBAL)
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 5).build())
            .build();
    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("rs")
            .rules(List.of(rule))
            .keyResolver(
                (ctx, r) -> {
                  throw new MissingRateLimitKeyException(r.getId(), r.getScope());
                })
            .build();

    RateLimitResult result = limiter.tryConsume(createRequestContext(null, null), ruleSet, 1);

    assertThat(result.isAllowed()).isFalse();
    assertThat(result.getKey().value()).matches("missing-key:h:rule_one:[0-9a-f]{16}");
  }

  // --- 6) Bucket cache is bounded --------------------------------------

  @Test
  void bucketCacheShouldNeverExceedMaximumSize() {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter(100L);
    RateLimitRuleSet ruleSet = createSimpleRuleSet((ctx, rule) -> ipKey(ctx.getClientIp()), null);

    // A client rotating its IP used to add one bucket per IP, for ever
    for (int i = 0; i < 5_000; i++) {
      limiter.tryConsume(
          createRequestContext("10.2." + (i / 250) + "." + (i % 250), null), ruleSet, 1);
    }

    assertThat(limiter.size()).isLessThanOrEqualTo(limiter.getMaximumSize());
  }

  @Test
  void resetShouldDropOnlyTheBucketsOfTheGivenRuleSet() {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();
    KeyResolver resolver = (ctx, rule) -> ipKey(ctx.getClientIp());
    RateLimitRuleSet first = createSimpleRuleSet(resolver, null);
    RateLimitRuleSet second =
        RateLimitRuleSet.builder("other-ruleset")
            .rules(first.getRules())
            .keyResolver(resolver)
            .build();
    RequestContext ctx = createRequestContext("10.3.0.1", null);

    // Exhaust the first rule set, then touch the second one
    for (int i = 0; i < 5; i++) {
      limiter.tryConsume(ctx, first, 1);
    }
    limiter.tryConsume(ctx, second, 1);
    assertThat(limiter.size()).isEqualTo(2L);
    assertThat(limiter.tryConsume(ctx, first, 1).isAllowed()).isFalse();

    limiter.reset(first.getId());

    assertThat(limiter.size()).isEqualTo(1L);
    assertThat(limiter.tryConsume(ctx, first, 1).isAllowed())
        .as("the rebuilt bucket starts full again")
        .isTrue();

    limiter.resetAll();
    assertThat(limiter.size()).isZero();
  }

  // --- 7) Evictions are counted, because an eviction resets a limit ----

  @Test
  void evictedBucketsShouldBeCounted() throws Exception {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter(100L);
    RateLimitRuleSet ruleSet = createSimpleRuleSet((ctx, rule) -> ipKey(ctx.getClientIp()), null);

    for (int i = 0; i < 5_000; i++) {
      limiter.tryConsume(
          createRequestContext("10.4." + (i / 250) + "." + (i % 250), null), ruleSet, 1);
    }

    // Caffeine notifies the removal listener on its own executor, so give it a moment to drain.
    long deadline = System.currentTimeMillis() + 5_000L;
    while (limiter.getEvictionCount() == 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(20L);
    }

    assertThat(limiter.getEvictionCount())
        .as("an evicted bucket comes back full, so the eviction rate is worth alerting on")
        .isPositive();
  }

  @Test
  void explicitResetShouldNotCountAsAnEviction() throws Exception {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();
    RateLimitRuleSet ruleSet = createSimpleRuleSet((ctx, rule) -> ipKey(ctx.getClientIp()), null);

    limiter.tryConsume(createRequestContext("10.5.0.1", null), ruleSet, 1);
    limiter.tryConsume(createRequestContext("10.5.0.2", null), ruleSet, 1);
    limiter.resetAll();

    Thread.sleep(200L);

    assertThat(limiter.getEvictionCount())
        .as("a rule change dropping the buckets is not cache pressure")
        .isZero();
  }

  // --- 8) Retry-After reports the longest wait among the rejecting rules ----

  @Test
  void rejectionShouldReportTheLongestWaitAcrossRejectingRules() {
    RateLimitRule perMinute =
        RateLimitRule.builder("per-minute")
            .scope(LimitScope.GLOBAL)
            .priority(10) // evaluated first
            .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 1).label("1m").build())
            .build();
    RateLimitRule perHour =
        RateLimitRule.builder("per-hour")
            .scope(LimitScope.GLOBAL)
            .addBand(RateLimitBand.builder(Duration.ofHours(1), 1).label("1h").build())
            .build();
    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder("retry-after")
            .rules(List.of(perMinute, perHour))
            .keyResolver((ctx, rule) -> RateLimitKey.of("k"))
            .build();
    RequestContext ctx = createRequestContext("10.6.0.1", null);

    assertThat(rateLimiter.tryConsume(ctx, ruleSet, 1).isAllowed()).isTrue();
    RateLimitResult rejected = rateLimiter.tryConsume(ctx, ruleSet, 1);

    assertThat(rejected.isAllowed()).isFalse();
    assertThat(rejected.getNanosToWaitForRefill())
        .as("retrying after the per-minute wait would only be rejected by the hourly rule")
        .isGreaterThan(Duration.ofMinutes(30).toNanos());
    assertThat(rejected.getMatchedRule().getId()).isEqualTo("per-hour");
  }

  @Test
  void failingMetricsRecorderDoesNotFailTheDecisionOrChargeTwice() {
    AtomicInteger calls = new AtomicInteger();
    RateLimitMetricsRecorder failing =
        (ctx, result) -> {
          calls.incrementAndGet();
          throw new IllegalStateException("metrics backend down");
        };
    RateLimitRuleSet ruleSet = createSimpleRuleSet((ctx, rule) -> RateLimitKey.of("k"), failing);
    RequestContext ctx = createRequestContext("10.7.0.1", null);

    for (int i = 1; i <= 5; i++) {
      RateLimitResult result = rateLimiter.tryConsume(ctx, ruleSet, 1);
      assertThat(result.isAllowed()).as("request %s should be allowed", i).isTrue();
      assertThat(result.getRemainingTokens()).as("one token per call").isEqualTo(5L - i);
    }
    assertThat(rateLimiter.tryConsume(ctx, ruleSet, 1).isAllowed()).isFalse();
    assertThat(calls).hasValue(6);
  }

  @Test
  void metricsRecorderSneakyThrowingACheckedExceptionDoesNotFailTheDecision() {
    AtomicInteger calls = new AtomicInteger();
    RateLimitMetricsRecorder failing =
        (ctx, result) -> {
          calls.incrementAndGet();
          sneakyThrow(new IOException("metrics backend down"));
        };
    RateLimitRuleSet ruleSet = createSimpleRuleSet((ctx, rule) -> RateLimitKey.of("k"), failing);
    RequestContext ctx = createRequestContext("10.7.0.2", null);

    RateLimitResult result = rateLimiter.tryConsume(ctx, ruleSet, 1);

    assertThat(result.isAllowed()).isTrue();
    assertThat(result.getRemainingTokens()).isEqualTo(4L);
    assertThat(calls).hasValue(1);
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
    throw (T) t;
  }
}
