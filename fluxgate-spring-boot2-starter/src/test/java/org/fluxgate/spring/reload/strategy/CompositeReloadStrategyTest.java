package org.fluxgate.spring.reload.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.fluxgate.core.reload.ReloadSource;
import org.fluxgate.core.reload.RuleReloadEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CompositeReloadStrategy}.
 *
 * <p>This is what gives the PUBSUB strategy a path back to consistency: Redis Pub/Sub is
 * at-most-once, so a dropped notification used to leave the node on stale rules with nothing to
 * correct it.
 */
class CompositeReloadStrategyTest {

  /** A strategy whose events can be published by the test. */
  private static final class TestStrategy extends AbstractReloadStrategy {

    private final ReloadSource source;
    private boolean started;

    TestStrategy(ReloadSource source) {
      this.source = source;
    }

    @Override
    protected ReloadSource getReloadSource() {
      return source;
    }

    @Override
    protected void doStart() {
      started = true;
    }

    @Override
    protected void doStop() {
      started = false;
    }

    void publish(RuleReloadEvent event) {
      notifyListeners(event);
    }
  }

  private TestStrategy primary;
  private TestStrategy backstop;
  private CompositeReloadStrategy composite;
  private List<String> events;

  @BeforeEach
  void setUp() {
    primary = new TestStrategy(ReloadSource.PUBSUB);
    backstop = new TestStrategy(ReloadSource.POLLING);
    composite = new CompositeReloadStrategy(primary, backstop);
    events = new ArrayList<>();
    composite.addListener(event -> events.add(event.getSource() + ":" + event.getRuleSetId()));
  }

  @Test
  void shouldStartAndStopEveryDelegate() {
    composite.start();
    assertThat(primary.started).isTrue();
    assertThat(backstop.started).isTrue();
    assertThat(composite.isRunning()).isTrue();

    composite.stop();
    assertThat(primary.started).isFalse();
    assertThat(backstop.started).isFalse();
    assertThat(composite.isRunning()).isFalse();
  }

  @Test
  void shouldRepublishEventsFromThePrimary() {
    primary.publish(RuleReloadEvent.forRuleSet("orders", ReloadSource.PUBSUB));

    assertThat(events).containsExactly("PUBSUB:orders");
  }

  @Test
  void shouldRepublishEventsFromTheBackstopWhenAMessageWasLost() {
    backstop.publish(RuleReloadEvent.forRuleSet("orders", ReloadSource.POLLING));

    assertThat(events).containsExactly("POLLING:orders");
  }

  @Test
  void shouldReportThePrimarySource() {
    assertThat(composite.getPrimary()).isSameAs(primary);
    assertThat(composite.getBackstops()).containsExactly(backstop);
  }

  @Test
  void shouldHonourListenerOrderingForEventsFromAnyDelegate() {
    List<String> order = new ArrayList<>();
    CompositeReloadStrategy strategy = new CompositeReloadStrategy(primary, backstop);
    strategy.addListener(
        event -> order.add("bucket-reset"), AbstractReloadStrategy.ORDER_BUCKET_RESET);
    strategy.addListener(
        event -> order.add("cache-invalidation"), AbstractReloadStrategy.ORDER_CACHE_INVALIDATION);

    backstop.publish(RuleReloadEvent.forRuleSet("orders", ReloadSource.POLLING));

    assertThat(order).containsExactly("cache-invalidation", "bucket-reset");
  }
}
