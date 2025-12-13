package org.fluxgate.adapter.mongo.health;

import com.mongodb.client.MongoDatabase;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB health checker implementation that provides detailed health information.
 *
 * <p>This checker performs the following checks:
 *
 * <ul>
 *   <li>Connectivity: ping command to verify connection
 *   <li>Latency: Measures ping response time in milliseconds
 *   <li>Database: Reports database name
 *   <li>Server Info: Reports MongoDB version and other server details
 * </ul>
 *
 * <p>Example health output:
 *
 * <pre>{@code
 * {
 *   "mongo.status": "UP",
 *   "mongo.message": "MongoDB is healthy",
 *   "mongo.database": "fluxgate",
 *   "mongo.latency_ms": 5,
 *   "mongo.version": "7.0.0",
 *   "mongo.connections.current": 10,
 *   "mongo.connections.available": 100
 * }
 * }</pre>
 */
public class MongoHealthCheckerImpl {

  private static final Logger log = LoggerFactory.getLogger(MongoHealthCheckerImpl.class);

  private final MongoDatabase database;

  /**
   * Creates a new MongoDB health checker.
   *
   * @param database the MongoDB database
   */
  public MongoHealthCheckerImpl(MongoDatabase database) {
    this.database = Objects.requireNonNull(database, "database must not be null");
  }

  /**
   * Performs a health check on the MongoDB connection.
   *
   * @return health status with detailed information
   */
  public HealthCheckResult check() {
    Map<String, Object> details = new HashMap<>();
    details.put("database", database.getName());

    try {
      // Check connectivity and measure latency with ping command
      long startTime = System.nanoTime();
      Document pingResult = database.runCommand(new Document("ping", 1));
      long latencyNanos = System.nanoTime() - startTime;
      long latencyMs = latencyNanos / 1_000_000;

      details.put("latency_ms", latencyMs);

      // Check ping result
      Double ok = pingResult.getDouble("ok");
      if (ok == null || ok != 1.0) {
        return HealthCheckResult.down("Ping command failed", details);
      }

      // Get server status for additional details
      addServerDetails(details);

      return HealthCheckResult.up("MongoDB is healthy", details);

    } catch (Exception e) {
      log.warn("MongoDB health check failed: {}", e.getMessage());
      details.put("error", e.getMessage());
      return HealthCheckResult.down("Health check failed: " + e.getMessage(), details);
    }
  }

  /**
   * Adds server details from serverStatus command.
   *
   * @param details the details map to populate
   */
  private void addServerDetails(Map<String, Object> details) {
    try {
      // Get build info for version
      Document buildInfo = database.runCommand(new Document("buildInfo", 1));
      String version = buildInfo.getString("version");
      if (version != null) {
        details.put("version", version);
      }

      // Get server status for connection info
      // Note: serverStatus requires appropriate privileges
      try {
        Document serverStatus = database.runCommand(new Document("serverStatus", 1));

        // Connection info
        Document connections = serverStatus.get("connections", Document.class);
        if (connections != null) {
          Integer current = connections.getInteger("current");
          Integer available = connections.getInteger("available");
          Integer totalCreated = connections.getInteger("totalCreated");

          if (current != null) {
            details.put("connections.current", current);
          }
          if (available != null) {
            details.put("connections.available", available);
          }
          if (totalCreated != null) {
            details.put("connections.totalCreated", totalCreated);
          }
        }

        // Replication info (if replica set)
        Document repl = serverStatus.get("repl", Document.class);
        if (repl != null) {
          String setName = repl.getString("setName");
          Boolean isWritablePrimary = repl.getBoolean("isWritablePrimary");
          Boolean secondary = repl.getBoolean("secondary");

          if (setName != null) {
            details.put("replicaSet.name", setName);
          }
          if (isWritablePrimary != null && isWritablePrimary) {
            details.put("replicaSet.role", "PRIMARY");
          } else if (secondary != null && secondary) {
            details.put("replicaSet.role", "SECONDARY");
          }
        }

      } catch (Exception e) {
        // serverStatus may fail due to permissions, which is fine
        log.debug("Could not get serverStatus (may require admin privileges): {}", e.getMessage());
      }

    } catch (Exception e) {
      log.debug("Could not get server details: {}", e.getMessage());
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
