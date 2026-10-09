package org.fluxgate.spring.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.exception.RedisConnectionException;
import org.fluxgate.core.exception.RedisUnavailableException;
import org.fluxgate.core.exception.ScriptExecutionException;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.match.PathPatternMatcher;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.fluxgate.core.resilience.CircuitBreakerConfig;
import org.fluxgate.core.resilience.ResilientExecutor;
import org.fluxgate.core.resilience.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link ResilientRateLimiter}. */
class ResilientRateLimiterTest {

  private RequestContext context;
  private RateLimitRuleSet ruleSet;

  @BeforeEach
  void setUp() {
    context = RequestContext.builder().clientIp("10.0.0.1").endpoint("/api/orders").build();
    ruleSet =
        RateLimitRuleSet.builder("orders")
            .rules(
                List.of(
                    RateLimitRule.builder("orders-rule")
                        .name("orders")
                        .enabled(true)
                        .ruleSetId("orders")
                        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 5).build())
                        .build()))
            .keyResolver((ctx, rule) -> RateLimitKey.of("ip:" + ctx.getClientIp()))
            .build();
  }

  /** A resilient executor with retry and the circuit breaker switched off. */
  private static ResilientExecutor directExecutor() {
    return ResilientExecutor.disabled();
  }

  @Test
  void shouldDelegateToThePrimaryLimiterWhenItWorks() {
    RateLimitResult expected = RateLimitResult.allowedWithoutRule();
    ResilientRateLimiter limiter =
        new ResilientRateLimiter(
            (ctx, rules, permits) -> expected, directExecutor(), null, false, null);

    assertThat(limiter.tryConsume(context, ruleSet, 1L)).isEqualTo(expected);
  }

  @Test
  void shouldNotRechargeEarlierRulesWhenAScriptRejectsStalePolicy() {
    AtomicInteger earlierRuleCharges = new AtomicInteger();
    RateLimiter partiallyCharged =
        (request, rules, permits) -> {
          // A preceding matching rule already consumed before the later rule rejected stale policy.
          earlierRuleCharges.incrementAndGet();
          throw new ScriptExecutionException(
              "STALE_POLICY", "token_bucket_consume.lua", null, false);
        };
    ResilientExecutor executor =
        new ResilientExecutor(
            RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(2))
                .build(),
            CircuitBreakerConfig.disabled(),
            "policy-check");
    ResilientRateLimiter limiter =
        new ResilientRateLimiter(partiallyCharged, executor, null, false, null);
    RateLimitResult result = limiter.tryConsume(context, ruleSet, 1);
    assertThat(result.isAllowed()).isFalse();
    assertThat(result.getDecisionReason())
        .isEqualTo(RateLimitResult.DecisionReason.BACKEND_FAILURE);
    assertThat(earlierRuleCharges).hasValue(1);
  }

  @Nested
  class WhenThePrimaryLimiterFails {

    private final RateLimiter failing =
        (ctx, rules, permits) -> {
          throw new RedisConnectionException("redis is down");
        };

    @Test
    void shouldAllowWithoutRuleWhenFailureBehaviourIsAllow() {
      AtomicReference<String> action = new AtomicReference<>();
      ResilientRateLimiter limiter =
          new ResilientRateLimiter(
              failing,
              directExecutor(),
              null,
              true,
              (ruleSetId, endpoint, taken, cause) -> action.set(taken));

      RateLimitResult result = limiter.tryConsume(context, ruleSet, 1L);

      assertThat(result.isAllowed()).isTrue();
      assertThat(result.hasRule()).isFalse();
      assertThat(action).hasValue("fail_open");
    }

    @Test
    void shouldRejectWithoutWaitWhenFailureBehaviourIsDeny() {
      AtomicReference<String> action = new AtomicReference<>();
      AtomicReference<Throwable> reportedCause = new AtomicReference<>();
      ResilientRateLimiter limiter =
          new ResilientRateLimiter(
              failing,
              directExecutor(),
              null,
              false,
              (ruleSetId, endpoint, taken, cause) -> {
                action.set(taken);
                reportedCause.set(cause);
              });

      RateLimitResult result = limiter.tryConsume(context, ruleSet, 1L);

      assertThat(result.isAllowed()).isFalse();
      assertThat(result.getNanosToWaitForRefill()).isZero();
      assertThat(result.getKey().value()).contains("orders");
      assertThat(action).hasValue("fail_closed");
      assertThat(reportedCause.get()).isInstanceOf(RedisConnectionException.class);
    }

    @Test
    void shouldLimitInMemoryWhenAFallbackLimiterIsConfigured() {
      AtomicReference<String> action = new AtomicReference<>();
      ResilientRateLimiter limiter =
          new ResilientRateLimiter(
              failing,
              directExecutor(),
              new Bucket4jRateLimiter(100L, Duration.ofMinutes(1)),
              false,
              (ruleSetId, endpoint, taken, cause) -> action.set(taken));

      // Capacity is 5, so the first five calls pass locally and the sixth is limited - the point of
      // the fallback: the limit keeps being enforced per instance instead of disappearing.
      for (int i = 0; i < 5; i++) {
        assertThat(limiter.tryConsume(context, ruleSet, 1L).isAllowed()).isTrue();
      }
      assertThat(limiter.tryConsume(context, ruleSet, 1L).isAllowed()).isFalse();
      assertThat(action).hasValue("fallback_in_memory");
    }

    @Test
    void shouldApplyFailureBehaviourWhenTheFallbackLimiterAlsoFails() {
      ResilientRateLimiter limiter =
          new ResilientRateLimiter(
              failing,
              directExecutor(),
              (ctx, rules, permits) -> {
                throw new IllegalStateException("fallback broken too");
              },
              true,
              null);

      assertThat(limiter.tryConsume(context, ruleSet, 1L).isAllowed()).isTrue();
    }
  }

  @Test
  void shouldRetryThePrimaryLimiterBeforeDegrading() {
    AtomicInteger attempts = new AtomicInteger();
    RateLimiter flaky =
        (ctx, rules, permits) -> {
          if (attempts.incrementAndGet() < 3) {
            throw new RedisConnectionException("transient");
          }
          return RateLimitResult.allowedWithoutRule();
        };

    ResilientExecutor executor =
        new ResilientExecutor(
            RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(2))
                .build(),
            CircuitBreakerConfig.disabled(),
            "test");

    ResilientRateLimiter limiter = new ResilientRateLimiter(flaky, executor, null, false, null);

    assertThat(limiter.tryConsume(context, ruleSet, 1L).isAllowed()).isTrue();
    assertThat(attempts).hasValue(3);
  }

  @Test
  void shouldDegradeImmediatelyWhenRedisIsNotConnectedYet() {
    AtomicInteger attempts = new AtomicInteger();
    RateLimiter disconnected =
        (ctx, rules, permits) -> {
          attempts.incrementAndGet();
          throw new RedisUnavailableException("not connected yet: connect timed out");
        };

    ResilientExecutor executor =
        new ResilientExecutor(
            RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialBackoff(Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(2))
                .build(),
            CircuitBreakerConfig.disabled(),
            "test");

    AtomicReference<String> action = new AtomicReference<>();
    ResilientRateLimiter limiter =
        new ResilientRateLimiter(
            disconnected,
            executor,
            null,
            false,
            (ruleSetId, endpoint, taken, cause) -> action.set(taken));

    long start = System.nanoTime();
    RateLimitResult result = limiter.tryConsume(context, ruleSet, 1L);
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

    // The exception is non-retryable, so failure-behavior applies on the first attempt instead of
    // after three attempts and two backoff sleeps.
    assertThat(attempts).hasValue(1);
    assertThat(elapsedMillis).isLessThan(500L);
    assertThat(result.isAllowed()).isFalse();
    assertThat(action).hasValue("fail_closed");
  }

  @Test
  void shouldFallBackInMemoryImmediatelyWhenRedisIsNotConnectedYet() {
    AtomicInteger attempts = new AtomicInteger();
    RateLimiter disconnected =
        (ctx, rules, permits) -> {
          attempts.incrementAndGet();
          throw new RedisUnavailableException("not connected yet: connect timed out");
        };

    ResilientExecutor executor =
        new ResilientExecutor(
            RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialBackoff(Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(2))
                .build(),
            CircuitBreakerConfig.disabled(),
            "test");

    AtomicReference<String> action = new AtomicReference<>();
    ResilientRateLimiter limiter =
        new ResilientRateLimiter(
            disconnected,
            executor,
            new Bucket4jRateLimiter(100L, Duration.ofMinutes(1)),
            false,
            (ruleSetId, endpoint, taken, cause) -> action.set(taken));

    assertThat(limiter.tryConsume(context, ruleSet, 1L).isAllowed()).isTrue();
    assertThat(attempts).hasValue(1);
    assertThat(action).hasValue("fallback_in_memory");
  }

  @Test
  void shouldUseTheFallbackWhileTheCircuitIsOpenWithoutCallingThePrimary() {
    AtomicInteger primaryCalls = new AtomicInteger();
    RateLimiter failing =
        (ctx, rules, permits) -> {
          primaryCalls.incrementAndGet();
          throw new RedisConnectionException("redis is down");
        };

    ResilientExecutor executor =
        new ResilientExecutor(
            RetryConfig.disabled(),
            CircuitBreakerConfig.builder()
                .enabled(true)
                .failureThreshold(2)
                .minimumNumberOfCalls(2)
                .slidingWindowSize(4)
                .waitDurationInOpenState(Duration.ofMinutes(5))
                .build(),
            "test");

    AtomicInteger fallbackCalls = new AtomicInteger();
    RateLimiter fallback =
        (ctx, rules, permits) -> {
          fallbackCalls.incrementAndGet();
          return RateLimitResult.allowedWithoutRule();
        };

    ResilientRateLimiter limiter =
        new ResilientRateLimiter(failing, executor, fallback, false, null);

    for (int i = 0; i < 6; i++) {
      assertThat(limiter.tryConsume(context, ruleSet, 1L).isAllowed()).isTrue();
    }

    assertThat(fallbackCalls).hasValue(6);
    // Once the circuit opened, the primary stopped being called at all.
    assertThat(primaryCalls.get()).isLessThan(6);
  }

  @Nested
  class PathMatcherPropagation {

    private final PathPatternMatcher matcher = (pattern, path) -> true;

    /** Records the matcher it receives through the 4-arg overload; fails if it is dropped. */
    private RateLimiter recording(AtomicReference<PathPatternMatcher> seen, boolean fail) {
      return new RateLimiter() {
        @Override
        public RateLimitResult tryConsume(
            RequestContext ctx, RateLimitRuleSet rules, long permits) {
          throw new AssertionError("the path matcher was dropped");
        }

        @Override
        public RateLimitResult tryConsume(
            RequestContext ctx,
            RateLimitRuleSet rules,
            long permits,
            PathPatternMatcher pathMatcher) {
          seen.set(pathMatcher);
          if (fail) {
            throw new RedisConnectionException("redis is down");
          }
          return RateLimitResult.allowedWithoutRule();
        }
      };
    }

    @Test
    void shouldPassThePathMatcherToThePrimaryLimiter() {
      AtomicReference<PathPatternMatcher> seen = new AtomicReference<>();
      ResilientRateLimiter limiter =
          new ResilientRateLimiter(recording(seen, false), directExecutor(), null, false, null);

      assertThat(limiter.tryConsume(context, ruleSet, 1L, matcher).isAllowed()).isTrue();
      assertThat(seen.get()).isSameAs(matcher);
    }

    @Test
    void shouldPassThePathMatcherToTheFallbackLimiter() {
      AtomicReference<PathPatternMatcher> primarySeen = new AtomicReference<>();
      AtomicReference<PathPatternMatcher> fallbackSeen = new AtomicReference<>();
      ResilientRateLimiter limiter =
          new ResilientRateLimiter(
              recording(primarySeen, true),
              directExecutor(),
              recording(fallbackSeen, false),
              false,
              null);

      assertThat(limiter.tryConsume(context, ruleSet, 1L, matcher).isAllowed()).isTrue();
      assertThat(primarySeen.get()).isSameAs(matcher);
      assertThat(fallbackSeen.get()).isSameAs(matcher);
    }
  }
}
