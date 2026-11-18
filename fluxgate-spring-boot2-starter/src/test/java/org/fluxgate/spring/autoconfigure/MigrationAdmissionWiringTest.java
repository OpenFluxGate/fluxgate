package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.fluxgate.core.config.*;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.*;
import org.fluxgate.core.handler.*;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.*;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.annotation.EnableFluxgateFilter;
import org.fluxgate.spring.filter.FluxgateRateLimitFilter;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.*;

class MigrationAdmissionWiringTest {
  @Configuration(proxyBeanMethods = false)
  @EnableFluxgateFilter(ruleSetId = "orders")
  static class WebConfig {}

  static final RequestContext REQUEST =
      RequestContext.builder().clientIp("192.0.2.10").endpoint("/api").build();

  static RateLimitRuleSet rules(String epoch, long revision) {
    return RateLimitRuleSet.builder("orders")
        .rules(
            List.of(
                RateLimitRule.builder("one")
                    .ruleSetId("orders")
                    .attributes(
                        Map.of(
                            "fluxgate.counterEpoch", epoch, "fluxgate.counterRevision", revision))
                    .addBand(RateLimitBand.builder(Duration.ofHours(1), 6).build())
                    .build()))
        .keyResolver((c, r) -> RateLimitKey.of("test"))
        .build();
  }

  WebApplicationContextRunner runner(RateLimitRuleSetProvider provider, RateLimiter limiter) {
    return new WebApplicationContextRunner()
        .withUserConfiguration(WebConfig.class)
        .withConfiguration(
            AutoConfigurations.of(
                FluxgateRateLimiterAutoConfiguration.class,
                FluxgateResilienceAutoConfiguration.class,
                FluxgateFilterAutoConfiguration.class))
        .withBean("fluxgateDelegateRateLimiter", RateLimiter.class, () -> limiter)
        .withBean("delegateRuleSetProvider", RateLimitRuleSetProvider.class, () -> provider)
        .withPropertyValues(
            "fluxgate.reload.enabled=false",
            "fluxgate.migration-admission.enabled=true",
            "fluxgate.ratelimit.failure-behavior=DENY",
            "fluxgate.ratelimit.fallback.mode=NONE");
  }

  WebApplicationContextRunner approved(RateLimitRuleSetProvider provider, RateLimiter limiter) {
    return runner(provider, limiter)
        .withPropertyValues(
            "fluxgate.migration-admission.approvals[0].rule-set-id=orders",
            "fluxgate.migration-admission.approvals[0].revision=2",
            "fluxgate.migration-admission.approvals[0].epoch=new-epoch");
  }

