package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.exception.MissingConfigurationException;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.key.MissingKeyBehavior;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.handler.EngineBackedRateLimitHandler;
import org.fluxgate.spring.handler.LazyRedisRateLimiter;
import org.fluxgate.spring.handler.MissingRuleSetProviderRateLimitHandler;
import org.fluxgate.spring.handler.RedisConnectionState;
import org.fluxgate.spring.handler.ResilientRateLimiter;
import org.fluxgate.spring.reload.handler.InMemoryBucketResetHandler;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tests for {@link FluxgateRateLimiterAutoConfiguration}.
 *
 * <p>Covers the matrix that decides whether {@code @EnableFluxgateFilter} actually limits anything:
 * which limiter is selected, whether it is wrapped for resilience, and whether the library handler
 * is registered. Before this configuration existed, a project that forgot to write its own handler
 * got a single WARN line and unlimited traffic.
 *
 * <p>No Redis is needed: the Redis branch points at a closed port on purpose, which is also the
 * regression for "a Redis outage must not stop the context from starting".
 */
class FluxgateRateLimiterAutoConfigurationTest {

  /** A rule set provider that knows one rule set, so the engine and handler can be created. */
  @Configuration(proxyBeanMethods = false)
  static class RuleSetProviderConfig {

    @Bean
    RateLimitRuleSetProvider delegateRuleSetProvider() {
      return ruleSetId ->
          "orders".equals(ruleSetId)
              ? Optional.of(
                  RateLimitRuleSet.builder("orders")
                      .rules(
                          List.of(
                              RateLimitRule.builder("orders-rule")
                                  .name("orders")
                                  .enabled(true)
                                  .ruleSetId("orders")
                                  .scope(LimitScope.PER_IP)
                                  .addBand(
                                      RateLimitBand.builder(Duration.ofMinutes(1), 2)
                                          .label("per-minute")
                                          .build())
                                  .build()))
                      .keyResolver(new LimitScopeKeyResolver())
                      .build())
              : Optional.empty();
    }
  }

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  FluxgateResilienceAutoConfiguration.class,
                  FluxgateRedisAutoConfiguration.class,
                  FluxgateRateLimiterAutoConfiguration.class));

  @Nested
  class LimiterSelection {

    @Test
    void shouldUseTheInMemoryLimiterWhenRedisIsNotEnabled() {
      contextRunner.run(
          context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("fluxgateDelegateRateLimiter"))
                .isInstanceOf(Bucket4jRateLimiter.class);
          });
    }

    @Test
    void shouldUseTheInMemoryLimiterWhenModeIsInMemoryEvenWithRedisEnabled() {
      contextRunner
          .withPropertyValues(
              "fluxgate.ratelimit.mode=IN_MEMORY",
              "fluxgate.redis.enabled=true",
              "fluxgate.redis.uri=redis://localhost:1",
              "fluxgate.redis.timeout-ms=200")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean("fluxgateDelegateRateLimiter"))
                    .isInstanceOf(Bucket4jRateLimiter.class);
              });
    }

    @Test
    void shouldAcceptTheRelaxedSpellingOfTheInMemoryMode() {
      contextRunner
          .withPropertyValues(
              "fluxgate.ratelimit.mode=in-memory",
              "fluxgate.redis.enabled=true",
              "fluxgate.redis.uri=redis://localhost:1",
              "fluxgate.redis.timeout-ms=200")
          .run(
              context ->
                  assertThat(context.getBean("fluxgateDelegateRateLimiter"))
                      .isInstanceOf(Bucket4jRateLimiter.class));
    }

    @Test
    void shouldStartWithTheRedisLimiterEvenWhenRedisIsUnreachable() {
      contextRunner
          .withPropertyValues(
              "fluxgate.redis.enabled=true",
              "fluxgate.redis.uri=redis://localhost:1",
              "fluxgate.redis.timeout-ms=200")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean("fluxgateDelegateRateLimiter"))
                    .isInstanceOf(LazyRedisRateLimiter.class);
                assertThat(context.getBean(RedisConnectionState.class).isReady()).isFalse();
                assertThat(context.getBean(RedisConnectionState.class).getFailedAttempts())
                    .isPositive();
              });
    }

    @Test
    void shouldBackOffEntirelyWhenTheApplicationSuppliesItsOwnLimiter() {
      RateLimiter custom = (ctx, ruleSet, permits) -> null;
      contextRunner
          .withBean("customRateLimiter", RateLimiter.class, () -> custom)
          .run(
              context -> {
                assertThat(context).doesNotHaveBean("fluxgateDelegateRateLimiter");
                assertThat(context).doesNotHaveBean(ResilientRateLimiter.class);
              });
    }
  }

  @Nested
  class ResilienceWiring {

    @Test
    void shouldWrapTheLimiterByDefaultBecauseRetryIsEnabled() {
      contextRunner.run(
          context -> {
            assertThat(context).hasSingleBean(ResilientRateLimiter.class);
            assertThat(context.getBean(ResilientRateLimiter.class).getFallbackLimiter()).isNull();
          });
    }

    @Test
    void shouldNotWrapTheLimiterWhenResilienceAndFallbackAreBothOff() {
      contextRunner
          .withPropertyValues(
              "fluxgate.resilience.retry.enabled=false",
              "fluxgate.resilience.circuit-breaker.enabled=false")
          .run(
              context -> {
                assertThat(context).doesNotHaveBean(ResilientRateLimiter.class);
                assertThat(context).hasSingleBean(RateLimiter.class);
              });
    }

    @Test
    void shouldCreateAnInMemoryFallbackLimiterWhenFallbackModeIsInMemory() {
      contextRunner
          .withPropertyValues(
              "fluxgate.resilience.retry.enabled=false",
              "fluxgate.ratelimit.fallback.mode=IN_MEMORY",
              "fluxgate.redis.enabled=true",
              "fluxgate.redis.uri=redis://localhost:1",
              "fluxgate.redis.timeout-ms=200")
          .run(
              context -> {
                ResilientRateLimiter limiter = context.getBean(ResilientRateLimiter.class);
                assertThat(limiter.getDelegate()).isInstanceOf(LazyRedisRateLimiter.class);
                assertThat(limiter.getFallbackLimiter()).isInstanceOf(Bucket4jRateLimiter.class);
              });
    }

    @Test
    void shouldExposeTheFallbackLimiterAsItsOwnBeanSoResetHandlersCanFindIt() {
      // The fallback used to be an inline instance, invisible to every BucketResetHandler, so its
      // local buckets kept enforcing superseded bands until they expired.
      contextRunner
          .withPropertyValues(
              "fluxgate.ratelimit.fallback.mode=IN_MEMORY",
              "fluxgate.redis.enabled=true",
              "fluxgate.redis.uri=redis://localhost:1",
              "fluxgate.redis.timeout-ms=200")
          .run(
              context -> {
                assertThat(context).hasSingleBean(Bucket4jRateLimiter.class);
                assertThat(context).hasBean("fluxgateFallbackRateLimiter");
                assertThat(context.getBean(ResilientRateLimiter.class).getFallbackLimiter())
                    .isSameAs(context.getBean("fluxgateFallbackRateLimiter"));
              });
    }

    @Test
    void shouldStillResolveRateLimiterUniquelyWithAFallbackBeanPresent() {
      contextRunner
          .withPropertyValues(
              "fluxgate.ratelimit.fallback.mode=IN_MEMORY",
              "fluxgate.redis.enabled=true",
              "fluxgate.redis.uri=redis://localhost:1",
              "fluxgate.redis.timeout-ms=200")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                // Three RateLimiter beans now exist; @Primary must still pick the wrapper.
                assertThat(context.getBean(RateLimiter.class))
                    .isInstanceOf(ResilientRateLimiter.class);
              });
    }

    @Test
    void shouldAcceptTheRelaxedSpellingOfTheInMemoryFallbackMode() {
      contextRunner
          .withPropertyValues(
              "fluxgate.ratelimit.fallback.mode=in-memory",
              "fluxgate.redis.enabled=true",
              "fluxgate.redis.uri=redis://localhost:1",
              "fluxgate.redis.timeout-ms=200")
          .run(
              context ->
                  assertThat(context.getBean(ResilientRateLimiter.class).getFallbackLimiter())
                      .isInstanceOf(Bucket4jRateLimiter.class));
    }

    @Test
    void shouldNotRegisterAFallbackBeanWhenFallbackModeIsNone() {
      contextRunner
          .withPropertyValues(
              "fluxgate.redis.enabled=true",
              "fluxgate.redis.uri=redis://localhost:1",
              "fluxgate.redis.timeout-ms=200")
          .run(context -> assertThat(context).doesNotHaveBean("fluxgateFallbackRateLimiter"));
    }

    @Test
    void shouldNotDuplicateAnAlreadyInMemoryPrimaryAsItsOwnFallback() {
      contextRunner
          .withPropertyValues("fluxgate.ratelimit.fallback.mode=IN_MEMORY")
          .run(
              context ->
                  assertThat(context.getBean(ResilientRateLimiter.class).getFallbackLimiter())
                      .isNull());
    }

    @Test
    void shouldRejectWhenTheLimiterFailsAndFailureBehaviourIsDeny() {
      contextRunner
          .withUserConfiguration(RuleSetProviderConfig.class)
          .withPropertyValues(
              "fluxgate.ratelimit.failure-behavior=DENY",
              "fluxgate.resilience.retry.max-attempts=1",
              "fluxgate.redis.enabled=true",
              "fluxgate.redis.uri=redis://localhost:1",
              "fluxgate.redis.timeout-ms=200")
          .run(
              context -> {
                RateLimitResponse response =
                    context
                        .getBean(FluxgateRateLimitHandler.class)
                        .tryConsume(
                            RequestContext.builder().clientIp("10.0.0.1").build(), "orders");
                assertThat(response.isAllowed()).isFalse();
              });
    }

    @Test
    void shouldAllowWhenTheLimiterFailsAndFailureBehaviourIsAllow() {
      contextRunner
          .withUserConfiguration(RuleSetProviderConfig.class)
          .withPropertyValues(
              "fluxgate.ratelimit.failure-behavior=ALLOW",
              "fluxgate.resilience.retry.max-attempts=1",
              "fluxgate.redis.enabled=true",
              "fluxgate.redis.uri=redis://localhost:1",
              "fluxgate.redis.timeout-ms=200")
          .run(
              context -> {
                RateLimitResponse response =
                    context
                        .getBean(FluxgateRateLimitHandler.class)
                        .tryConsume(
                            RequestContext.builder().clientIp("10.0.0.1").build(), "orders");
                assertThat(response.isAllowed()).isTrue();
              });
    }
  }

  @Nested
  class HandlerRegistration {

    @Test
    void shouldRegisterTheLibraryHandlerWhenALimiterAndAProviderExist() {
      contextRunner
          .withUserConfiguration(RuleSetProviderConfig.class)
          .run(
              context -> {
                assertThat(context).hasSingleBean(RateLimitEngine.class);
                assertThat(context).hasSingleBean(FluxgateRateLimitHandler.class);
                assertThat(context.getBean(FluxgateRateLimitHandler.class))
                    .isInstanceOf(EngineBackedRateLimitHandler.class);
              });
    }

    @Test
    void shouldNotRegisterAHandlerWithoutARuleSetProviderOrAnyFluxgateConfiguration() {
      // No fluxgate.redis.enabled and no fluxgate.ratelimit.mode: nothing says this application
      // expects limiting, so the starter stays out of the way entirely.
      contextRunner.run(
          context -> {
            assertThat(context).doesNotHaveBean(RateLimitEngine.class);
            assertThat(context).doesNotHaveBean(FluxgateRateLimitHandler.class);
          });
    }

    @Test
    void shouldRegisterTheStopgapHandlerWhenAModeIsChosenButNoProviderExists() {
      // An explicit fluxgate.ratelimit.mode says limiting is expected, even without Redis.
      contextRunner
          .withPropertyValues("fluxgate.ratelimit.mode=IN_MEMORY")
          .run(
              context -> {
                assertThat(context).doesNotHaveBean(RateLimitEngine.class);
                assertThat(context.getBean(FluxgateRateLimitHandler.class))
                    .isInstanceOf(MissingRuleSetProviderRateLimitHandler.class);
              });
    }

    @Test
    void shouldKeepAnApplicationProvidedHandler() {
      FluxgateRateLimitHandler custom = (ctx, ruleSetId) -> RateLimitResponse.allowed(7, 0);
      contextRunner
          .withUserConfiguration(RuleSetProviderConfig.class)
          .withBean("customHandler", FluxgateRateLimitHandler.class, () -> custom)
          .run(
              context ->
                  assertThat(context.getBean(FluxgateRateLimitHandler.class)).isSameAs(custom));
    }

    @Test
    void shouldLimitEndToEndThroughTheLibraryHandler() {
      contextRunner
          .withUserConfiguration(RuleSetProviderConfig.class)
          .run(
              context -> {
                FluxgateRateLimitHandler handler = context.getBean(FluxgateRateLimitHandler.class);
                RequestContext request = RequestContext.builder().clientIp("10.0.0.1").build();

                // Capacity is 2 per minute.
                assertThat(handler.tryConsume(request, "orders").isAllowed()).isTrue();
                assertThat(handler.tryConsume(request, "orders").isAllowed()).isTrue();
                assertThat(handler.tryConsume(request, "orders").isAllowed()).isFalse();
              });
    }
  }

  /**
   * Redis enabled, MongoDB disabled: a limiter exists but nothing can supply rules.
   *
   * <p>This combination used to produce no handler at all, so the filter fell through to its own
   * no-handler path and reported a missing {@code RateLimiter} - the one bean that did exist -
   * while silently allowing or rejecting every request.
   */
  @Nested
  class RedisOnlyWithoutARuleSetProvider {

    private final ApplicationContextRunner redisOnlyRunner =
        contextRunner.withPropertyValues(
            "fluxgate.redis.enabled=true",
            "fluxgate.redis.uri=redis://localhost:1",
            "fluxgate.redis.timeout-ms=200",
            "fluxgate.mongo.enabled=false");

    @Test
    void shouldRegisterTheNamedStopgapHandler() {
      redisOnlyRunner.run(
          context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RateLimitEngine.class);
            assertThat(context).hasSingleBean(FluxgateRateLimitHandler.class);
            assertThat(context.getBean(FluxgateRateLimitHandler.class))
                .isInstanceOf(MissingRuleSetProviderRateLimitHandler.class);
          });
    }

    @Test
    void shouldRejectByDefaultBecauseFailureBehaviourIsDeny() {
      redisOnlyRunner.run(
          context -> {
            RateLimitResponse response =
                context
                    .getBean(FluxgateRateLimitHandler.class)
                    .tryConsume(RequestContext.builder().clientIp("10.0.0.1").build(), "orders");
            assertThat(response.isAllowed()).isFalse();
            assertThat(response.getRetryAfterMillis()).isZero();
          });
    }

    @Test
    void shouldAllowWhenFailureBehaviourIsAllow() {
      redisOnlyRunner
          .withPropertyValues("fluxgate.ratelimit.failure-behavior=ALLOW")
          .run(
              context -> {
                RateLimitResponse response =
                    context
                        .getBean(FluxgateRateLimitHandler.class)
                        .tryConsume(
                            RequestContext.builder().clientIp("10.0.0.1").build(), "orders");
                assertThat(response.isAllowed()).isTrue();
              });
    }

    @Test
    void shouldAlsoApplyToWeightedPermits() {
      redisOnlyRunner.run(
          context ->
              assertThat(
                      context
                          .getBean(FluxgateRateLimitHandler.class)
                          .tryConsume(
                              RequestContext.builder().clientIp("10.0.0.1").build(), "orders", 5L)
                          .isAllowed())
                  .isFalse());
    }

    @Test
    void shouldFailStartupWhenFailOnMissingHandlerIsSet() {
      redisOnlyRunner
          .withPropertyValues("fluxgate.ratelimit.fail-on-missing-handler=true")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(MissingConfigurationException.class)
                    .hasMessageContaining("no RateLimitRuleSetProvider");
              });
    }

    @Test
    void shouldStandDownOnceARuleSetProviderAppears() {
      redisOnlyRunner
          .withUserConfiguration(RuleSetProviderConfig.class)
          .run(
              context -> {
                assertThat(context.getBean(FluxgateRateLimitHandler.class))
                    .isInstanceOf(EngineBackedRateLimitHandler.class);
                assertThat(context).doesNotHaveBean(MissingRuleSetProviderRateLimitHandler.class);
              });
    }

    @Test
    void shouldStandDownForAnApplicationProvidedHandler() {
      FluxgateRateLimitHandler custom = (ctx, ruleSetId) -> RateLimitResponse.allowed(7, 0);
      redisOnlyRunner
          .withBean("customHandler", FluxgateRateLimitHandler.class, () -> custom)
          .run(
              context ->
                  assertThat(context.getBean(FluxgateRateLimitHandler.class)).isSameAs(custom));
    }
  }

  /**
   * How the in-memory fallback limiter reaches a bucket reset handler.
   *
   * <p>Exposing it as {@code fluxgateFallbackRateLimiter} is what makes {@code
   * inMemoryBucketResetHandler} - conditional on a {@link Bucket4jRateLimiter} bean - able to see
   * it at all. With Redis as the primary limiter the reload configuration still registers only one
   * reset handler, so the fallback is reachable but not yet reset; that second half is tracked as
   * the composite reset-handler refactor.
   */
  @Nested
  class FallbackLimiterResetWiring {

    private final ApplicationContextRunner reloadRunner =
        new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    FluxgateResilienceAutoConfiguration.class,
                    FluxgateRedisAutoConfiguration.class,
                    FluxgateRateLimiterAutoConfiguration.class,
                    FluxgateReloadAutoConfiguration.class))
            .withUserConfiguration(RuleSetProviderConfig.class)
            .withPropertyValues(
                "fluxgate.redis.enabled=true",
                "fluxgate.redis.uri=redis://localhost:1",
                "fluxgate.redis.timeout-ms=200",
                // Polling keeps the test off the network; only the reset wiring matters here.
                "fluxgate.reload.strategy=POLLING",
                "fluxgate.reload.polling.interval=1h",
                "fluxgate.ratelimit.fallback.mode=IN_MEMORY");

    @Test
    void shouldMakeTheFallbackLimiterVisibleToTheInMemoryResetHandlerCondition() {
      reloadRunner.run(
          context -> {
            assertThat(context).hasNotFailed();
            // The condition InMemoryBucketResetHandler is gated on now has a bean to match.
            assertThat(context).hasSingleBean(Bucket4jRateLimiter.class);
            assertThat(context.getBean(Bucket4jRateLimiter.class))
                .isSameAs(context.getBean("fluxgateFallbackRateLimiter"));
          });
    }

    @Test
    void shouldResetTheFallbackLimiterWhenAResetHandlerReachesIt() {
      // The reset contract itself, independent of which handler the reload configuration picks.
      reloadRunner.run(
          context -> {
            Bucket4jRateLimiter fallback = context.getBean(Bucket4jRateLimiter.class);
            RequestContext request = RequestContext.builder().clientIp("10.0.0.1").build();
            RateLimitRuleSet ruleSet =
                context.getBean(RateLimitRuleSetProvider.class).findById("orders").orElseThrow();

            // Capacity is 2 per minute.
            assertThat(fallback.tryConsume(request, ruleSet, 1L).isAllowed()).isTrue();
            assertThat(fallback.tryConsume(request, ruleSet, 1L).isAllowed()).isTrue();
            assertThat(fallback.tryConsume(request, ruleSet, 1L).isAllowed()).isFalse();

            new InMemoryBucketResetHandler(fallback).resetBuckets("orders");

            assertThat(fallback.tryConsume(request, ruleSet, 1L).isAllowed()).isTrue();
          });
    }
  }

  @Nested
  class MissingRuleBehaviour {

    @Test
    void shouldRejectAnUnknownRuleSetWhenMissingRuleBehaviorIsDeny() {
      contextRunner
          .withUserConfiguration(RuleSetProviderConfig.class)
          .withPropertyValues("fluxgate.ratelimit.missing-rule-behavior=DENY")
          .run(
              context -> {
                RateLimitResponse response =
                    context
                        .getBean(FluxgateRateLimitHandler.class)
                        .tryConsume(
                            RequestContext.builder().clientIp("10.0.0.1").build(), "unknown");
                assertThat(response.isAllowed()).isFalse();
                assertThat(response.getRetryAfterMillis()).isZero();
              });
    }

    @Test
    void shouldAllowAnUnknownRuleSetWhenMissingRuleBehaviorIsAllow() {
      contextRunner
          .withUserConfiguration(RuleSetProviderConfig.class)
          .withPropertyValues("fluxgate.ratelimit.missing-rule-behavior=ALLOW")
          .run(
              context -> {
                RateLimitResponse response =
                    context
                        .getBean(FluxgateRateLimitHandler.class)
                        .tryConsume(
                            RequestContext.builder().clientIp("10.0.0.1").build(), "unknown");
                assertThat(response.isAllowed()).isTrue();
              });
    }
  }

  @Nested
  class KeyResolverWiring {

    @Test
    void shouldBuildTheKeyResolverFromMissingKeyBehavior() {
      contextRunner
          .withPropertyValues("fluxgate.ratelimit.missing-key-behavior=REJECT")
          .run(
              context -> {
                KeyResolver resolver = context.getBean(KeyResolver.class);
                assertThat(resolver).isInstanceOf(LimitScopeKeyResolver.class);
                assertThat(((LimitScopeKeyResolver) resolver).getMissingKeyBehavior())
                    .isEqualTo(MissingKeyBehavior.REJECT);
              });
    }

    @Test
    void shouldDefaultToFallbackToIp() {
      contextRunner.run(
          context ->
              assertThat(
                      ((LimitScopeKeyResolver) context.getBean(KeyResolver.class))
                          .getMissingKeyBehavior())
                  .isEqualTo(MissingKeyBehavior.FALLBACK_TO_IP));
    }
  }
}
