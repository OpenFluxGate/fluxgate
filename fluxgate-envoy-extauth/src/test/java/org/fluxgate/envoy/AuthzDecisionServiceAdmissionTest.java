package org.fluxgate.envoy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.junit.jupiter.api.Test;

class AuthzDecisionServiceAdmissionTest {
  private final RequestContext request =
      RequestContext.builder().clientIp("127.0.0.1").endpoint("/api/items").method("GET").build();

  private final RateLimitRule rule =
      RateLimitRule.builder("one")
          .name("one")
          .ruleSetId("pilot")
          .addBand(RateLimitBand.builder(Duration.ofSeconds(60), 1).build())
          .build();
  private final RateLimitRuleSet snapshot =
      RateLimitRuleSet.builder("pilot")
          .rules(List.of(rule))
          .keyResolver(new LimitScopeKeyResolver())
          .build();

  private AuthzDecisionService service(
      RateLimitRuleSetProvider provider, RateLimiter limiter, int maximum) {
    RateLimitEngine engine =
        RateLimitEngine.builder().ruleSetProvider(provider).rateLimiter(limiter).build();
    return new AuthzDecisionService(engine, provider, "pilot", maximum);
  }

  private RateLimitResult allowed() {
    return RateLimitResult.allowed(RateLimitKey.of("global"), rule, 0, 0);
  }

  @Test
  void defaultLimitRejectsBeforeReadingAnotherSnapshotOrConsuming() throws Exception {
    RateLimitRule rule =
        RateLimitRule.builder("one")
            .name("one")
            .ruleSetId("pilot")
            .addBand(RateLimitBand.builder(Duration.ofSeconds(60), 1).build())
            .build();
    RateLimitRuleSet snapshot =
        RateLimitRuleSet.builder("pilot")
            .rules(List.of(rule))
            .keyResolver(new LimitScopeKeyResolver())
            .build();
    CountDownLatch entered = new CountDownLatch(32);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger reads = new AtomicInteger();
    AtomicInteger consumed = new AtomicInteger();
    RateLimitRuleSetProvider provider =
        id -> {
          if (reads.incrementAndGet() <= 32) {
            entered.countDown();
            await(release);
          }
          return Optional.of(snapshot);
        };
    RateLimitEngine engine =
        RateLimitEngine.builder()
            .ruleSetProvider(provider)
            .rateLimiter(
                (ctx, rs, permits) -> {
                  consumed.incrementAndGet();
                  return RateLimitResult.allowed(RateLimitKey.of("global"), rule, 0, 0);
                })
            .build();
    AuthzDecisionService service = new AuthzDecisionService(engine, provider, "pilot");
    ExecutorService pool = Executors.newCachedThreadPool();
    List<Future<AuthzDecision>> admitted = new java.util.ArrayList<>();
    try {
      for (int i = 0; i < 32; i++) {
        admitted.add(pool.submit(() -> service.decide(request)));
      }
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      Future<AuthzDecision> rejected = pool.submit(() -> service.decide(request));
      assertThat(rejected.get(1, TimeUnit.SECONDS).status()).isEqualTo(503);
      assertThat(reads).hasValue(32);
      assertThat(consumed).hasValue(0);
      release.countDown();
      for (Future<AuthzDecision> decision : admitted) {
        assertThat(decision.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
      }
      assertThat(service.decide(request).status()).isEqualTo(200);
      assertThat(reads).hasValue(33);
      assertThat(consumed).hasValue(33);
    } finally {
      release.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void configuredLimitRejectsWhileSnapshotReadsAreBlocked() throws Exception {
    assertConfiguredAdmission(true);
  }

  @Test
  void configuredLimitRejectsWhileConsumptionIsBlocked() throws Exception {
    assertConfiguredAdmission(false);
  }

  private void assertConfiguredAdmission(boolean blockProvider) throws Exception {
    CountDownLatch entered = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger reads = new AtomicInteger();
    AtomicInteger consumed = new AtomicInteger();
    AuthzDecisionService service =
        service(
            id -> {
              int count = reads.incrementAndGet();
              if (blockProvider && count <= 2) {
                entered.countDown();
                await(release);
              }
              return Optional.of(snapshot);
            },
            (ctx, rs, permits) -> {
              int count = consumed.incrementAndGet();
              if (!blockProvider && count <= 2) {
                entered.countDown();
                await(release);
              }
              return allowed();
            },
            2);
    ExecutorService pool = Executors.newCachedThreadPool();
    try {
      Future<AuthzDecision> first = pool.submit(() -> service.decide(request));
      Future<AuthzDecision> second = pool.submit(() -> service.decide("pilot", request, 1L));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      Future<AuthzDecision> rejected = pool.submit(() -> service.decide(request));
      assertThat(rejected.get(1, TimeUnit.SECONDS).status()).isEqualTo(503);
      assertThat(reads).hasValue(2);
      assertThat(consumed).hasValue(blockProvider ? 0 : 2);
      if (!blockProvider) {
        assertThat(service.isReady("pilot")).isTrue();
        assertThat(reads).hasValue(3);
      }
      release.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
      assertThat(second.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
      assertThat(service.decide(request).status()).isEqualTo(200);
      assertThat(reads).hasValue(blockProvider ? 3 : 4);
      assertThat(consumed).hasValue(3);
    } finally {
      release.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void runtimeExceptionsAndErrorsReleaseAdmissionAtEitherBackendBoundary() {
    for (boolean providerFailure : new boolean[] {true, false}) {
      for (boolean error : new boolean[] {true, false}) {
        AtomicInteger attempts = new AtomicInteger();
        Runnable failOnce =
            () -> {
              if (attempts.incrementAndGet() == 1) {
                if (error) {
                  throw new AssertionError("backend error");
                }
                throw new IllegalStateException("backend unavailable");
              }
            };
        AuthzDecisionService service =
            service(
                id -> {
                  if (providerFailure) {
                    failOnce.run();
                  }
                  return Optional.of(snapshot);
                },
                (ctx, rs, permits) -> {
                  if (!providerFailure) {
                    failOnce.run();
                  }
                  return allowed();
                },
                1);
        if (error) {
          assertThatThrownBy(() -> service.decide(request)).isInstanceOf(AssertionError.class);
        } else {
          assertThat(service.decide(request).status()).isEqualTo(503);
        }
        assertThat(service.decide(request).status()).isEqualTo(200);
        assertThat(attempts).hasValue(2);
      }
    }
  }

  @Test
  void earlyRejectionReleasesAdmissionWithoutConsumption() {
    AtomicInteger reads = new AtomicInteger();
    AtomicInteger consumed = new AtomicInteger();
    AuthzDecisionService service =
        service(
            id -> reads.incrementAndGet() == 1 ? Optional.empty() : Optional.of(snapshot),
            (ctx, rs, permits) -> {
              consumed.incrementAndGet();
              return allowed();
            },
            1);
    assertThat(service.decide("pilot", request, 0L).status()).isEqualTo(503);
    assertThat(reads).hasValue(0);
    assertThat(service.decide(request).status()).isEqualTo(503);
    assertThat(consumed).hasValue(0);
    assertThat(service.decide(request).status()).isEqualTo(200);
    assertThat(reads).hasValue(2);
    assertThat(consumed).hasValue(1);
  }

  @Test
  void nonPositiveAdmissionLimitsCannotStartAnAlwaysRejectedService() {
    for (int maximum : new int[] {0, -1}) {
      assertThatThrownBy(
              () -> service(id -> Optional.of(snapshot), (ctx, rs, permits) -> allowed(), maximum))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("blocked decision was not released");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("blocked decision interrupted", e);
    }
  }
}
