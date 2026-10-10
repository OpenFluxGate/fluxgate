package org.fluxgate.spring.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.exception.InvalidRuleConfigException;
import org.fluxgate.core.exception.RedisConnectionException;
import org.fluxgate.core.exception.RedisUnavailableException;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.match.PathPatternMatcher;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.fluxgate.core.resilience.CircuitBreaker;
import org.fluxgate.core.resilience.CircuitBreakerConfig;
import org.fluxgate.core.resilience.DefaultCircuitBreaker;
import org.fluxgate.core.resilience.NoOpRetryExecutor;
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

      // Item 10: rejected as "unavailable" (HTTP 503), not as an exceeded limit.
      assertThatThrownBy(() -> limiter.tryConsume(context, ruleSet, 1L))
          .isInstanceOf(RateLimiterUnavailableException.class)
          .hasMessageContaining("orders")
          .hasCauseInstanceOf(RedisConnectionException.class);
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

    @Test
    void aFailureRecorderThrowingACheckedExceptionDoesNotChangeTheDegradedDecision() {
      ResilientRateLimiter.FailureRecorder broken =
          (ruleSetId, endpoint, taken, cause) ->
              sneakyThrow(new IOException("metrics backend down"));

      ResilientRateLimiter failOpen =
          new ResilientRateLimiter(failing, directExecutor(), null, true, broken);
      assertThat(failOpen.tryConsume(context, ruleSet, 1L).isAllowed()).isTrue();
    }

    @Test
    void aFailingFailureRecorderDoesNotChangeTheDegradedDecision() {
      ResilientRateLimiter.FailureRecorder broken =
          (ruleSetId, endpoint, taken, cause) -> {
            throw new IllegalStateException("metrics backend down");
          };

      ResilientRateLimiter failOpen =
          new ResilientRateLimiter(failing, directExecutor(), null, true, broken);
      assertThat(failOpen.tryConsume(context, ruleSet, 1L).isAllowed()).isTrue();

      ResilientRateLimiter failClosed =
          new ResilientRateLimiter(failing, directExecutor(), null, false, broken);
      assertThatThrownBy(() -> failClosed.tryConsume(context, ruleSet, 1L))
          .isInstanceOf(RateLimiterUnavailableException.class);

      // The fallback decision stands and its tokens are charged once per call: with capacity 5
      // the sixth call is limited, not degraded to failure-behavior.
      ResilientRateLimiter fallback =
          new ResilientRateLimiter(
              failing,
              directExecutor(),
              new Bucket4jRateLimiter(100L, Duration.ofMinutes(1)),
              false,
              broken);
      for (int i = 0; i < 5; i++) {
        assertThat(fallback.tryConsume(context, ruleSet, 1L).isAllowed()).isTrue();
      }
      RateLimitResult sixth = fallback.tryConsume(context, ruleSet, 1L);
      assertThat(sixth.isAllowed()).isFalse();
      assertThat(sixth.hasRule()).isTrue();
    }
  }

  @Test
  void shouldRetryThePrimaryLimiterBeforeDegrading() {
    AtomicInteger attempts = new AtomicInteger();
    RateLimiter flaky =
        (ctx, rules, permits) -> {
          if (attempts.incrementAndGet() < 3) {
            // a connect-phase failure never reached Redis, so it is safe to retry
            throw new RedisConnectionException(
                "transient", null, RedisConnectionException.Phase.CONNECT);
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
  void shouldNotRetryACommandPhaseFailureAndAnswerUnavailable() {
    AtomicInteger attempts = new AtomicInteger();
    RateLimiter failingMidCommand =
        (ctx, rules, permits) -> {
          attempts.incrementAndGet();
          // Redis may already have consumed the tokens: retrying would charge the request twice.
          throw new RedisConnectionException(
              "connection reset during EVALSHA", null, RedisConnectionException.Phase.COMMAND);
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

    ResilientRateLimiter limiter =
        new ResilientRateLimiter(failingMidCommand, executor, null, false, null);

    assertThatThrownBy(() -> limiter.tryConsume(context, ruleSet, 1L))
        .isInstanceOf(RateLimiterUnavailableException.class)
        .hasCauseInstanceOf(RedisConnectionException.class);
    assertThat(attempts).hasValue(1);
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
    assertThatThrownBy(() -> limiter.tryConsume(context, ruleSet, 1L))
        .isInstanceOf(RateLimiterUnavailableException.class);
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

    // The exception is non-retryable, so failure-behavior applies on the first attempt instead of
    // after three attempts and two backoff sleeps.
    assertThat(attempts).hasValue(1);
    assertThat(elapsedMillis).isLessThan(500L);
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
  class ClientErrors {

    private final PathPatternMatcher anyPath = (pattern, path) -> true;
    private final AtomicInteger fallbackCalls = new AtomicInteger();
    private final AtomicInteger recordedFailures = new AtomicInteger();

    private final RateLimiter countingFallback =
        (ctx, rules, permits) -> {
          fallbackCalls.incrementAndGet();
          return RateLimitResult.allowedWithoutRule();
        };

    /** A breaker that opens after two failures and stays open long enough to be observed. */
    private ResilientExecutor breakerExecutor() {
      return new ResilientExecutor(
          RetryConfig.builder().maxAttempts(3).initialBackoff(Duration.ZERO).build(),
          CircuitBreakerConfig.builder()
              .enabled(true)
              .failureThreshold(2)
              .minimumNumberOfCalls(2)
              .slidingWindowSize(4)
              .waitDurationInOpenState(Duration.ofMinutes(5))
              .build(),
          "client-errors");
    }

    private ResilientRateLimiter limiter(
        RateLimiter primary, ResilientExecutor executor, boolean allow) {
      return new ResilientRateLimiter(
          primary,
          executor,
          countingFallback,
          allow,
          (ruleSetId, endpoint, action, cause) -> recordedFailures.incrementAndGet());
    }

    @Test
    void oversizedCostsNeverOpenTheBreakerOrReachTheFallback() {
      AtomicInteger primaryCalls = new AtomicInteger();
      Bucket4jRateLimiter inMemory = new Bucket4jRateLimiter(100L, Duration.ofMinutes(1));
      RateLimiter primary =
          new RateLimiter() {
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
              primaryCalls.incrementAndGet();
              return inMemory.tryConsume(ctx, rules, permits, pathMatcher);
            }
          };
      ResilientExecutor executor = breakerExecutor();
      ResilientRateLimiter limiter = limiter(primary, executor, true);

      for (int i = 0; i < 50; i++) {
        assertThatThrownBy(() -> limiter.tryConsume(context, ruleSet, 6L, anyPath))
            .isInstanceOfSatisfying(
                PermitsExceedCapacityException.class,
                e -> {
                  assertThat(e.getPermits()).isEqualTo(6L);
                  assertThat(e.getCapacity()).isEqualTo(5L);
                  assertThat(e.getRuleId()).isEqualTo("orders-rule");
                });
      }

      assertThat(executor.getCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
      assertThat(primaryCalls).hasValue(0);
      assertThat(fallbackCalls).hasValue(0);
      assertThat(recordedFailures).hasValue(0);
      // and well-sized requests are still limited by the primary limiter
      assertThat(limiter.tryConsume(context, ruleSet, 5L, anyPath).isAllowed()).isTrue();
      assertThat(primaryCalls).hasValue(1);
    }

    @Test
    void limiterConfigurationErrorsAreRethrownWithoutRetryBreakerOrFallback() {
      AtomicInteger primaryCalls = new AtomicInteger();
      InvalidRuleConfigException invalid =
          new InvalidRuleConfigException(
              "permits (6) exceed the capacity of band 'b' (5)", "orders-rule");
      RateLimiter primary =
          (ctx, rules, permits) -> {
            primaryCalls.incrementAndGet();
            throw invalid;
          };
      ResilientExecutor executor = breakerExecutor();
      ResilientRateLimiter limiter = limiter(primary, executor, false);

      // the 3-arg overload has no matcher, so only the limiter's own check can catch the cost
      for (int i = 0; i < 20; i++) {
        assertThatThrownBy(() -> limiter.tryConsume(context, ruleSet, 6L)).isSameAs(invalid);
      }

      assertThat(executor.getCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
      assertThat(primaryCalls).as("never retried").hasValue(20);
      assertThat(fallbackCalls).hasValue(0);
      assertThat(recordedFailures).hasValue(0);
    }

    @Test
    void illegalArgumentsAreRethrownWithoutDegrading() {
      IllegalArgumentException invalid = new IllegalArgumentException("permits must be > 0");
      ResilientExecutor executor = breakerExecutor();
      ResilientRateLimiter limiter =
          limiter(
              (ctx, rules, permits) -> {
                throw invalid;
              },
              executor,
              true);

      for (int i = 0; i < 5; i++) {
        assertThatThrownBy(() -> limiter.tryConsume(context, ruleSet, 1L)).isSameAs(invalid);
      }
      assertThat(executor.getCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
      assertThat(fallbackCalls).hasValue(0);
    }

    /** A primary limiter that throws whatever {@code next} holds. */
    private RateLimiter throwing(AtomicReference<RuntimeException> next) {
      return (ctx, rules, permits) -> {
        throw next.get();
      };
    }

    @Test
    void clientErrorsDoNotResetTheConsecutiveFailureCountOfAClosedBreaker() {
      DefaultCircuitBreaker breaker =
          new DefaultCircuitBreaker(
              "closed",
              CircuitBreakerConfig.builder()
                  .enabled(true)
                  .failureThreshold(3)
                  .minimumNumberOfCalls(100)
                  .slidingWindowSize(100)
                  .waitDurationInOpenState(Duration.ofMinutes(5))
                  .build());
      ResilientExecutor executor = new ResilientExecutor(NoOpRetryExecutor.getInstance(), breaker);
      AtomicReference<RuntimeException> next =
          new AtomicReference<>(new RedisUnavailableException("redis is down"));
      ResilientRateLimiter limiter = limiter(throwing(next), executor, true);

      limiter.tryConsume(context, ruleSet, 1L);
      limiter.tryConsume(context, ruleSet, 1L);
      assertThat(breaker.getFailureCount()).isEqualTo(2);

      IllegalArgumentException invalid = new IllegalArgumentException("permits must be > 0");
      next.set(invalid);
      assertThatThrownBy(() -> limiter.tryConsume(context, ruleSet, 1L)).isSameAs(invalid);
      assertThat(breaker.getFailureCount()).as("not a success").isEqualTo(2);
      assertThat(breaker.getRecordedCalls()).as("not recorded at all").isEqualTo(2);

      next.set(new RedisUnavailableException("redis is down"));
      limiter.tryConsume(context, ruleSet, 1L);
      assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void clientErrorsDoNotCloseAHalfOpenBreaker() throws InterruptedException {
      DefaultCircuitBreaker breaker =
          new DefaultCircuitBreaker(
              "half-open",
              CircuitBreakerConfig.builder()
                  .enabled(true)
                  .failureThreshold(2)
                  .minimumNumberOfCalls(2)
                  .slidingWindowSize(4)
                  .permittedCallsInHalfOpenState(2)
                  .waitDurationInOpenState(Duration.ofMillis(50))
                  .build());
      ResilientExecutor executor = new ResilientExecutor(NoOpRetryExecutor.getInstance(), breaker);
      AtomicReference<RuntimeException> next =
          new AtomicReference<>(new RedisUnavailableException("redis is down"));
      ResilientRateLimiter limiter = limiter(throwing(next), executor, true);

      limiter.tryConsume(context, ruleSet, 1L);
      limiter.tryConsume(context, ruleSet, 1L);
      assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
      Thread.sleep(100);

      InvalidRuleConfigException invalid =
          new InvalidRuleConfigException("permits exceed capacity", "orders-rule");
      next.set(invalid);
      // more client errors than trial permits: none of them counts as a successful trial
      for (int i = 0; i < 4; i++) {
        assertThatThrownBy(() -> limiter.tryConsume(context, ruleSet, 1L)).isSameAs(invalid);
      }
      assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
      assertThat(fallbackCalls).hasValue(2);
    }

    @Test
    void limiterFailuresStillOpenTheBreaker() {
      ResilientExecutor executor = breakerExecutor();
      ResilientRateLimiter limiter =
          limiter(
              (ctx, rules, permits) -> {
                throw new RedisUnavailableException("redis is down");
              },
              executor,
              true);

      for (int i = 0; i < 3; i++) {
        assertThat(limiter.tryConsume(context, ruleSet, 1L).isAllowed()).isTrue();
      }
      assertThat(executor.getCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
      assertThat(fallbackCalls).hasValue(3);
    }

    @Test
    void clientErrorsBypassTheFallbackOfABreakerThatDoesNotKnowIgnoredCalls() {
      // a custom breaker that answers every RuntimeException - IgnoredCallException included -
      // with the fallback, as the CircuitBreaker contract allows for implementations unaware of it
      AtomicInteger breakerFallbacks = new AtomicInteger();
      CircuitBreaker unaware =
          new CircuitBreaker() {
            @Override
            public <T> T execute(Supplier<T> action) {
              return action.get();
            }

            @Override
            public <T> T execute(String operationName, Supplier<T> action) {
              return action.get();
            }

            @Override
            public <T> T executeWithFallback(Supplier<T> action, Supplier<T> fallback) {
              try {
                return action.get();
              } catch (RuntimeException e) {
                breakerFallbacks.incrementAndGet();
                return fallback.get();
              }
            }

            @Override
            public State getState() {
              return State.CLOSED;
            }

            @Override
            public CircuitBreakerConfig getConfig() {
              return CircuitBreakerConfig.disabled();
            }

            @Override
            public void reset() {}
          };
      ResilientExecutor executor = new ResilientExecutor(NoOpRetryExecutor.getInstance(), unaware);
      IllegalArgumentException invalid = new IllegalArgumentException("permits must be > 0");
      ResilientRateLimiter limiter =
          limiter(
              (ctx, rules, permits) -> {
                throw invalid;
              },
              executor,
              true);

      assertThatThrownBy(() -> limiter.tryConsume(context, ruleSet, 1L)).isSameAs(invalid);
      assertThat(breakerFallbacks).as("the breaker did fall back").hasValue(1);
      assertThat(fallbackCalls).as("the fallback limiter was not used").hasValue(0);
      assertThat(recordedFailures).as("no degradation was recorded").hasValue(0);
    }
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

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
    throw (T) t;
  }
}
