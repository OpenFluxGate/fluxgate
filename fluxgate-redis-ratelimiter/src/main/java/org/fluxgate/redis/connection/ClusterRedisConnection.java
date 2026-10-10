package org.fluxgate.redis.connection;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
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
 *   <li>Topology refresh: periodically (every {@link #DEFAULT_TOPOLOGY_REFRESH_PERIOD} unless
 *       configured otherwise) and on every adaptive trigger (MOVED/ASK redirects, persistent
 *       reconnects, unknown nodes, uncovered slots), so a failover does not leave commands routed
 *       to a dead master
 * </ul>
 *
 * <p>The configured timeout is the command timeout as well as the connect timeout. Lettuce's
 * cluster client takes its command timeout from the node URIs and ignores {@code
 * setDefaultTimeout}, so the timeout is set on every node URI and enforced through {@link
 * TimeoutOptions}; without that, a stalled node blocks a caller for Lettuce's 60 second default.
 */
public class ClusterRedisConnection implements RedisConnectionProvider {

  private static final Logger log = LoggerFactory.getLogger(ClusterRedisConnection.class);

  /** How often the cluster topology is refreshed when no other period is given. */
  public static final Duration DEFAULT_TOPOLOGY_REFRESH_PERIOD = Duration.ofSeconds(30);

  private final RedisClusterClient clusterClient;
  private final StatefulRedisClusterConnection<String, String> connection;
  private final RedisAdvancedClusterCommands<String, String> commands;

  /**
   * Creates a new cluster Redis connection.
   *
   * @param nodeUris list of cluster node URIs (e.g., ["redis://node1:6379", "redis://node2:6379"])
   */
  public ClusterRedisConnection(List<String> nodeUris) {
    this(nodeUris, RedisUriUtils.DEFAULT_TIMEOUT);
  }

  /**
   * Creates a new cluster Redis connection with custom timeout.
   *
   * @param nodeUris list of cluster node URIs
   * @param timeout the connect and command timeout
   */
  public ClusterRedisConnection(List<String> nodeUris, Duration timeout) {
    this(nodeUris, timeout, DEFAULT_TOPOLOGY_REFRESH_PERIOD);
  }

  /**
   * Creates a new cluster Redis connection with custom timeout and topology refresh period.
   *
   * @param nodeUris list of cluster node URIs
   * @param timeout the connect and command timeout
   * @param topologyRefreshPeriod how often the cluster topology is refreshed in the background
   */
  public ClusterRedisConnection(
      List<String> nodeUris, Duration timeout, Duration topologyRefreshPeriod) {
    Objects.requireNonNull(nodeUris, "nodeUris must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");
    Objects.requireNonNull(topologyRefreshPeriod, "topologyRefreshPeriod must not be null");

    if (nodeUris.isEmpty()) {
      throw new IllegalArgumentException("At least one cluster node URI is required");
    }

    log.info("Creating Redis Cluster connection to {} nodes", nodeUris.size());

    List<RedisURI> redisUris = toRedisUris(nodeUris, timeout);

    this.clusterClient = RedisClusterClient.create(redisUris);
    this.clusterClient.setOptions(clientOptions(timeout, topologyRefreshPeriod));
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
      throw new org.fluxgate.core.exception.RedisConnectionException(
          "Failed to connect to Redis Cluster",
          RedisUriUtils.mask(String.join(",", nodeUris)),
          e,
          org.fluxgate.core.exception.RedisConnectionException.Phase.CONNECT);
    }
  }

  /**
   * Parses the node URIs and gives each of them the configured timeout, which the Lettuce cluster
   * client uses as its command timeout.
   *
   * @throws IllegalArgumentException if a URI cannot be parsed; the message carries the masked URI
   *     only, never the credentials
   */
  static List<RedisURI> toRedisUris(List<String> nodeUris, Duration timeout) {
    List<RedisURI> redisUris = new ArrayList<>(nodeUris.size());
    for (String nodeUri : nodeUris) {
      RedisURI redisUri = RedisUriUtils.parse(nodeUri);
      redisUri.setTimeout(timeout);
      redisUris.add(redisUri);
    }
    return redisUris;
  }

  /**
   * Client options of a cluster connection: the connect and command timeout, plus periodic and
   * adaptive topology refresh.
   */
  static ClusterClientOptions clientOptions(Duration timeout, Duration topologyRefreshPeriod) {
    return ClusterClientOptions.builder()
        .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
        .timeoutOptions(TimeoutOptions.enabled(timeout))
        .topologyRefreshOptions(
            ClusterTopologyRefreshOptions.builder()
                .enablePeriodicRefresh(topologyRefreshPeriod)
                .enableAllAdaptiveRefreshTriggers()
                .build())
        .build();
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

  /**
   * Visible for testing: wraps Lettuce objects that were created elsewhere, so that shutdown
   * behaviour can be exercised without a Redis cluster.
   */
  ClusterRedisConnection(
      RedisClusterClient clusterClient,
      StatefulRedisClusterConnection<String, String> connection,
      RedisAdvancedClusterCommands<String, String> commands) {
    this.clusterClient = clusterClient;
    this.connection = connection;
    this.commands = commands;
  }

  @Override
  public RedisMode getMode() {
    return RedisMode.CLUSTER;
  }

  @Override
  public boolean isConnected() {
    try {
      // connection == null means the commands were supplied from outside and this class does not
      // own a StatefulRedisClusterConnection to inspect; PING alone is then the whole answer.
      return (connection == null || connection.isOpen()) && "PONG".equals(commands.ping());
    } catch (Exception e) {
      log.warn("Cluster connection check failed", e);
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
  @SuppressWarnings("unchecked")
  public <T> T eval(String script, String[] keys, String[] args) {
    Objects.requireNonNull(script, "script must not be null");
    Objects.requireNonNull(keys, "keys must not be null");
    Objects.requireNonNull(args, "args must not be null");

    // Lettuce cluster client automatically routes EVAL to the correct node
    // based on the key's slot
    return (T) commands.eval(script, ScriptOutputType.MULTI, keys, args);
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
  public long unlink(String... keys) {
    return commands.unlink(keys);
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
  public List<String> scanKeys(String pattern, long count) {
    List<String> keys = new ArrayList<>();
    scanKeys(pattern, count, keys::addAll);
    return keys;
  }

  @Override
  public void scanKeys(String pattern, long count, Consumer<List<String>> pageConsumer) {
    Objects.requireNonNull(pattern, "pattern must not be null");
    Objects.requireNonNull(pageConsumer, "pageConsumer must not be null");
    if (count <= 0) {
      throw new IllegalArgumentException("count must be > 0");
    }

    ScanArgs scanArgs = ScanArgs.Builder.matches(pattern).limit(count);
    ScanCursor cursor = ScanCursor.INITIAL;
    do {
      KeyScanCursor<String> result = commands.scan(cursor, scanArgs);
      if (!result.getKeys().isEmpty()) {
        pageConsumer.accept(result.getKeys());
      }
      cursor = result;
    } while (!cursor.isFinished());
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
      return Collections.emptyList();
    }
  }

  @Override
  public void close() {
    log.info("Closing Redis Cluster connection");

    // Independent blocks: a connection that fails to close must not leak the client's Netty
    // event loop group along with it.
    try {
      if (connection != null) {
        connection.close();
      }
    } catch (Exception e) {
      log.warn("Error closing cluster connection", e);
    }

    try {
      if (clusterClient != null) {
        clusterClient.shutdown();
      }
    } catch (Exception e) {
      log.warn("Error shutting down Redis Cluster client", e);
    }

    log.info("Redis Cluster connection closed");
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
