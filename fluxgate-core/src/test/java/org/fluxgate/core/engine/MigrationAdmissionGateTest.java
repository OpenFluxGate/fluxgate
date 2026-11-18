package org.fluxgate.core.engine;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import org.fluxgate.core.config.AccessControl;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.exception.SnapshotAdmissionDeniedException;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.match.CidrSet;
import org.fluxgate.core.ratelimiter.*;
import org.junit.jupiter.api.Test;

class MigrationAdmissionGateTest {
  private static final RequestContext REQUEST =
      RequestContext.builder().clientIp("192.0.2.10").endpoint("/api").build();

  private static final class CountingLimiter implements RateLimiter {
    int calls;
    RateLimitRuleSet lastSnapshot;
    long lastPermits;

    @Override
    public RateLimitResult tryConsume(
        RequestContext context, RateLimitRuleSet snapshot, long permits) {
      calls++;
      lastSnapshot = snapshot;
      lastPermits = permits;
      return RateLimitResult.allowedWithoutRule();
    }
  }

  private final CountingLimiter limiter = new CountingLimiter();

  private static RateLimitRule rule(String id, Map<String, Object> attributes) {
    return RateLimitRule.builder(id)
        .ruleSetId("orders")
        .addBand(RateLimitBand.builder(Duration.ofHours(1), 6).build())
        .attributes(attributes)
        .build();
  }

  private static Map<String, Object> metadata(Object revision, Object epoch) {
    Map<String, Object> attributes = new HashMap<>();
    if (revision != null) attributes.put("fluxgate.counterRevision", revision);
    if (epoch != null) attributes.put("fluxgate.counterEpoch", epoch);
    return attributes;
  }

  private static RateLimitRuleSet snapshot(String id, boolean bypass, RateLimitRule... rules) {
    return RateLimitRuleSet.builder(id)
        .rules(Arrays.asList(rules))
        .keyResolver((c, r) -> RateLimitKey.of("test"))
        .accessControl(
            bypass
                ? AccessControl.builder()
                    .allowedIps(CidrSet.ofStrict(List.of("192.0.2.10/32")))
                    .build()
                : AccessControl.EMPTY)
        .build();
  }

  private static MigrationAdmissionGate approved() {
    return MigrationAdmissionGate.enabled(
        Map.of("orders", new MigrationAdmissionGate.Approval(2, "explicit-epoch")));
  }

  private RateLimitEngine engine(MigrationAdmissionGate gate, RateLimitRuleSet snapshot) {
    return RateLimitEngine.builder()
        .ruleSetProvider(id -> Optional.ofNullable(snapshot))
        .rateLimiter(limiter)
        .onMissingRuleSetStrategy(RateLimitEngine.OnMissingRuleSetStrategy.ALLOW)
        .migrationAdmissionGate(gate)
        .build();
  }

  @Test
  void defaultOffPreservesLegacySnapshotAndMissingAllow() {
    var legacy = snapshot("orders", false, rule("one", metadata(null, "legacy")));
    var engine =
        RateLimitEngine.builder()
            .ruleSetProvider(id -> Optional.of(legacy))
            .rateLimiter(limiter)
            .build();
    assertThat(engine.check("orders", REQUEST).isAllowed()).isTrue();
    assertThat(engine(MigrationAdmissionGate.disabled(), null).check("orders", REQUEST).isAllowed())
        .isTrue();
  }

  @Test
  void enabledEmptyAllowlistDeniesEvenAclBypassWithoutConsumption() {
    var s = snapshot("orders", true, rule("one", metadata(2L, "explicit-epoch")));
    assertThatThrownBy(
            () ->
                engine(MigrationAdmissionGate.enabled(Map.of()), s)
                    .checkUsingSnapshot(s, REQUEST, 1))
        .isInstanceOf(SnapshotAdmissionDeniedException.class);
    assertThat(limiter.calls).isZero();
  }

  @Test
  void missingSnapshotCannotUseConfiguredMissingAllow() {
    assertThatThrownBy(() -> engine(approved(), null).check("orders", REQUEST))
        .isInstanceOf(SnapshotAdmissionDeniedException.class);
    assertThat(limiter.calls).isZero();
  }

  @Test
  void exactApprovedSnapshotPassesProviderAndDirectPaths() {
    var s = snapshot("orders", false, rule("one", metadata(2L, "explicit-epoch")));
    var engine = engine(approved(), s);
    assertThat(engine.check("orders", REQUEST).isAllowed()).isTrue();
    assertThat(engine.checkUsingSnapshot(s, REQUEST, 1).isAllowed()).isTrue();
    assertThat(limiter.calls).isEqualTo(2);
    assertThat(limiter.lastSnapshot).isSameAs(s);
    assertThat(limiter.lastPermits).isEqualTo(1L);
  }

