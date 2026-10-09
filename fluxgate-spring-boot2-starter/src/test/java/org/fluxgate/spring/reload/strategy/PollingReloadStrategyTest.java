package org.fluxgate.spring.reload.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.fluxgate.core.config.AccessControl;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.match.CidrSet;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.reload.RuleReloadEvent;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.reload.cache.CaffeineRuleCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PollingReloadStrategy}.
 *
 * <p>The first test is the regression for the polling bug that reset every bucket in the deployment
 * once per interval: the provider handed out a fresh object graph on every call, and without value
 * equality on the rules the version hash changed every time.
 */
class PollingReloadStrategyTest {

  private CaffeineRuleCache cache;
  private List<RuleReloadEvent> events;

  @BeforeEach
  void setUp() {
    cache = new CaffeineRuleCache(Duration.ofMinutes(5), 100);
    events = new ArrayList<>();
  }

  /** Builds a rule set with freshly constructed rule and band instances every time. */
  private static RateLimitRuleSet ruleSet(String id, long capacity) {
    return RateLimitRuleSet.builder(id)
        .description("orders traffic")
        .rules(
            List.of(
                RateLimitRule.builder(id + "-rule")
                    .name("orders")
                    .enabled(true)
                    .ruleSetId(id)
                    .addBand(
                        RateLimitBand.builder(Duration.ofMinutes(1), capacity)
                            .label("per-minute")
                            .build())
                    .build()))
        .keyResolver((ctx, rule) -> RateLimitKey.of("ip:10.0.0.1"))
        .build();
  }

  private PollingReloadStrategy strategyOver(RateLimitRuleSetProvider provider) {
    PollingReloadStrategy strategy =
        new PollingReloadStrategy(provider, cache, Duration.ofHours(1), Duration.ofHours(1));
    strategy.addListener(
        event -> {
          events.add(event);
          cache.invalidate(event.getRuleSetId());
        });
    return strategy;
  }

  @Test
  void shouldDetectChangeBeforeTheFirstPoll() {
    cache.put("orders", ruleSet("orders", 100));
    PollingReloadStrategy strategy = strategyOver(id -> Optional.of(ruleSet(id, 50)));
    strategy.forceCheck("orders");
    assertThat(events).hasSize(1);
    assertThat(cache.get("orders")).isEmpty();
  }

  @Test
  void shouldNotLetMarkSeenConcealAStaleCachedSnapshot() {
    cache.put("orders", ruleSet("orders", 100));
    PollingReloadStrategy strategy = strategyOver(id -> Optional.of(ruleSet(id, 50)));
    strategy.markSeen("orders");
    strategy.forceCheck("orders");
    assertThat(events).hasSize(1);
    assertThat(cache.get("orders")).isEmpty();
  }

  @Test
  void shouldDetectDeletionBeforeTheFirstPoll() {
    cache.put("orders", ruleSet("orders", 100));
    PollingReloadStrategy strategy = strategyOver(id -> Optional.empty());
    strategy.forceCheck("orders");
    strategy.forceCheck("orders");
    assertThat(events).hasSize(1);
    assertThat(cache.get("orders")).isEmpty();
  }

  @Test
  void shouldNotInvalidateEqualAclSnapshotsRebuiltByTheProvider() {
    RateLimitRuleSetProvider provider =
        id ->
            Optional.of(
                RateLimitRuleSet.builder(id)
                    .description("orders traffic")
                    .rules(ruleSet(id, 100).getRules())
                    .keyResolver((ctx, rule) -> RateLimitKey.of("ip:10.0.0.1"))
                    .accessControl(
                        AccessControl.builder()
                            .deniedIps(CidrSet.of(List.of("10.0.0.0/8")))
                            .build())
                    .build());
    cache.put("orders", provider.findById("orders").orElseThrow());
    PollingReloadStrategy strategy = strategyOver(provider);
    strategy.forceCheck("orders");
    strategy.forceCheck("orders");
    assertThat(events).isEmpty();
  }

  @Test
  void shouldDetectOnlyIpAclChanges() {
    cache.put("orders", ruleSet("orders", 100));
    RateLimitRuleSet changed =
        RateLimitRuleSet.builder("orders")
            .description("orders traffic")
            .rules(ruleSet("orders", 100).getRules())
            .keyResolver((ctx, rule) -> RateLimitKey.of("ip:10.0.0.1"))
            .accessControl(
                AccessControl.builder().deniedIps(CidrSet.of(List.of("10.0.0.0/8"))).build())
            .build();
    PollingReloadStrategy strategy = strategyOver(id -> Optional.of(changed));
    strategy.forceCheck("orders");
    assertThat(events).hasSize(1);
  }

  @Test
  void shouldNotFireWhenSuccessivePollsSeeEqualButDistinctRuleInstances() {
    // Every call rebuilds the rule set, exactly like a repository with no identity cache.
    PollingReloadStrategy strategy = strategyOver(id -> Optional.of(ruleSet(id, 100)));
    cache.put("orders", ruleSet("orders", 100));

    strategy.forceCheck("orders"); // first sighting, records the version
    strategy.forceCheck("orders");
    strategy.forceCheck("orders");

    assertThat(events).isEmpty();
  }

  @Test
  void shouldFireWhenABandCapacityChanges() {
    AtomicReference<Long> capacity = new AtomicReference<>(100L);
    PollingReloadStrategy strategy = strategyOver(id -> Optional.of(ruleSet(id, capacity.get())));
    cache.put("orders", ruleSet("orders", 100));

    strategy.forceCheck("orders");
    assertThat(events).isEmpty();

    capacity.set(50L);
    strategy.forceCheck("orders");

    assertThat(events).hasSize(1);
    assertThat(events.get(0).getRuleSetId()).isEqualTo("orders");
    assertThat(events.get(0).isFullReload()).isFalse();
  }

  @Test
  void shouldFireOnceWhenARuleSetIsDeleted() {
    AtomicReference<Optional<RateLimitRuleSet>> answer =
        new AtomicReference<>(Optional.of(ruleSet("orders", 100)));
    PollingReloadStrategy strategy = strategyOver(id -> answer.get());
    cache.put("orders", ruleSet("orders", 100));

    strategy.forceCheck("orders");
    answer.set(Optional.empty());

    strategy.forceCheck("orders");
    strategy.forceCheck("orders");

    assertThat(events).hasSize(1);
    assertThat(events.get(0).getRuleSetId()).isEqualTo("orders");
  }

  @Test
  void shouldNotFireWhenTheProviderFails() {
    PollingReloadStrategy strategy =
        strategyOver(
            id -> {
              throw new IllegalStateException("store unavailable");
            });
    cache.put("orders", ruleSet("orders", 100));

    strategy.forceCheck("orders");

    assertThat(events).isEmpty();
  }
}
