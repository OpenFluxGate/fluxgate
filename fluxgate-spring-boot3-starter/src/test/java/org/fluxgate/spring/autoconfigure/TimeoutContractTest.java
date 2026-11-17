package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.fluxgate.core.config.*;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.exception.FluxgateTimeoutException;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.*;
import org.fluxgate.core.resilience.*;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.annotation.EnableFluxgateFilter;
import org.fluxgate.spring.filter.FluxgateRateLimitFilter;
import org.fluxgate.spring.handler.*;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.*;

class TimeoutContractTest {
  @Configuration(proxyBeanMethods = false)
  @EnableFluxgateFilter(ruleSetId = "orders")
  static class WebConfig {}

  static final RequestContext REQUEST =
      RequestContext.builder().clientIp("192.0.2.10").endpoint("/api").build();

  static RateLimitRuleSet rules() {
    return RateLimitRuleSet.builder("orders")
        .rules(
            List.of(
                RateLimitRule.builder("one")
                    .ruleSetId("orders")
                    .addBand(RateLimitBand.builder(Duration.ofHours(1), 6).build())
                    .build()))
        .keyResolver((c, r) -> RateLimitKey.of("test"))
        .build();
  }

  static class Delegate implements RateLimiter {
    final AtomicInteger calls = new AtomicInteger();

    public RateLimitResult tryConsume(RequestContext c, RateLimitRuleSet r, long p) {
      if (calls.incrementAndGet() == 1)
        throw new FluxgateTimeoutException("injected uncertain consumption");
      return RateLimitResult.builder(RateLimitKey.of("test"))
          .allowed(false)
          .limit(6)
          .remainingTokens(0)
          .matchedRule(r.getRules().get(0))
          .build();
    }
  }

  WebApplicationContextRunner runner(Delegate d, boolean resilience) {
    RateLimitRuleSetProvider provider = mock(RateLimitRuleSetProvider.class);
    when(provider.findById("orders")).thenReturn(Optional.of(rules()));
    when(provider.requiresFreshRead()).thenReturn(true);
    when(provider.usesPublishedCounterEpochs()).thenReturn(true);
    var r =
        new WebApplicationContextRunner()
            .withUserConfiguration(WebConfig.class)
            .withConfiguration(
                AutoConfigurations.of(
                    FluxgateRateLimiterAutoConfiguration.class,
                    FluxgateReloadAutoConfiguration.class,
                    FluxgateFilterAutoConfiguration.class))
            .withBean("fluxgateDelegateRateLimiter", RateLimiter.class, () -> d)
            .withBean("delegateRuleSetProvider", RateLimitRuleSetProvider.class, () -> provider)
            .withPropertyValues(
                "fluxgate.redis.enabled=true",
                "fluxgate.ratelimit.mode=REDIS",
                "fluxgate.reload.counter-mode=PUBLISHED_EPOCH",
                "fluxgate.reload.strategy=NONE",
                "fluxgate.reload.cache.enabled=false",
                "fluxgate.ratelimit.fallback.mode=NONE",
                "fluxgate.ratelimit.failure-behavior=DENY",
                "fluxgate.ratelimit.missing-rule-behavior=DENY");
    return resilience
        ? r.withConfiguration(AutoConfigurations.of(FluxgateResilienceAutoConfiguration.class))
        : r;
  }

