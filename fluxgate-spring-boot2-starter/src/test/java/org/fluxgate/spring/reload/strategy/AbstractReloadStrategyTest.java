package org.fluxgate.spring.reload.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.fluxgate.core.reload.ReloadSource;
import org.fluxgate.core.reload.RuleReloadEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the listener contract of {@link AbstractReloadStrategy}.
 *
 * <p>Ordering is behaviour, not a detail: resetting buckets before the rule cache has been
 * invalidated lets an in-flight request recreate the bucket from the stale rule, and resetting them
 * after a failed invalidation silently applies the old limits to fresh buckets.
 */
class AbstractReloadStrategyTest {

  /** Minimal concrete strategy that only exposes the listener machinery. */
  private static final class TestStrategy extends AbstractReloadStrategy {

    @Override
    protected ReloadSource getReloadSource() {
      return ReloadSource.MANUAL;
    }

    @Override
    protected void doStart() {}

    @Override
    protected void doStop() {}

    void publish(RuleReloadEvent event) {
      notifyListeners(event);
    }
  }

  private TestStrategy strategy;
  private List<String> calls;

  @BeforeEach
  void setUp() {
    strategy = new TestStrategy();
    calls = new ArrayList<>();
  }

  @Test
  void shouldNotifyListenersInAscendingOrderRegardlessOfRegistrationOrder() {
    strategy.addListener(
        event -> calls.add("bucket-reset"), AbstractReloadStrategy.ORDER_BUCKET_RESET);
    strategy.addListener(event -> calls.add("default"));
    strategy.addListener(
        event -> calls.add("cache-invalidation"), AbstractReloadStrategy.ORDER_CACHE_INVALIDATION);

    strategy.publish(RuleReloadEvent.forRuleSet("orders", ReloadSource.MANUAL));

    assertThat(calls).containsExactly("cache-invalidation", "default", "bucket-reset");
  }

  @Test
  void shouldSkipTheBucketResetWhenCacheInvalidationFails() {
    strategy.addListener(
        event -> {
          throw new IllegalStateException("cache is unreachable");
        },
        AbstractReloadStrategy.ORDER_CACHE_INVALIDATION);
    strategy.addListener(
        event -> calls.add("bucket-reset"), AbstractReloadStrategy.ORDER_BUCKET_RESET);

    strategy.publish(RuleReloadEvent.forRuleSet("orders", ReloadSource.MANUAL));

    assertThat(calls).isEmpty();
  }

  @Test
  void shouldStillRunEveryListenerSharingTheFailingOrder() {
    strategy.addListener(
        event -> {
          calls.add("first");
          throw new IllegalStateException("boom");
        },
        AbstractReloadStrategy.ORDER_CACHE_INVALIDATION);
    strategy.addListener(
        event -> calls.add("second"), AbstractReloadStrategy.ORDER_CACHE_INVALIDATION);
    strategy.addListener(
        event -> calls.add("bucket-reset"), AbstractReloadStrategy.ORDER_BUCKET_RESET);

    strategy.publish(RuleReloadEvent.forRuleSet("orders", ReloadSource.MANUAL));

    assertThat(calls).containsExactly("first", "second");
  }

  @Test
  void shouldKeepGoingWhenTheLastOrderFails() {
    strategy.addListener(
        event -> calls.add("cache-invalidation"), AbstractReloadStrategy.ORDER_CACHE_INVALIDATION);
    strategy.addListener(
        event -> {
          throw new IllegalStateException("redis is down");
        },
        AbstractReloadStrategy.ORDER_BUCKET_RESET);

    strategy.publish(RuleReloadEvent.forRuleSet("orders", ReloadSource.MANUAL));

    assertThat(calls).containsExactly("cache-invalidation");
  }

  @Test
  void shouldRemoveListeners() {
    org.fluxgate.core.reload.RuleReloadListener listener = event -> calls.add("kept");
    strategy.addListener(listener, AbstractReloadStrategy.ORDER_BUCKET_RESET);
    strategy.removeListener(listener);

    strategy.publish(RuleReloadEvent.fullReload(ReloadSource.MANUAL));

    assertThat(calls).isEmpty();
  }
}
