package org.fluxgate.testkit.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryRateLimitHandler")
class InMemoryRateLimitHandlerTest {

  private static final String RULE_SET = "api-limits";

  private static RateLimitRuleSet twoPerMinutePerIp() {
    return FluxgateTestRules.rule("per-ip")
        .perIp()
        .band(Duration.ofMinutes(1), 2)
        .toRuleSet(RULE_SET);
  }

  private static RequestContext from(String ip) {
    return RequestContext.builder().clientIp(ip).endpoint("/api/test").method("GET").build();
  }

  @Nested
  @DisplayName("consumption")
  class ConsumptionTests {

    @Test
    @DisplayName("should allow up to the band capacity and reject afterwards")
    void shouldAllowUpToCapacity() {
      InMemoryRateLimitHandler handler = InMemoryRateLimitHandler.withRuleSet(twoPerMinutePerIp());
      RequestContext context = from("10.0.0.1");

      RateLimitAssertions.assertAllowed(handler.tryConsume(context, RULE_SET));
      RateLimitAssertions.assertAllowedWithRemaining(handler.tryConsume(context, RULE_SET), 0);
      RateLimitAssertions.assertRejectedWithRetryAfter(
          handler.tryConsume(context, RULE_SET), Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("should keep one bucket per key, so callers do not share a quota")
    void shouldKeepOneBucketPerKey() {
      InMemoryRateLimitHandler handler = InMemoryRateLimitHandler.withRuleSet(twoPerMinutePerIp());

      handler.tryConsume(from("10.0.0.1"), RULE_SET);
      handler.tryConsume(from("10.0.0.1"), RULE_SET);
      RateLimitAssertions.assertRejected(handler.tryConsume(from("10.0.0.1"), RULE_SET));

      RateLimitAssertions.assertAllowed(handler.tryConsume(from("10.0.0.2"), RULE_SET));
      assertThat(handler.bucketCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("should report the band capacity and label of the binding band")
    void shouldReportLimitAndBand() {
      InMemoryRateLimitHandler handler = InMemoryRateLimitHandler.withRuleSet(twoPerMinutePerIp());

      RateLimitResponse response = handler.tryConsume(from("10.0.0.1"), RULE_SET);

      RateLimitAssertions.assertLimit(response, 2);
      RateLimitAssertions.assertBand(response, "2-per-60s");
      assertThat(response.getWindowSeconds()).isEqualTo(60L);
    }

    @Test
    @DisplayName("should support weighted permits")
    void shouldSupportWeightedPermits() {
      InMemoryRateLimitHandler handler = InMemoryRateLimitHandler.withRuleSet(twoPerMinutePerIp());
      RequestContext context = from("10.0.0.1");

      RateLimitAssertions.assertAllowedWithRemaining(handler.tryConsume(context, RULE_SET, 2L), 0);
      RateLimitAssertions.assertRejected(handler.tryConsume(context, RULE_SET, 1L));
    }

    @Test
    @DisplayName("should evaluate every band of a rule all-or-nothing")
    void shouldEvaluateEveryBandAllOrNothing() {
      RateLimitRuleSet ruleSet =
          FluxgateTestRules.rule("per-ip")
              .perIp()
              .band(Duration.ofMinutes(1), 3)
              .band(Duration.ofHours(1), 4)
              .toRuleSet(RULE_SET);
      InMemoryRateLimitHandler handler = InMemoryRateLimitHandler.withRuleSet(ruleSet);
      RequestContext context = from("10.0.0.1");

      // minute band 3 -> 1, hour band 4 -> 2
      RateLimitAssertions.assertAllowed(handler.tryConsume(context, RULE_SET, 2L));

      // The minute band cannot serve 2 permits, so these are rejected. The hour band must not be
      // charged for them: had it been, it would be empty and the next request rejected too.
      for (int i = 0; i < 3; i++) {
        RateLimitAssertions.assertRejected(handler.tryConsume(context, RULE_SET, 2L));
      }
      RateLimitAssertions.assertAllowed(handler.tryConsume(context, RULE_SET, 1L));

      // All bands of one rule share a single multi-bandwidth bucket per caller.
      assertThat(handler.bucketCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("should skip disabled rules")
    void shouldSkipDisabledRules() {
      RateLimitRuleSet ruleSet =
          FluxgateTestRules.rule("off")
              .perIp()
              .disabled()
              .band(Duration.ofMinutes(1), 1)
              .toRuleSet(RULE_SET);
      InMemoryRateLimitHandler handler = InMemoryRateLimitHandler.withRuleSet(ruleSet);
      RequestContext context = from("10.0.0.1");

      RateLimitAssertions.assertAllowedWithoutRule(handler.tryConsume(context, RULE_SET));
      RateLimitAssertions.assertAllowedWithoutRule(handler.tryConsume(context, RULE_SET));
    }

    @Test
    @DisplayName("should limit per user id under a PER_USER rule")
    void shouldLimitPerUserId() {
      RateLimitRuleSet ruleSet =
          FluxgateTestRules.rule("per-user")
              .perUser()
              .band(Duration.ofMinutes(1), 1)
              .toRuleSet(RULE_SET);
      InMemoryRateLimitHandler handler = InMemoryRateLimitHandler.withRuleSet(ruleSet);

      RequestContext alice = RequestContext.builder().clientIp("10.0.0.1").userId("alice").build();
      RequestContext bob = RequestContext.builder().clientIp("10.0.0.1").userId("bob").build();

      RateLimitAssertions.assertAllowed(handler.tryConsume(alice, RULE_SET));
      RateLimitAssertions.assertRejected(handler.tryConsume(alice, RULE_SET));
      RateLimitAssertions.assertAllowed(handler.tryConsume(bob, RULE_SET));
    }
  }

  @Nested
  @DisplayName("reset")
  class ResetTests {

    @Test
    @DisplayName("reset() should restore full buckets")
    void resetShouldRestoreFullBuckets() {
      InMemoryRateLimitHandler handler = InMemoryRateLimitHandler.withRuleSet(twoPerMinutePerIp());
      RequestContext context = from("10.0.0.1");

      handler.tryConsume(context, RULE_SET);
      handler.tryConsume(context, RULE_SET);
      RateLimitAssertions.assertRejected(handler.tryConsume(context, RULE_SET));

      handler.reset();

      assertThat(handler.bucketCount()).isZero();
      RateLimitAssertions.assertAllowed(handler.tryConsume(context, RULE_SET));
    }

    @Test
    @DisplayName("reset(ruleSetId) should leave the other rule set alone")
    void resetByRuleSetShouldLeaveOthersAlone() {
      RateLimitRuleSet first =
          FluxgateTestRules.rule("r").perIp().band(Duration.ofMinutes(1), 1).toRuleSet("first");
      RateLimitRuleSet second =
          FluxgateTestRules.rule("r").perIp().band(Duration.ofMinutes(1), 1).toRuleSet("second");
      InMemoryRateLimitHandler handler =
          InMemoryRateLimitHandler.withRuleSets(List.of(first, second));
      RequestContext context = from("10.0.0.1");

      handler.tryConsume(context, "first");
      handler.tryConsume(context, "second");

      handler.reset("first");

      RateLimitAssertions.assertAllowed(handler.tryConsume(context, "first"));
      RateLimitAssertions.assertRejected(handler.tryConsume(context, "second"));
    }
  }

  @Nested
  @DisplayName("configuration")
  class ConfigurationTests {

    @Test
    @DisplayName("should allow an unknown rule set by default")
    void shouldAllowUnknownRuleSetByDefault() {
      InMemoryRateLimitHandler handler = InMemoryRateLimitHandler.withRuleSet(twoPerMinutePerIp());

      RateLimitAssertions.assertAllowedWithoutRule(handler.tryConsume(from("10.0.0.1"), "nope"));
    }

    @Test
    @DisplayName("should reject an unknown rule set under DENY")
    void shouldRejectUnknownRuleSetUnderDeny() {
      InMemoryRateLimitHandler handler =
          InMemoryRateLimitHandler.builder()
              .ruleSet(twoPerMinutePerIp())
              .onMissingRuleSet(RateLimitEngine.OnMissingRuleSetStrategy.DENY)
              .build();

      RateLimitAssertions.assertRejected(handler.tryConsume(from("10.0.0.1"), "nope"));
    }

    @Test
    @DisplayName("should expose its rule set ids and a usable provider")
    void shouldExposeRuleSetIdsAndProvider() {
      InMemoryRateLimitHandler handler = InMemoryRateLimitHandler.withRuleSet(twoPerMinutePerIp());

      assertThat(handler.getRuleSetIds()).containsExactly(RULE_SET);
      assertThat(handler.getRuleSetProvider().findById(RULE_SET)).isPresent();
      assertThat(handler.getRuleSetProvider().findById("nope")).isEmpty();
      assertThat(handler.toString()).contains(RULE_SET);
    }

    @Test
    @DisplayName("should refuse to build without a rule set")
    void shouldRefuseToBuildWithoutRuleSet() {
      assertThatThrownBy(() -> InMemoryRateLimitHandler.builder().build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("at least one rule set");
    }
  }

  @Nested
  @DisplayName("concurrency")
  class ConcurrencyTests {

    @Test
    @DisplayName("should never allow more than the capacity under concurrent load")
    void shouldNeverAllowMoreThanCapacity() throws Exception {
      int capacity = 20;
      int threads = 16;
      int attemptsPerThread = 10;

      RateLimitRuleSet ruleSet =
          FluxgateTestRules.rule("per-ip")
              .perIp()
              .band(Duration.ofHours(1), capacity)
              .toRuleSet(RULE_SET);
      InMemoryRateLimitHandler handler = InMemoryRateLimitHandler.withRuleSet(ruleSet);
      RequestContext context = from("10.0.0.1");

      AtomicInteger allowed = new AtomicInteger();
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      try {
        for (int t = 0; t < threads; t++) {
          pool.submit(
              () -> {
                start.await();
                for (int i = 0; i < attemptsPerThread; i++) {
                  if (handler.tryConsume(context, RULE_SET).isAllowed()) {
                    allowed.incrementAndGet();
                  }
                }
                return null;
              });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
      } finally {
        pool.shutdownNow();
      }

      // A one hour window refills roughly one token every three minutes, so nothing is credited
      // back during the test: exactly the capacity may pass.
      assertThat(allowed.get()).isEqualTo(capacity);
    }
  }
}
