package org.fluxgate.redis.store;

import io.lettuce.core.api.sync.RedisCommands;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis-backed storage for RuleSet configurations.
 *
 * <p>Stores RuleSet data as Redis Hashes for efficient retrieval. Key format:
 * fluxgate:ruleset:{ruleSetId}
 */
public class RedisRuleSetStore {

  private static final Logger log = LoggerFactory.getLogger(RedisRuleSetStore.class);

  private static final String KEY_PREFIX = "fluxgate:ruleset:";
  private static final String INDEX_KEY = "fluxgate:rulesets";

  private final RedisCommands<String, String> commands;

  public RedisRuleSetStore(RedisCommands<String, String> commands) {
    this.commands = Objects.requireNonNull(commands, "commands must not be null");
  }

  /** Save a RuleSet to Redis. */
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

    commands.hset(key, hash);

    // Add to index set
    commands.sadd(INDEX_KEY, ruleSetData.getRuleSetId());

    log.info("Saved RuleSet to Redis: {}", ruleSetData);
  }

  /** Find a RuleSet by ID. */
  public Optional<RuleSetData> findById(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");

    String key = KEY_PREFIX + ruleSetId;
    Map<String, String> hash = commands.hgetall(key);

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

  /** Delete a RuleSet by ID. */
  public boolean delete(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");

    String key = KEY_PREFIX + ruleSetId;
    long deleted = commands.del(key);
    commands.srem(INDEX_KEY, ruleSetId);

    if (deleted > 0) {
      log.info("Deleted RuleSet from Redis: {}", ruleSetId);
      return true;
    }
    return false;
  }

  /** List all RuleSet IDs. */
  public List<String> listAllIds() {
    Set<String> ids = commands.smembers(INDEX_KEY);
    return ids != null ? new ArrayList<>(ids) : Collections.emptyList();
  }

  /** Find all RuleSets. */
  public List<RuleSetData> findAll() {
    List<String> ids = listAllIds();
    List<RuleSetData> result = new ArrayList<>();

    for (String id : ids) {
      findById(id).ifPresent(result::add);
    }

    return result;
  }

  /** Check if a RuleSet exists. */
  public boolean exists(String ruleSetId) {
    String key = KEY_PREFIX + ruleSetId;
    return commands.exists(key) > 0;
  }

  /** Clear all RuleSets (use with caution!). */
  public void clearAll() {
    List<String> ids = listAllIds();
    for (String id : ids) {
      commands.del(KEY_PREFIX + id);
    }
    commands.del(INDEX_KEY);
    log.info("Cleared all RuleSets from Redis");
  }
}