  @Test
  void rawEnginePropagatesWithoutAutomaticReconsume() {
    var d = new Delegate();
    runner(d, false)
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(c.getBean(RateLimiter.class)).isSameAs(d);
              assertThatThrownBy(() -> c.getBean(RateLimitEngine.class).check("orders", REQUEST, 1))
                  .isInstanceOf(FluxgateTimeoutException.class);
              assertThat(d.calls).hasValue(1);
              assertThat(
                      c.getBean(RateLimitEngine.class)
                          .check("orders", REQUEST, 1)
                          .getDecisionReason())
                  .isEqualTo(RateLimitResult.DecisionReason.QUOTA);
              assertThat(d.calls).hasValue(2);
            });
  }

  @Test
  void fullWrapperDenyPreservesSingleAttemptAndNextIndependentQuota() {
    var d = new Delegate();
    runner(d, true)
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(c.getBean(RateLimiter.class)).isInstanceOf(ResilientRateLimiter.class);
              assertThat(
                      c.getBean(ResilientExecutor.class)
                          .getRetryExecutor()
                          .getConfig()
                          .isRetryOnTimeout())
                  .isFalse();
              assertThat(c.getBean(FluxgateProperties.class).getRedis().getTimeoutMs())
                  .isEqualTo(5000);
              assertThatThrownBy(() -> c.getBean(RateLimitEngine.class).check("orders", REQUEST, 1))
                  .isInstanceOf(RateLimiterUnavailableException.class)
                  .hasCauseInstanceOf(FluxgateTimeoutException.class);
              assertThat(d.calls).hasValue(1);
              assertThat(
                      c.getBean(RateLimitEngine.class)
                          .check("orders", REQUEST, 1)
                          .getDecisionReason())
                  .isEqualTo(RateLimitResult.DecisionReason.QUOTA);
              assertThat(d.calls).hasValue(2);
            });
  }

  @Test
  void actualFilterOnMockHttpReturns503WithoutInvokingChain() {
    var d = new Delegate();
    runner(d, true)
        .withPropertyValues("fluxgate.redis.timeout-ms=1000")
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(c.getBean(FluxgateProperties.class).getRedis().getTimeoutMs())
                  .isEqualTo(1000);
              var req = new MockHttpServletRequest("GET", "/api");
              var res = new MockHttpServletResponse();
              var chain = new MockFilterChain();
              c.getBean(FluxgateRateLimitFilter.class).doFilter(req, res, chain);
              assertThat(res.getStatus()).isEqualTo(503);
              assertThat(chain.getRequest()).isNull();
              assertThat(d.calls).hasValue(1);
            });
  }

  @Test
  void epochRejectsExplicitTimeoutRetry() {
    runner(new Delegate(), true)
        .withPropertyValues("fluxgate.resilience.retry.retry-on-timeout=true")
        .run(
            c -> {
              assertThat(c).hasFailed();
              assertThat(c.getStartupFailure())
                  .hasStackTraceContaining("PUBLISHED_EPOCH forbids retry-on-timeout");
            });
  }

  @Test
  void epochRejectsConfiguredTimeoutRetryEvenWithoutExecutor() {
    runner(new Delegate(), false)
        .withPropertyValues(
            "fluxgate.resilience.retry.enabled=false",
            "fluxgate.resilience.retry.retry-on-timeout=true")
        .run(
            c -> {
              assertThat(c).hasFailed();
              assertThat(c.getStartupFailure())
                  .hasStackTraceContaining("PUBLISHED_EPOCH forbids retry-on-timeout");
            });
  }

  @Test
  void epochRejectsEffectiveCustomExecutorDespiteSafeProperty() {
    runner(new Delegate(), true)
        .withBean(
            ResilientExecutor.class,
            () ->
                ResilientExecutor.withRetryOnly(RetryConfig.builder().retryOnTimeout(true).build()))
        .run(
            c -> {
              assertThat(c).hasFailed();
              assertThat(c.getStartupFailure())
                  .hasStackTraceContaining("PUBLISHED_EPOCH forbids retry-on-timeout");
            });
  }

  @Test
  void legacyPreservesExplicitTimeoutRetry() {
    runner(new Delegate(), true)
        .withPropertyValues(
            "fluxgate.reload.counter-mode=LEGACY_RESET",
            "fluxgate.resilience.retry.retry-on-timeout=true")
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(
                      c.getBean(ResilientExecutor.class)
                          .getRetryExecutor()
                          .getConfig()
                          .isRetryOnTimeout())
                  .isTrue();
            });
  }

  @Test
  void epochRejectsTimeoutRetryEvenWhenRetryDisabled() {
    runner(new Delegate(), true)
        .withPropertyValues(
            "fluxgate.resilience.retry.enabled=false",
            "fluxgate.resilience.retry.retry-on-timeout=true")
        .run(
            c -> {
              assertThat(c).hasFailed();
              assertThat(c.getStartupFailure())
                  .hasStackTraceContaining("PUBLISHED_EPOCH forbids retry-on-timeout");
            });
  }

  @Test
  void epochChecksPrimaryExecutorInsteadOfSafeAlternative() {
    runner(new Delegate(), true)
        .withBean("safeExecutor", ResilientExecutor.class, ResilientExecutor::disabled)
        .withBean(
            "runtimeExecutor",
            ResilientExecutor.class,
            () ->
                ResilientExecutor.withRetryOnly(RetryConfig.builder().retryOnTimeout(true).build()),
            definition -> definition.setPrimary(true))
        .run(
            c -> {
              assertThat(c).hasFailed();
              assertThat(c.getStartupFailure())
                  .hasStackTraceContaining("PUBLISHED_EPOCH forbids retry-on-timeout");
            });
  }

  @Test
  void epochRejectsCustomRetryConfigDespiteSafeProperty() {
    runner(new Delegate(), true)
        .withBean(RetryConfig.class, () -> RetryConfig.builder().retryOnTimeout(true).build())
        .run(
            c -> {
              assertThat(c).hasFailed();
              assertThat(c.getStartupFailure())
                  .hasStackTraceContaining("PUBLISHED_EPOCH forbids retry-on-timeout");
            });
  }

  @Test
  void defaultLegacyModeRemainsCompatible() {
    runner(new Delegate(), true)
        .withPropertyValues("fluxgate.reload.counter-mode=LEGACY_RESET")
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(
                      c.getBean(ResilientExecutor.class)
                          .getRetryExecutor()
                          .getConfig()
                          .isRetryOnTimeout())
                  .isFalse();
            });
  }

  @Test
  void epochRejectsCustomRetryExecutorDespiteSafeConfig() {
    runner(new Delegate(), true)
        .withBean(
            RetryExecutor.class,
            () -> new DefaultRetryExecutor(RetryConfig.builder().retryOnTimeout(true).build()))
        .run(
            c -> {
              assertThat(c).hasFailed();
              assertThat(c.getStartupFailure())
                  .hasStackTraceContaining("PUBLISHED_EPOCH forbids retry-on-timeout");
            });
  }
}
