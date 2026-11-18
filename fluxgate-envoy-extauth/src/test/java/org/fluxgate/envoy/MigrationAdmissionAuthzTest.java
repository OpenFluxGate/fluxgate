package org.fluxgate.envoy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.*;
import org.fluxgate.core.config.*;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.*;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.match.CidrSet;
import org.fluxgate.core.ratelimiter.*;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.junit.jupiter.api.Test;

/** Real engine and AuthzDecisionService, no network server or Redis. */
class MigrationAdmissionAuthzTest {
  private static final RequestContext REQUEST =
      RequestContext.builder().clientIp("192.0.2.10").endpoint("/api").build();

  private static RateLimitRuleSet snapshot(String epoch) {
    return RateLimitRuleSet.builder("orders")
        .keyResolver((c, r) -> RateLimitKey.of("test"))
        .accessControl(
            AccessControl.builder().allowedIps(CidrSet.ofStrict(List.of("192.0.2.10/32"))).build())
        .rules(
            List.of(
                RateLimitRule.builder("one")
                    .ruleSetId("orders")
                    .addBand(RateLimitBand.builder(Duration.ofHours(1), 6).build())
                    .attributes(
                        Map.of("fluxgate.counterRevision", 2L, "fluxgate.counterEpoch", epoch))
                    .build()))
        .build();
  }

  @Test
  void directSnapshotLegacyAclBypassIs503WithoutConsuming() {
    RateLimiter limiter = mock(RateLimiter.class);
    RateLimitRuleSetProvider provider = id -> Optional.of(snapshot("legacy"));
    var gate =
        MigrationAdmissionGate.enabled(
            Map.of("orders", new MigrationAdmissionGate.Approval(2, "explicit-epoch")));
    var engine =
        RateLimitEngine.builder()
            .ruleSetProvider(provider)
            .rateLimiter(limiter)
            .migrationAdmissionGate(gate)
            .build();
    var authz = new AuthzDecisionService(engine, provider, "orders", 1);
    assertThat(authz.isReady("orders")).isFalse();
    assertThat(authz.decide(REQUEST).status()).isEqualTo(503);
    verifyNoInteractions(limiter);
    assertThat(authz.diagnostics().runtimeException()).isEqualTo(1);
  }

  @Test
  void approvedSnapshotRetainsAclBypassWithoutConsumption() {
    RateLimiter limiter = mock(RateLimiter.class);
    RateLimitRuleSetProvider provider = id -> Optional.of(snapshot("explicit-epoch"));
    var gate =
        MigrationAdmissionGate.enabled(
            Map.of("orders", new MigrationAdmissionGate.Approval(2, "explicit-epoch")));
    var engine =
        RateLimitEngine.builder()
            .ruleSetProvider(provider)
            .rateLimiter(limiter)
            .migrationAdmissionGate(gate)
            .build();
    var service = new AuthzDecisionService(engine, provider, "orders", 1);
    assertThat(service.isReady("orders")).isTrue();
    assertThat(service.decide(REQUEST).status()).isEqualTo(200);
    verifyNoInteractions(limiter);
  }

  @Test
  void defaultOffPreservesLegacyAclBypass() {
    RateLimiter limiter = mock(RateLimiter.class);
    RateLimitRuleSetProvider provider = id -> Optional.of(snapshot("legacy"));
    var engine = RateLimitEngine.builder().ruleSetProvider(provider).rateLimiter(limiter).build();
    var service = new AuthzDecisionService(engine, provider, "orders", 1);
    assertThat(service.isReady("orders")).isTrue();
    assertThat(service.decide(REQUEST).status()).isEqualTo(200);
    verifyNoInteractions(limiter);
  }

  @Test
  void requestedIdMismatchDeniesEvenWhenBothSnapshotsAreApproved() {
    RateLimiter limiter = mock(RateLimiter.class);
    RateLimitRuleSetProvider provider = id -> Optional.of(snapshot("explicit-epoch"));
    var gate =
        MigrationAdmissionGate.enabled(
            Map.of(
                "requested", new MigrationAdmissionGate.Approval(2, "explicit-epoch"),
                "orders", new MigrationAdmissionGate.Approval(2, "explicit-epoch")));
    var engine =
        RateLimitEngine.builder()
            .ruleSetProvider(provider)
            .rateLimiter(limiter)
            .migrationAdmissionGate(gate)
            .build();
    var service = new AuthzDecisionService(engine, provider, "requested", 1);
    assertThat(service.isReady("requested")).isFalse();
    assertThat(service.decide(REQUEST).status()).isEqualTo(503);
    verifyNoInteractions(limiter);
  }

  @Test
  void defaultOffPreservesRequestedIdMismatchBehavior() {
    RateLimiter limiter = mock(RateLimiter.class);
    RateLimitRuleSetProvider provider = id -> Optional.of(snapshot("explicit-epoch"));
    var engine = RateLimitEngine.builder().ruleSetProvider(provider).rateLimiter(limiter).build();
    var service = new AuthzDecisionService(engine, provider, "requested", 1);
    assertThat(service.isReady("requested")).isTrue();
    assertThat(service.decide(REQUEST).status()).isEqualTo(200);
    verifyNoInteractions(limiter);
  }

  @Test
  void emptyAllowlistOrMissingSnapshotIsNotReadyAndCannotConsume() {
    RateLimiter limiter = mock(RateLimiter.class);
    for (Optional<RateLimitRuleSet> response :
        List.of(Optional.of(snapshot("explicit-epoch")), Optional.<RateLimitRuleSet>empty())) {
      RateLimitRuleSetProvider provider = id -> response;
      var engine =
          RateLimitEngine.builder()
              .ruleSetProvider(provider)
              .rateLimiter(limiter)
              .migrationAdmissionGate(MigrationAdmissionGate.enabled(Map.of()))
              .build();
      var service = new AuthzDecisionService(engine, provider, "orders", 1);
      assertThat(service.isReady("orders")).isFalse();
      assertThat(service.decide(REQUEST).status()).isEqualTo(503);
    }
    verifyNoInteractions(limiter);
  }
}
