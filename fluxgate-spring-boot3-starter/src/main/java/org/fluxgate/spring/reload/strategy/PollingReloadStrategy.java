package org.fluxgate.spring.reload.strategy;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.reload.ReloadSource;
import org.fluxgate.core.reload.RuleCache;
import org.fluxgate.core.reload.RuleReloadEvent;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;

/**
 * Polling-based reload strategy that periodically checks for rule changes.
 *
 * <p>This strategy maintains a version map (using hashCode) of cached rule sets and compares them
 * against the source provider at regular intervals. A rule set whose content is unchanged fires no
 * event, so no cache is invalidated. Notifications do not reset usage.
 *
 * <p>Configuration example:
 *
 * <pre>
 * fluxgate:
 *   reload:
 *     strategy: POLLING
 *     polling:
 *       interval: 30s
 *       initial-delay: 10s
 * </pre>
 */
public class PollingReloadStrategy extends AbstractReloadStrategy {

  private final RateLimitRuleSetProvider provider;
  private final RuleCache cache;
  private final Duration pollInterval;
  private final Duration initialDelay;

  private ScheduledExecutorService scheduler;
  private ScheduledFuture<?> pollTask;

  // Track rule set versions using hash codes
  private final Map<String, Integer> versionMap = new ConcurrentHashMap<>();

  /**
   * Creates a new polling reload strategy.
   *
   * @param provider the rule set provider to poll for changes
   * @param cache the cache to check for known rule set IDs
   * @param pollInterval interval between polls
   * @param initialDelay initial delay before first poll
   */
  public PollingReloadStrategy(
      RateLimitRuleSetProvider provider,
      RuleCache cache,
      Duration pollInterval,
      Duration initialDelay) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.cache = Objects.requireNonNull(cache, "cache must not be null");
    this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
    this.initialDelay = Objects.requireNonNull(initialDelay, "initialDelay must not be null");
  }

  @Override
  protected ReloadSource getReloadSource() {
    return ReloadSource.POLLING;
  }

  @Override
  protected void doStart() {
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "fluxgate-rule-poller");
              t.setDaemon(true);
              return t;
            });

    pollTask =
        scheduler.scheduleWithFixedDelay(
            this::pollForChanges,
            initialDelay.toMillis(),
            pollInterval.toMillis(),
            TimeUnit.MILLISECONDS);

    log.info(
        "Polling reload strategy started: interval={}, initialDelay={}",
        pollInterval,
        initialDelay);
  }

  @Override
  protected void doStop() {
    if (pollTask != null) {
      pollTask.cancel(false);
      pollTask = null;
    }

    if (scheduler != null) {
      scheduler.shutdown();
      try {
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        scheduler.shutdownNow();
        Thread.currentThread().interrupt();
      }
      scheduler = null;
    }

    versionMap.clear();
    log.info("Polling reload strategy stopped");
  }

  /** Polls for changes in all cached rule sets. */
  private void pollForChanges() {
    try {
      Set<String> cachedIds = cache.getCachedRuleSetIds();

      if (cachedIds.isEmpty()) {
        log.trace("No cached rule sets to poll");
        return;
      }

      log.trace("Polling {} cached rule sets for changes", cachedIds.size());

      for (String ruleSetId : cachedIds) {
        checkForChange(ruleSetId);
      }
    } catch (Exception e) {
      log.error("Error during polling cycle", e);
    }
  }

  /**
   * Checks if a specific rule set has changed.
   *
   * @param ruleSetId the rule set ID to check
   */
  private void checkForChange(String ruleSetId) {
    try {
      Optional<RateLimitRuleSet> cached = cache.get(ruleSetId);
      Optional<RateLimitRuleSet> currentOpt = provider.findById(ruleSetId);

      if (currentOpt.isEmpty()) {
        // Rule set was deleted
        Integer previousVersion = versionMap.remove(ruleSetId);
        if (previousVersion != null || cached.isPresent()) {
          log.info("Rule set deleted: {}", ruleSetId);
          notifyListeners(RuleReloadEvent.forRuleSet(ruleSetId, ReloadSource.POLLING));
        }
        return;
      }

      RateLimitRuleSet current = currentOpt.get();
      int currentVersion = computeVersion(current);
      Integer previousVersion = cached.map(this::computeVersion).orElse(versionMap.get(ruleSetId));

      if (previousVersion == null) {
        // No cached snapshot or previous observation to compare
        versionMap.put(ruleSetId, currentVersion);
        log.debug("Tracking new rule set: {} (version: {})", ruleSetId, currentVersion);
      } else if (!previousVersion.equals(currentVersion)) {
        // Rule set changed
        versionMap.put(ruleSetId, currentVersion);
        log.info(
            "Rule set changed: {} (version: {} -> {})", ruleSetId, previousVersion, currentVersion);
        notifyListeners(RuleReloadEvent.forRuleSet(ruleSetId, ReloadSource.POLLING));
      } else {
        log.trace("Rule set unchanged: {}", ruleSetId);
      }
    } catch (Exception e) {
      log.warn("Error checking rule set for changes: {}", ruleSetId, e);
    }
  }

  /**
   * Computes a version hash for a rule set.
   *
   * <p>Uses the rule set's content to generate a hash that changes when the rules change - and only
   * then. {@code RateLimitRule} and {@code RateLimitBand} have value-based {@code equals}/{@code
   * hashCode}, so two rule sets loaded from the store in successive polls hash identically as long
   * as their content is identical. Without that, every poll looked like a change and reset every
   * bucket in the deployment, handing all clients a fresh full quota each interval.
   *
   * <p>Deliberately not {@code RateLimitRuleSet.equals}, which also compares the key resolver and
   * the metrics recorder - usually freshly created lambdas that are never equal.
   *
   * @param ruleSet the rule set
   * @return version hash
   */
  private int computeVersion(RateLimitRuleSet ruleSet) {
    return Objects.hash(
        ruleSet.getId(), ruleSet.getDescription(), ruleSet.getRules(), ruleSet.getAccessControl());
  }

  /**
   * Manually triggers a version check for a specific rule set.
   *
   * @param ruleSetId the rule set ID to check
   */
  public void forceCheck(String ruleSetId) {
    checkForChange(ruleSetId);
  }

  /**
   * Records the current version of a rule set without firing a reload event.
   *
   * <p>Called by {@link CompositeReloadStrategy} when the primary strategy already notified
   * listeners of a change for this rule set, so the next backstop poll does not fire a second
   * reset.
   *
   * @param ruleSetId the rule set whose current version should be recorded
   */
  public void markSeen(String ruleSetId) {
    provider.findById(ruleSetId).ifPresent(rs -> versionMap.put(ruleSetId, computeVersion(rs)));
  }

  /**
   * Clears the version map without firing any reload event.
   *
   * <p>Called by {@link CompositeReloadStrategy} after a full-reload event, so the next poll treats
   * every rule set as a first observation and does not fire a second reset.
   */
  public void markAllSeen() {
    versionMap.clear();
  }

  /**
   * Returns the configured poll interval.
   *
   * @return poll interval
   */
  public Duration getPollInterval() {
    return pollInterval;
  }

  /**
   * Returns the configured initial delay.
   *
   * @return initial delay
   */
  public Duration getInitialDelay() {
    return initialDelay;
  }

  /**
   * Returns the current version map for debugging.
   *
   * @return unmodifiable copy of version map
   */
  public Map<String, Integer> getVersionMap() {
    return Map.copyOf(versionMap);
  }
}
