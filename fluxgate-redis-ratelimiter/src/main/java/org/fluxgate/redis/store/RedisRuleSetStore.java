package org.fluxgate.redis.store;

import java.util.*;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis-backed storage for RuleSet configurations.
 *
 * <p>Stores RuleSet data as Redis Hashes for efficient retrieval. Key format:
 * fluxgate:ruleset:{ruleSetId}
 *
 * <p>Supports both Standalone and Cluster Redis deployments via {@link RedisConnectionProvider}.
 */
public class RedisRuleSetStore {

  private static final Logger log = LoggerFactory.getLogger(RedisRuleSetStore.class);

  private static final String KEY_PREFIX = "fluxgate:ruleset:";
  private static final String INDEX_KEY = "fluxgate:rulesets";

  private final RedisConnectionProvider connectionProvider;

  /**
   * Creates a new RedisRuleSetStore with the given connection provider.
   *
   * @param connectionProvider the Redis connection provider (standalone or cluster)
   */
  public RedisRuleSetStore(RedisConnectionProvider connectionProvider) {
    this.connectionProvider =
        Objects.requireNonNull(connectionProvider, "connectionProvider must not be null");
  }

  /**
   * Save a RuleSet to Redis.
   *
   * @param ruleSetData the rule set data to save
   */
  public void save(RuleSetData ruleSetData) {
    Objects.requireNonNull(ruleSetData, "ruleSetData must not be null");
    Objects.requireNonNull(ruleSetData.getRuleSetId(), "ruleSetId must not be null");

    String key = KEY_PREFIX + ruleSetData.getRuleSetId();

    Map<String, String> hash = new HashMap<>();
    hash.put("ruleSetId", ruleSetData.getRuleSetId());
    hash.put("capacity", String.valueOf(ruleSetData.getCapacity()));
    hash.put("windowSeconds", String.valueOf(ruleSetData.getWindowSeconds()));
    hash.put(
        "keyStrategyId",
        ruleSetData.getKeyStrategyId() != null ? ruleSetData.getKeyStrategyId() : "clientIp");
    hash.put("createdAt", String.valueOf(ruleSetData.getCreatedAt()));

    connectionProvider.hset(key, hash);

    // Add to index set
    connectionProvider.sadd(INDEX_KEY, ruleSetData.getRuleSetId());

    log.info("Saved RuleSet to Redis: {}", ruleSetData);
  }

  /**
   * Find a RuleSet by ID.
   *
   * @param ruleSetId the rule set ID to find
   * @return the rule set data if found
   */
  public Optional<RuleSetData> findById(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");

    String key = KEY_PREFIX + ruleSetId;
    Map<String, String> hash = connectionProvider.hgetall(key);

    if (hash == null || hash.isEmpty()) {
      log.debug("RuleSet not found in Redis: {}", ruleSetId);
      return Optional.empty();
    }

    RuleSetData data = new RuleSetData();
    data.setRuleSetId(hash.get("ruleSetId"));
    data.setCapacity(Long.parseLong(hash.getOrDefault("capacity", "10")));
    data.setWindowSeconds(Long.parseLong(hash.getOrDefault("windowSeconds", "60")));
    data.setKeyStrategyId(hash.getOrDefault("keyStrategyId", "clientIp"));
    data.setCreatedAt(Long.parseLong(hash.getOrDefault("createdAt", "0")));

    log.debug("Found RuleSet in Redis: {}", data);
    return Optional.of(data);
  }

  /**
   * Delete a RuleSet by ID.
   *
   * @param ruleSetId the rule set ID to delete
   * @return true if deleted, false if not found
   */
  public boolean delete(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");

    String key = KEY_PREFIX + ruleSetId;
    long deleted = connectionProvider.del(key);
    connectionProvider.srem(INDEX_KEY, ruleSetId);

    if (deleted > 0) {
      log.info("Deleted RuleSet from Redis: {}", ruleSetId);
      return true;
    }
    return false;
  }

  /**
   * List all RuleSet IDs.
   *
   * @return list of all rule set IDs
   */
  public List<String> listAllIds() {
    Set<String> ids = connectionProvider.smembers(INDEX_KEY);
    return ids != null ? new ArrayList<>(ids) : Collections.emptyList();
  }

  /**
   * Find all RuleSets.
   *
   * @return list of all rule set data
   */
  public List<RuleSetData> findAll() {
    List<String> ids = listAllIds();
    List<RuleSetData> result = new ArrayList<>();

    for (String id : ids) {
      findById(id).ifPresent(result::add);
    }

    return result;
  }

  /**
   * Check if a RuleSet exists.
   *
   * @param ruleSetId the rule set ID to check
   * @return true if exists
   */
  public boolean exists(String ruleSetId) {
    String key = KEY_PREFIX + ruleSetId;
    return connectionProvider.exists(key);
  }

  /** Clear all RuleSets (use with caution!). */
  public void clearAll() {
    List<String> ids = listAllIds();
    for (String id : ids) {
      connectionProvider.del(KEY_PREFIX + id);
    }
    connectionProvider.del(INDEX_KEY);
    log.info("Cleared all RuleSets from Redis");
  }

  /**
   * Gets the Redis connection mode.
   *
   * @return STANDALONE or CLUSTER
   */
  public RedisConnectionProvider.RedisMode getMode() {
    return connectionProvider.getMode();
  }
}
