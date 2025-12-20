package org.fluxgate.redis.connection;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Abstraction layer for Redis operations that supports both Standalone and Cluster modes.
 *
 * <p>This interface provides a unified API for Redis commands regardless of the underlying
 * deployment topology (single node vs. cluster).
 */
public interface RedisConnectionProvider extends AutoCloseable {

  /**
   * Returns the connection mode.
   *
   * @return the Redis connection mode (STANDALONE or CLUSTER)
   */
  RedisMode getMode();

  /**
   * Checks if the connection is open and healthy.
   *
   * @return true if the connection is active
   */
  boolean isConnected();

  /**
   * Loads a Lua script into Redis and returns its SHA1 hash.
   *
   * <p>In cluster mode, the script is loaded on all master nodes.
   *
   * @param script the Lua script content
   * @return the SHA1 hash of the script
   */
  String scriptLoad(String script);

  /**
   * Executes a Lua script using EVALSHA.
   *
   * @param sha the SHA1 hash of the script
   * @param keys the keys used by the script
   * @param args the arguments passed to the script
   * @param <T> the return type
   * @return the script execution result
   * @throws io.lettuce.core.RedisNoScriptException if the script is not cached in Redis
   */
  <T> T evalsha(String sha, String[] keys, String[] args);

  /**
   * Executes a Lua script using EVAL.
   *
   * <p>This is less efficient than EVALSHA but useful as a fallback when the script is not cached.
   *
   * @param script the Lua script content
   * @param keys the keys used by the script
   * @param args the arguments passed to the script
   * @param <T> the return type
   * @return the script execution result
   */
  <T> T eval(String script, String[] keys, String[] args);

  /**
   * Sets a hash field value.
   *
   * @param key the hash key
   * @param field the field name
   * @param value the field value
   * @return true if the field was newly created, false if updated
   */
  boolean hset(String key, String field, String value);

  /**
   * Sets multiple hash fields.
   *
   * @param key the hash key
   * @param map the field-value pairs
   * @return the number of fields added
   */
  long hset(String key, Map<String, String> map);

  /**
   * Gets all fields and values from a hash.
   *
   * @param key the hash key
   * @return map of all field-value pairs
   */
  Map<String, String> hgetall(String key);

  /**
   * Deletes one or more keys.
   *
   * @param keys the keys to delete
   * @return the number of keys deleted
   */
  long del(String... keys);

  /**
   * Adds members to a set.
   *
   * @param key the set key
   * @param members the members to add
   * @return the number of members added
   */
  long sadd(String key, String... members);

  /**
   * Gets all members of a set.
   *
   * @param key the set key
   * @return the set members
   */
  Set<String> smembers(String key);

  /**
   * Removes members from a set.
   *
   * @param key the set key
   * @param members the members to remove
   * @return the number of members removed
   */
  long srem(String key, String... members);

  /**
   * Checks if a key exists.
   *
   * @param key the key to check
   * @return true if the key exists
   */
  boolean exists(String key);

  /**
   * Gets the TTL (time-to-live) of a key in seconds.
   *
   * @param key the key to check
   * @return TTL in seconds, -1 if key has no TTL, -2 if key doesn't exist
   */
  long ttl(String key);

  /**
   * Finds all keys matching the given pattern.
   *
   * <p>Warning: This operation can be slow on large databases. Use with caution in production.
   *
   * @param pattern the pattern to match (e.g., "fluxgate:*")
   * @return list of matching keys
   */
  java.util.List<String> keys(String pattern);

  /**
   * Flushes the current database (deletes all keys).
   *
   * <p>Warning: This is a destructive operation. Use with caution.
   *
   * @return status message
   */
  String flushdb();

  /**
   * Executes PING command to test connection.
   *
   * @return "PONG" if successful
   */
  String ping();

  /**
   * Gets information about cluster nodes (cluster mode only).
   *
   * @return list of node information, empty for standalone mode
   */
  List<String> clusterNodes();

  /** Closes all connections and releases resources. */
  @Override
  void close();

  /** Redis deployment modes. */
  enum RedisMode {
    STANDALONE,
    CLUSTER
  }
}
