package org.fluxgate.spring.reload.strategy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.fluxgate.core.reload.ReloadSource;
import org.fluxgate.core.reload.RuleReloadEvent;
import org.fluxgate.core.reload.RuleReloadListener;
import org.fluxgate.core.reload.RuleReloadStrategy;

/**
 * Reload strategy that runs a primary strategy together with one or more backstops.
 *
 * <p>Used to pair {@link RedisPubSubReloadStrategy} with a low-frequency {@link
 * PollingReloadStrategy}: Pub/Sub delivers changes within milliseconds but is at-most-once, so a
 * dropped message would leave this instance serving stale rules until the cache TTL expires. The
 * polling backstop makes the node converge within its interval regardless.
 *
 * <p>Events from every delegate are funnelled through this strategy's own listener list, so the
 * ordering contract of {@link AbstractReloadStrategy} (cache invalidation before bucket reset)
 * holds no matter which delegate detected the change.
 */
public class CompositeReloadStrategy extends AbstractReloadStrategy implements RuleReloadListener {

  private final RuleReloadStrategy primary;
  private final List<RuleReloadStrategy> backstops;
  private final ReloadSource source;

  /**
   * Creates a composite strategy.
   *
   * @param primary the strategy that detects changes in real time (must not be null)
   * @param backstops additional strategies that converge more slowly (must not be null or contain
   *     null)
   */
  public CompositeReloadStrategy(RuleReloadStrategy primary, RuleReloadStrategy... backstops) {
    this.primary = Objects.requireNonNull(primary, "primary must not be null");
    this.backstops = new ArrayList<>(Arrays.asList(Objects.requireNonNull(backstops)));
    this.backstops.forEach(
        backstop -> Objects.requireNonNull(backstop, "backstop must not be null"));
    this.source =
        primary instanceof AbstractReloadStrategy
            ? ((AbstractReloadStrategy) primary).getReloadSource()
            : ReloadSource.MANUAL;

    primary.addListener(this);
    this.backstops.forEach(backstop -> backstop.addListener(this));
  }

  @Override
  protected ReloadSource getReloadSource() {
    return source;
  }

  @Override
  protected void doStart() {
    primary.start();
    backstops.forEach(RuleReloadStrategy::start);
    log.info(
        "Composite reload strategy started: primary={}, backstops={}",
        primary.getClass().getSimpleName(),
        backstops.stream().map(s -> s.getClass().getSimpleName()).collect(Collectors.toList()));
  }

  @Override
  protected void doStop() {
    for (int i = backstops.size() - 1; i >= 0; i--) {
      backstops.get(i).stop();
    }
    primary.stop();
    log.info("Composite reload strategy stopped");
  }

  /**
   * Republishes an event detected by one of the delegates to this strategy's own listeners and
   * synchronises every polling backstop's version map, so the backstop's next poll does not treat
   * the already-delivered change as a new one and fire a second reset.
   */
  @Override
  public void onReload(RuleReloadEvent event) {
    notifyListeners(event);
    for (RuleReloadStrategy backstop : backstops) {
      if (backstop instanceof PollingReloadStrategy) {
        PollingReloadStrategy polling = (PollingReloadStrategy) backstop;
        if (event.isFullReload()) {
          polling.markAllSeen();
        } else {
          polling.markSeen(event.getRuleSetId());
        }
      }
    }
  }

  /**
   * Returns the primary strategy.
   *
   * @return the primary strategy
   */
  public RuleReloadStrategy getPrimary() {
    return primary;
  }

  /**
   * Returns the backstop strategies.
   *
   * @return an unmodifiable view of the backstops
   */
  public List<RuleReloadStrategy> getBackstops() {
    return Collections.unmodifiableList(backstops);
  }
}
