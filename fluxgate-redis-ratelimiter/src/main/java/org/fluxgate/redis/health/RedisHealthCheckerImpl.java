package org.fluxgate.redis.health;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fluxgate.redis.connection.ClusterRedisConnection;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis health checker implementation that provides detailed health information.
 *
 * <p>This checker performs the following checks:
 *
 * <ul>
 *   <li>Connectivity: PING command to verify connection
 *   <li>Latency: Measures PING response time in milliseconds
 *   <li>Mode: Reports whether running in STANDALONE or CLUSTER mode
 *   <li>Cluster State (cluster mode only): Reports cluster_state, node count, slots status
 * </ul>
 *
 * <p>Example health output for cluster mode:
 *
 * <pre>{@code
 * {
 *   "redis.status": "UP",
 *   "redis.message": "Redis cluster is healthy",
 *   "redis.mode": "CLUSTER",
 *   "redis.latency_ms": 2,
 *   "redis.cluster_state": "ok",
 *   "redis.cluster_nodes": 6,
 *   "redis.cluster_slots_ok": 16384,
 *   "redis.cluster_slots_fail": 0
 * }
 * }</pre>
 */
public class RedisHealthCheckerImpl {

  private static final Logger log = LoggerFactory.getLogger(RedisHealthCheckerImpl.class);

  private final RedisConnectionProvider connectionProvider;

  /**
   * Creates a new Redis health checker.
   *
   * @param connectionProvider the Redis connection provider
   */
  public RedisHealthCheckerImpl(RedisConnectionProvider connectionProvider) {
    this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
  }

  /**
   * Performs a health check on the Redis connection.
   *
   * @return health status with detailed information
   */
  public HealthCheckResult check() {
    Map<String, Object> details = new HashMap<>();
    details.put("mode", connectionProvider.getMode().name());

    try {
      // Check connectivity and measure latency
      long startTime = System.nanoTime();
      String pong = connectionProvider.ping();
      long latencyNanos = System.nanoTime() - startTime;
      long latencyMs = latencyNanos / 1_000_000;

      if (!"PONG".equals(pong)) {
        return HealthCheckResult.down("Unexpected PING response: " + pong, details);
      }

      details.put("latency_ms", latencyMs);

      // Add cluster-specific details if in cluster mode
      if (connectionProvider.getMode() == RedisMode.CLUSTER) {
        addClusterDetails(details);
      }

      // Check if connection is truly healthy
      if (!connectionProvider.isConnected()) {
        return HealthCheckResult.down("Connection is not active", details);
      }

      String message =
          connectionProvider.getMode() == RedisMode.CLUSTER
              ? "Redis cluster is healthy"
              : "Redis standalone is healthy";

      return HealthCheckResult.up(message, details);

    } catch (Exception e) {
      log.warn("Redis health check failed: {}", e.getMessage());
      details.put("error", e.getMessage());
      return HealthCheckResult.down("Health check failed: " + e.getMessage(), details);
    }
  }

  /**
   * Adds cluster-specific details to the health check.
   *
   * @param details the details map to populate
   */
  private void addClusterDetails(Map<String, Object> details) {
    try {
      // Get cluster nodes count
      List<String> nodes = connectionProvider.clusterNodes();
      details.put("cluster_nodes", nodes.size());

      // Count master and slave nodes
      int masters = 0;
      int slaves = 0;
      for (String node : nodes) {
        if (node.contains("master")) {
          masters++;
        } else if (node.contains("slave") || node.contains("replica")) {
          slaves++;
        }
      }
      details.put("cluster_masters", masters);
      details.put("cluster_replicas", slaves);

      // Parse cluster info for more details
      if (connectionProvider instanceof ClusterRedisConnection clusterConnection) {
        parseClusterInfo(clusterConnection.getClusterInfo(), details);
      }

    } catch (Exception e) {
      log.warn("Failed to get cluster details: {}", e.getMessage());
      details.put("cluster_error", e.getMessage());
    }
  }

  /**
   * Parses CLUSTER INFO output and extracts relevant details.
   *
   * @param clusterInfo the raw CLUSTER INFO output
   * @param details the details map to populate
   */
  private void parseClusterInfo(String clusterInfo, Map<String, Object> details) {
    if (clusterInfo == null || clusterInfo.isEmpty()) {
      return;
    }

    for (String line : clusterInfo.split("\r?\n")) {
      String[] parts = line.split(":");
      if (parts.length == 2) {
        String key = parts[0].trim();
        String value = parts[1].trim();

        switch (key) {
          case "cluster_state" -> details.put("cluster_state", value);
          case "cluster_slots_ok" -> details.put("cluster_slots_ok", parseIntSafe(value));
          case "cluster_slots_fail" -> details.put("cluster_slots_fail", parseIntSafe(value));
          case "cluster_slots_assigned" ->
              details.put("cluster_slots_assigned", parseIntSafe(value));
          case "cluster_known_nodes" -> details.put("cluster_known_nodes", parseIntSafe(value));
          case "cluster_size" -> details.put("cluster_size", parseIntSafe(value));
          default -> {
            // Skip other fields
          }
        }
      }
    }
  }

  private int parseIntSafe(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /** Health check result with status and details. */
  public record HealthCheckResult(
      String status, String message, boolean isHealthy, Map<String, Object> details) {

    public static HealthCheckResult up(String message, Map<String, Object> details) {
      return new HealthCheckResult("UP", message, true, details);
    }

    public static HealthCheckResult down(String message, Map<String, Object> details) {
      return new HealthCheckResult("DOWN", message, false, details);
    }
  }
}