  @Test
  void exactBindingProtectsEngineWithReloadDisabled() {
    var calls = new AtomicInteger();
    approved(
            id -> Optional.of(rules("new-epoch", 2)),
            (c, r, p) -> {
              calls.incrementAndGet();
              return RateLimitResult.allowedWithoutRule();
            })
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              var engine = c.getBean(RateLimitEngine.class);
              assertThat(engine.getMigrationAdmissionGate().isEnabled()).isTrue();
              assertThat(engine.check("orders", REQUEST).isAllowed()).isTrue();
              assertThat(calls).hasValue(1);
              c.getBean(FluxgateProperties.class)
                  .getMigrationAdmission()
                  .getApprovals()
                  .get(0)
                  .setEpoch("mutated");
              assertThat(engine.check("orders", REQUEST).isAllowed()).isTrue();
            });
  }

  @Test
  void legacyAndNewRevisionReturn503WithoutChainOrConsumption() {
    for (var snapshot : List.of(rules("legacy", 2), rules("new-epoch", 3))) {
      var limiter = mock(RateLimiter.class);
      approved(id -> Optional.of(snapshot), limiter)
          .run(
              c -> {
                assertThat(c).hasNotFailed();
                var res = new MockHttpServletResponse();
                var chain = new MockFilterChain();
                c.getBean(FluxgateRateLimitFilter.class)
                    .doFilter(new MockHttpServletRequest("GET", "/api"), res, chain);
                assertThat(res.getStatus()).isEqualTo(503);
                assertThat(chain.getRequest()).isNull();
                verifyNoInteractions(limiter);
              });
    }
  }

  @Test
  void missingAndProviderErrorsReturnExplicit503() {
    for (boolean error : List.of(false, true)) {
      var limiter = mock(RateLimiter.class);
      RateLimitRuleSetProvider provider =
          id -> {
            if (error) throw new IllegalStateException("lookup failed");
            return Optional.empty();
          };
      approved(provider, limiter)
          .run(
              c -> {
                assertThat(c).hasNotFailed();
                var res = new MockHttpServletResponse();
                var chain = new MockFilterChain();
                c.getBean(FluxgateRateLimitFilter.class)
                    .doFilter(new MockHttpServletRequest("GET", "/api"), res, chain);
                assertThat(res.getStatus()).isEqualTo(503);
                assertThat(chain.getRequest()).isNull();
                verifyNoInteractions(limiter);
              });
    }
  }

  @Test
  void enabledEmptyListDeniesBeforeProviderRead() {
    var provider = mock(RateLimitRuleSetProvider.class);
    var limiter = mock(RateLimiter.class);
    runner(provider, limiter)
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThatThrownBy(() -> c.getBean(RateLimitEngine.class).check("orders", REQUEST))
                  .isInstanceOf(org.fluxgate.core.exception.SnapshotAdmissionDeniedException.class);
              verifyNoInteractions(provider, limiter);
            });
  }

  @Test
  void disabledPreservesLegacyAndFailOpenConfiguration() {
    runner(id -> Optional.of(rules("legacy", 2)), (c, r, p) -> RateLimitResult.allowedWithoutRule())
        .withPropertyValues(
            "fluxgate.migration-admission.enabled=false",
            "fluxgate.ratelimit.failure-behavior=ALLOW")
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(c.getBean(RateLimitEngine.class).getMigrationAdmissionGate().isEnabled())
                  .isFalse();
              assertThat(c.getBean(RateLimitEngine.class).check("orders", REQUEST).isAllowed())
                  .isTrue();
            });
  }

  @Test
  void enabledRejectsFailOpenOrLocalFallback() {
    for (String setting :
        List.of(
            "fluxgate.ratelimit.failure-behavior=ALLOW",
            "fluxgate.ratelimit.fallback.mode=IN_MEMORY")) {
      approved(id -> Optional.of(rules("new-epoch", 2)), mock(RateLimiter.class))
          .withPropertyValues(setting)
          .run(
              c -> {
                assertThat(c).hasFailed();
                assertThat(c.getStartupFailure())
                    .hasStackTraceContaining("Migration admission requires failure-behavior=DENY");
              });
    }
  }

  @Test
  void selectedPrimaryCustomEngineMustCarryExactGate() {
    var provider = (RateLimitRuleSetProvider) id -> Optional.of(rules("new-epoch", 2));
    var limiter = mock(RateLimiter.class);
    var unsafe = RateLimitEngine.builder().ruleSetProvider(provider).rateLimiter(limiter).build();
    var safe =
        RateLimitEngine.builder()
            .ruleSetProvider(provider)
            .rateLimiter(limiter)
            .migrationAdmissionGate(
                MigrationAdmissionGate.enabled(
                    Map.of("orders", new MigrationAdmissionGate.Approval(2, "new-epoch"))))
            .build();
    approved(provider, limiter)
        .withBean("safeEngine", RateLimitEngine.class, () -> safe)
        .withBean("runtimeEngine", RateLimitEngine.class, () -> unsafe, d -> d.setPrimary(true))
        .run(
            c -> {
              assertThat(c).hasFailed();
              assertThat(c.getStartupFailure())
                  .hasStackTraceContaining("selected engine to carry the exact gate");
            });
  }

  @Test
  void selectedMatchingCustomEngineIsAccepted() {
    var provider = (RateLimitRuleSetProvider) id -> Optional.of(rules("new-epoch", 2));
    var limiter = (RateLimiter) (c, r, p) -> RateLimitResult.allowedWithoutRule();
    var safe =
        RateLimitEngine.builder()
            .ruleSetProvider(provider)
            .rateLimiter(limiter)
            .migrationAdmissionGate(
                MigrationAdmissionGate.enabled(
                    Map.of("orders", new MigrationAdmissionGate.Approval(2, "new-epoch"))))
            .build();
    approved(provider, limiter)
        .withBean(RateLimitEngine.class, () -> safe)
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(c.getBean(RateLimitEngine.class)).isSameAs(safe);
            });
  }

  @Test
  void customHandlerCannotBypassGate() {
    approved(id -> Optional.of(rules("new-epoch", 2)), mock(RateLimiter.class))
        .withBean(FluxgateRateLimitHandler.class, () -> (c, id) -> RateLimitResponse.allowed(1, 0))
        .run(
            c -> {
              assertThat(c).hasFailed();
              assertThat(c.getStartupFailure()).hasStackTraceContaining("engine-backed handlers");
            });
  }

  @Test
  void duplicateAndMalformedApprovalsFailStartup() {
    for (String bad :
        List.of(
            "fluxgate.migration-admission.approvals[0].revision=1.5",
            "fluxgate.migration-admission.approvals[0].revision=0",
            "fluxgate.migration-admission.approvals[0].epoch=legacy",
            "fluxgate.migration-admission.approvals[0].epoch= ",
            "fluxgate.migration-admission.approvals[0].rule-set-id= ")) {
      approved(id -> Optional.empty(), mock(RateLimiter.class))
          .withPropertyValues(bad)
          .run(c -> assertThat(c).hasFailed());
    }
    approved(id -> Optional.empty(), mock(RateLimiter.class))
        .withPropertyValues(
            "fluxgate.migration-admission.approvals[1].rule-set-id=orders",
            "fluxgate.migration-admission.approvals[1].revision=3",
            "fluxgate.migration-admission.approvals[1].epoch=next")
        .run(
            c -> {
              assertThat(c).hasFailed();
              assertThat(c.getStartupFailure())
                  .hasStackTraceContaining("Duplicate migration approval");
            });
  }

  @Test
  void enabledRejectsNullApprovalsAndNullEntries() {
    var config = new FluxgateProperties.MigrationAdmissionProperties();
    config.setEnabled(true);
    config.setApprovals(null);
    assertThatThrownBy(config::toGate).isInstanceOf(NullPointerException.class);
    config.setApprovals(java.util.Arrays.asList((FluxgateProperties.SnapshotApproval) null));
    assertThatThrownBy(config::toGate).isInstanceOf(NullPointerException.class);
    var empty = new FluxgateProperties.SnapshotApproval();
    config.setApprovals(List.of(empty));
    assertThatThrownBy(config::toGate).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void knownGateDenialOverridesDirectFilterFailOpen() throws Exception {
    for (boolean providerError : List.of(false, true)) {
      var limiter = mock(RateLimiter.class);
      RateLimitRuleSetProvider provider =
          id -> {
            if (providerError) throw new IllegalStateException("lookup failed");
            return Optional.of(rules("legacy", 2));
          };
      var engine =
          RateLimitEngine.builder()
              .ruleSetProvider(provider)
              .rateLimiter(limiter)
              .migrationAdmissionGate(
                  MigrationAdmissionGate.enabled(
                      Map.of("orders", new MigrationAdmissionGate.Approval(2, "new-epoch"))))
              .build();
      var filter =
          new FluxgateRateLimitFilter(
              new org.fluxgate.spring.handler.EngineBackedRateLimitHandler(engine),
              "orders",
              new String[] {"/**"},
              new String[] {},
              false,
              0,
              1,
              null,
              "X-Forwarded-For",
              false,
              true);
      var res = new MockHttpServletResponse();
      var chain = new MockFilterChain();
      filter.doFilter(new MockHttpServletRequest("GET", "/api"), res, chain);
      assertThat(res.getStatus()).isEqualTo(503);
      assertThat(chain.getRequest()).isNull();
      verifyNoInteractions(limiter);
    }
  }
}