  @Test
  void legacyMissingBlankOrMixedEpochsDenyBeforeAcl() {
    for (Object epoch : Arrays.asList(null, "legacy", "", " ", 7L, "different-epoch")) {
      var s = snapshot("orders", true, rule("one", metadata(2L, epoch)));
      assertThatThrownBy(() -> engine(approved(), s).checkUsingSnapshot(s, REQUEST, 1))
          .isInstanceOf(SnapshotAdmissionDeniedException.class);
    }
    var mixed =
        snapshot(
            "orders",
            true,
            rule("one", metadata(2L, "explicit-epoch")),
            rule("two", metadata(2L, "legacy")));
    assertThatThrownBy(() -> engine(approved(), mixed).checkUsingSnapshot(mixed, REQUEST, 1))
        .isInstanceOf(SnapshotAdmissionDeniedException.class);
    assertThat(limiter.calls).isZero();
  }

  @Test
  void malformedOrUnapprovedRevisionDeniesBeforeConsumption() {
    for (Object revision :
        Arrays.asList(
            null,
            0L,
            -1L,
            "2",
            1.5,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            9_007_199_254_740_992L,
            new BigDecimal("2.00000000000000001"),
            3L)) {
      var s = snapshot("orders", false, rule("one", metadata(revision, "explicit-epoch")));
      assertThatThrownBy(() -> engine(approved(), s).check("orders", REQUEST))
          .as("revision=%s", revision)
          .isInstanceOf(SnapshotAdmissionDeniedException.class);
    }
    assertThat(limiter.calls).isZero();
  }

  @Test
  void mixedRevisionsAndUnknownRuleSetDeny() {
    var s =
        snapshot(
            "orders",
            false,
            rule("one", metadata(2L, "explicit-epoch")),
            rule("two", metadata(3L, "explicit-epoch")));
    assertThatThrownBy(() -> engine(approved(), s).checkUsingSnapshot(s, REQUEST, 1))
        .isInstanceOf(SnapshotAdmissionDeniedException.class);
    var unknown = snapshot("unapproved", false, rule("one", metadata(2L, "explicit-epoch")));
    assertThatThrownBy(() -> engine(approved(), unknown).checkUsingSnapshot(unknown, REQUEST, 1))
        .isInstanceOf(SnapshotAdmissionDeniedException.class);
    assertThat(limiter.calls).isZero();
  }

  @Test
  void approvalMapIsDefensivelyCopied() {
    var map = new HashMap<String, MigrationAdmissionGate.Approval>();
    map.put("orders", new MigrationAdmissionGate.Approval(2, "explicit-epoch"));
    var gate = MigrationAdmissionGate.enabled(map);
    map.clear();
    map.put("orders", new MigrationAdmissionGate.Approval(3, "other"));
    var s = snapshot("orders", true, rule("one", metadata(2L, "explicit-epoch")));
    assertThat(engine(gate, s).check("orders", REQUEST).isAllowed()).isTrue();
    assertThat(limiter.calls).isZero();
  }

  @Test
  void configurationEqualityMatchesActualGateIdentityValues() {
    assertThat(approved()).isEqualTo(approved());
    assertThat(approved()).isNotEqualTo(MigrationAdmissionGate.disabled());
    assertThat(approved())
        .isNotEqualTo(
            MigrationAdmissionGate.enabled(
                Map.of("orders", new MigrationAdmissionGate.Approval(3, "explicit-epoch"))));
    assertThat(engine(approved(), null).getMigrationAdmissionGate()).isEqualTo(approved());
  }

  @Test
  void requestedIdCannotSelectDifferentApprovedSnapshot() {
    var s = snapshot("orders", false, rule("one", metadata(2L, "explicit-epoch")));
    var gate =
        MigrationAdmissionGate.enabled(
            Map.of(
                "requested", new MigrationAdmissionGate.Approval(2, "explicit-epoch"),
                "orders", new MigrationAdmissionGate.Approval(2, "explicit-epoch")));
    assertThatThrownBy(() -> engine(gate, s).check("requested", REQUEST))
        .isInstanceOf(SnapshotAdmissionDeniedException.class);
    assertThat(limiter.calls).isZero();
  }

  @Test
  void defaultOffPreservesRequestedIdMismatchBehavior() {
    var s = snapshot("orders", false, rule("one", metadata(2L, "explicit-epoch")));
    assertThat(engine(MigrationAdmissionGate.disabled(), s).check("requested", REQUEST).isAllowed())
        .isTrue();
    assertThat(limiter.calls).isEqualTo(1);
    assertThat(limiter.lastSnapshot).isSameAs(s);
  }

  @Test
  void emptySnapshotCannotBeConstructed() {
    assertThatThrownBy(() -> snapshot("orders", false))
        .isInstanceOf(org.fluxgate.core.exception.InvalidRuleConfigException.class)
        .hasMessageContaining("rules must contain");
    assertThat(limiter.calls).isZero();
  }
}
