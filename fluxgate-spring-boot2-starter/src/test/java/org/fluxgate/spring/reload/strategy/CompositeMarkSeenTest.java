package org.fluxgate.spring.reload.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.reload.ReloadSource;
import org.fluxgate.core.reload.RuleReloadEvent;
import org.fluxgate.core.reload.RuleReloadListener;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.reload.cache.CaffeineRuleCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression test for N5 — after the primary pub/sub strategy delivers a reload event the backstop
 * {@link PollingReloadStrategy} must not fire a second reload on its next poll.
 */
class CompositeMarkSeenTest {

  private static RateLimitRuleSet makeRuleSet(String id) {
    return RateLimitRuleSet.builder(id)
        .keyResolver(new LimitScopeKeyResolver())
        .rules(
            List.of(
                RateLimitRule.builder("r1")
                    .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10).build())
                    .build()))
        .build();
  }

  private static RateLimitRuleSet makeRuleSet2(String id) {
    return RateLimitRuleSet.builder(id)
        .keyResolver(new LimitScopeKeyResolver())
        .rules(
            List.of(
                RateLimitRule.builder("r1")
                    .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 20).build())
                    .build()))
        .build();
  }

  @Test
  @DisplayName("backstop poll does not fire reload after primary already delivered the event")
  void noDoubleReloadAfterPrimaryDelivery() {
    RateLimitRuleSet v1 = makeRuleSet("rs1");
    RateLimitRuleSet v2Modified = makeRuleSet2("rs1");

    // Provider starts with v1, then changes to v2Modified
    List<RateLimitRuleSet> versions = new ArrayList<>();
    versions.add(v1);
    RateLimitRuleSetProvider provider = id -> Optional.of(versions.get(versions.size() - 1));

    CaffeineRuleCache ruleCache =
        new CaffeineRuleCache(Duration.ofMinutes(5), 1000, Duration.ofSeconds(5));
    // Seed the cache so the poller tracks this id
    ruleCache.put("rs1", v1);

    PollingReloadStrategy backstop =
        new PollingReloadStrategy(provider, ruleCache, Duration.ofHours(1), Duration.ofHours(1));

    // Collect events
    List<RuleReloadEvent> events = new ArrayList<>();
    RuleReloadListener recorder =
        event -> {
          events.add(event);
          // The production caching provider invalidates before markSeen records the source version.
          ruleCache.invalidate(event.getRuleSetId());
        };

    ManualPrimaryStrategy primary = new ManualPrimaryStrategy();
    CompositeReloadStrategy composite = new CompositeReloadStrategy(primary, backstop);
    composite.addListener(recorder);

    composite.start();
    try {
      // Primary fires event for rs1 (simulates a pub/sub message)
      versions.add(v2Modified);
      primary.fireEvent(RuleReloadEvent.forRuleSet("rs1", ReloadSource.PUBSUB));

      // Check synchronously: no scheduler race or timing assumption is needed.
      backstop.forceCheck("rs1");
      backstop.forceCheck("rs1");

      long reloadCount = events.stream().filter(e -> "rs1".equals(e.getRuleSetId())).count();
      assertThat(reloadCount)
          .as(
              "primary delivers one reload; backstop must not add a second after markSeen was"
                  + " called")
          .isEqualTo(1);
    } finally {
      composite.stop();
    }
  }

  // -------------------------------------------------------------------------
  // Minimal stub for a primary strategy that can fire events manually
  // -------------------------------------------------------------------------

  private static final class ManualPrimaryStrategy extends AbstractReloadStrategy {

    @Override
    protected ReloadSource getReloadSource() {
      return ReloadSource.PUBSUB;
    }

    @Override
    protected void doStart() {}

    @Override
    protected void doStop() {}

    void fireEvent(RuleReloadEvent event) {
      notifyListeners(event);
    }
  }
}
