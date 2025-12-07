package org.fluxgate.redis.connection;

import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis Cluster connection implementation.
 *
 * <p>This implementation uses Lettuce's {@link RedisClusterClient} for connecting to a Redis
 * Cluster. It handles:
 *
 * <ul>
 *   <li>Automatic cluster topology discovery
 *   <li>MOVED/ASK redirect handling
 *   <li>Script loading across all master nodes
 *   <li>Connection pooling to cluster nodes
 * </ul>
 */
public class ClusterRedisConnection implements RedisConnectionProvider {

  private static final Logger log = LoggerFactory.getLogger(ClusterRedisConnection.class);

  private final RedisClusterClient clusterClient;
  private final StatefulRedisClusterConnection<String, String> connection;
  private final RedisAdvancedClusterCommands<String, String> commands;

  /**
   * Creates a new cluster Redis connection.
   *
   * @param nodeUris list of cluster node URIs (e.g., ["redis://node1:6379", "redis://node2:6379"])
   */
  public ClusterRedisConnection(List<String> nodeUris) {
    this(nodeUris, Duration.ofSeconds(5));
  }

  /**
   * Creates a new cluster Redis connection with custom timeout.
   *
   * @param nodeUris list of cluster node URIs
   * @param timeout the connection timeout
   */
  public ClusterRedisConnection(List<String> nodeUris, Duration timeout) {
    Objects.requireNonNull(nodeUris, "nodeUris must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");

    if (nodeUris.isEmpty()) {
      throw new IllegalArgumentException("At least one cluster node URI is required");
    }

    log.info("Creating Redis Cluster connection to {} nodes", nodeUris.size());

    List<RedisURI> redisUris = nodeUris.stream().map(RedisURI::create).collect(Collectors.toList());

    this.clusterClient = RedisClusterClient.create(redisUris);
    this.clusterClient.setDefaultTimeout(timeout);

    try {
      this.connection = clusterClient.connect();
      this.commands = connection.sync();

      // Verify cluster connection
      String pong = commands.ping();
      int nodeCount = getClusterNodeCount();
      log.info(
          "Redis Cluster connection established: {} nodes discovered, ping={}", nodeCount, pong);
    } catch (Exception e) {
      clusterClient.close();
      throw new RedisConnectionException("Failed to connect to Redis Cluster", e);
    }
  }

  /**
   * Creates a new cluster Redis connection from existing Lettuce commands. Useful for testing and
   * when connection is managed externally.
   *
   * @param commands the Lettuce RedisAdvancedClusterCommands instance
   */
  public ClusterRedisConnection(RedisAdvancedClusterCommands<String, String> commands) {
    Objects.requireNonNull(commands, "commands must not be null");
    this.clusterClient = null;
    this.connection = null;
    this.commands = commands;
    log.debug("Cluster Redis connection created from existing commands");
  }

  @Override
  public RedisMode getMode() {
    return RedisMode.CLUSTER;
  }

  @Override
  public boolean isConnected() {
    try {
      return connection != null && connection.isOpen() && "PONG".equals(commands.ping());
    } catch (Exception e) {
      log.warn("Cluster connection check failed: {}", e.getMessage());
      return false;
    }
  }

  @Override
  public String scriptLoad(String script) {
    Objects.requireNonNull(script, "script must not be null");

    // In cluster mode, scriptLoad broadcasts to all nodes automatically
    // Lettuce handles this via the cluster connection
    String sha = commands.scriptLoad(script);
    log.debug("Lua script loaded to cluster, SHA: {}", sha);
    return sha;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T evalsha(String sha, String[] keys, String[] args) {
    Objects.requireNonNull(sha, "sha must not be null");
    Objects.requireNonNull(keys, "keys must not be null");
    Objects.requireNonNull(args, "args must not be null");

    // Lettuce cluster client automatically routes EVALSHA to the correct node
    // based on the key's slot
    return (T) commands.evalsha(sha, ScriptOutputType.MULTI, keys, args);
  }

  @Override
  public boolean hset(String key, String field, String value) {
    return commands.hset(key, field, value);
  }

  @Override
  public long hset(String key, Map<String, String> map) {
    return commands.hset(key, map);
  }

  @Override
  public Map<String, String> hgetall(String key) {
    return commands.hgetall(key);
  }

  @Override
  public long del(String... keys) {
    return commands.del(keys);
  }

  @Override
  public long sadd(String key, String... members) {
    return commands.sadd(key, members);
  }

  @Override
  public Set<String> smembers(String key) {
    return commands.smembers(key);
  }

  @Override
  public long srem(String key, String... members) {
    return commands.srem(key, members);
  }

  @Override
  public boolean exists(String key) {
    return commands.exists(key) > 0;
  }

  @Override
  public long ttl(String key) {
    return commands.ttl(key);
  }

  @Override
  public List<String> keys(String pattern) {
    // In cluster mode, this scans all nodes
    return new ArrayList<>(commands.keys(pattern));
  }

  @Override
  public String flushdb() {
    // In cluster mode, this flushes all nodes
    return commands.flushdb();
  }

  @Override
  public String ping() {
    return commands.ping();
  }

  @Override
  public List<String> clusterNodes() {
    try {
      String nodesInfo = commands.clusterNodes();
      List<String> nodes = new ArrayList<>();
      for (String line : nodesInfo.split("\n")) {
        if (!line.trim().isEmpty()) {
          nodes.add(line.trim());
        }
      }
      return nodes;
    } catch (Exception e) {
      log.warn("Failed to get cluster nodes: {}", e.getMessage());
      return List.of();
    }
  }

  @Override
  public void close() {
    log.info("Closing Redis Cluster connection");
    try {
      if (connection != null) {
        connection.close();
      }
      if (clusterClient != null) {
        clusterClient.shutdown();
      }
      log.info("Redis Cluster connection closed");
    } catch (Exception e) {
      log.warn("Error closing cluster connection: {}", e.getMessage());
    }
  }

  /**
   * Returns the underlying Lettuce cluster commands for advanced operations.
   *
   * @return the RedisAdvancedClusterCommands instance
   */
  public RedisAdvancedClusterCommands<String, String> getCommands() {
    return commands;
  }

  /**
   * Gets the number of cluster nodes.
   *
   * @return the number of nodes in the cluster
   */
  public int getClusterNodeCount() {
    return clusterNodes().size();
  }

  /**
   * Gets cluster information.
   *
   * @return cluster info string
   */
  public String getClusterInfo() {
    try {
      return commands.clusterInfo();
    } catch (Exception e) {
      log.warn("Failed to get cluster info: {}", e.getMessage());
      return "";
    }
  }
}
