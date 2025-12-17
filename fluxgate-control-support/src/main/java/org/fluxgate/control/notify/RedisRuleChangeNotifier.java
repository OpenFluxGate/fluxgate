package org.fluxgate.control.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis Pub/Sub implementation of {@link RuleChangeNotifier}.
 *
 * <p>Publishes rule change notifications to a Redis channel. All FluxGate instances subscribed to
 * this channel will receive the notification and invalidate their local caches.
 *
 * <p>Supports both standalone Redis and Redis Cluster configurations.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * // Standalone Redis
 * RuleChangeNotifier notifier = new RedisRuleChangeNotifier(
 *     "redis://localhost:6379",
 *     "fluxgate:rule-reload"
 * );
 *
 * // Notify specific rule change
 * notifier.notifyChange("my-rule-set-id");
 *
 * // Notify full reload
 * notifier.notifyFullReload();
 *
 * // Cleanup
 * notifier.close();
 * }</pre>
 */
public class RedisRuleChangeNotifier implements RuleChangeNotifier {

  private static final Logger log = LoggerFactory.getLogger(RedisRuleChangeNotifier.class);
  private static final String DEFAULT_SOURCE = "fluxgate-control";

  private final String redisUri;
  private final String channel;
  private final Duration timeout;
  private final String source;
  private final ObjectMapper objectMapper;
  private final boolean isCluster;

  private volatile RedisClient redisClient;
  private volatile RedisClusterClient redisClusterClient;
  private volatile StatefulRedisConnection<String, String> connection;
  private volatile StatefulRedisClusterConnection<String, String> clusterConnection;
  private volatile boolean closed = false;

  /**
   * Creates a new RedisRuleChangeNotifier with default settings.
   *
   * @param redisUri Redis URI (e.g., "redis://localhost:6379" or comma-separated for cluster)
   * @param channel the Pub/Sub channel name
   */
  public RedisRuleChangeNotifier(String redisUri, String channel) {
    this(redisUri, channel, Duration.ofSeconds(5), DEFAULT_SOURCE);
  }

  /**
   * Creates a new RedisRuleChangeNotifier with custom settings.
   *
   * @param redisUri Redis URI (e.g., "redis://localhost:6379" or comma-separated for cluster)
   * @param channel the Pub/Sub channel name
   * @param timeout connection timeout
   * @param source identifier for this application in notifications
   */
  public RedisRuleChangeNotifier(String redisUri, String channel, Duration timeout, String source) {
    this.redisUri = Objects.requireNonNull(redisUri, "redisUri must not be null");
    this.channel = Objects.requireNonNull(channel, "channel must not be null");
    this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    this.source = Objects.requireNonNull(source, "source must not be null");
    this.objectMapper = new ObjectMapper();
    this.isCluster = redisUri.contains(",");

    log.info(
        "RedisRuleChangeNotifier initialized: channel={}, cluster={}, source={}",
        channel,
        isCluster,
        source);
  }

  @Override
  public void notifyChange(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    RuleChangeMessage message = RuleChangeMessage.forRuleSet(ruleSetId, source);
    publish(message);
    log.info("Published rule change notification: ruleSetId={}", ruleSetId);
  }

  @Override
  public void notifyFullReload() {
    RuleChangeMessage message = RuleChangeMessage.fullReload(source);
    publish(message);
    log.info("Published full reload notification");
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;

    log.info("Closing RedisRuleChangeNotifier");

    if (connection != null) {
      try {
        connection.close();
      } catch (Exception e) {
        log.warn("Error closing Redis connection", e);
      }
    }

    if (clusterConnection != null) {
      try {
        clusterConnection.close();
      } catch (Exception e) {
        log.warn("Error closing Redis cluster connection", e);
      }
    }

    if (redisClient != null) {
      try {
        redisClient.shutdown();
      } catch (Exception e) {
        log.warn("Error shutting down Redis client", e);
      }
    }

    if (redisClusterClient != null) {
      try {
        redisClusterClient.shutdown();
      } catch (Exception e) {
        log.warn("Error shutting down Redis cluster client", e);
      }
    }
  }

  private void publish(RuleChangeMessage message) {
    if (closed) {
      throw new IllegalStateException("RedisRuleChangeNotifier is closed");
    }

    String json = serialize(message);
    ensureConnection();

    try {
      if (isCluster) {
        clusterConnection.sync().publish(channel, json);
      } else {
        connection.sync().publish(channel, json);
      }
    } catch (Exception e) {
      log.error("Failed to publish rule change notification", e);
      throw new RuleChangeNotificationException("Failed to publish notification", e);
    }
  }

  private synchronized void ensureConnection() {
    if (isCluster) {
      if (clusterConnection == null || !clusterConnection.isOpen()) {
        createClusterConnection();
      }
    } else {
      if (connection == null || !connection.isOpen()) {
        createStandaloneConnection();
      }
    }
  }

  private void createStandaloneConnection() {
    log.debug("Creating standalone Redis connection");
    RedisURI uri = createRedisUri(redisUri);
    redisClient = RedisClient.create(uri);
    connection = redisClient.connect();
  }

  private void createClusterConnection() {
    log.debug("Creating Redis cluster connection");
    List<RedisURI> uris =
        Arrays.stream(redisUri.split(","))
            .map(String::trim)
            .map(this::createRedisUri)
            .collect(Collectors.toList());
    redisClusterClient = RedisClusterClient.create(uris);
    clusterConnection = redisClusterClient.connect();
  }

  private RedisURI createRedisUri(String uri) {
    RedisURI redisURI = RedisURI.create(uri);
    redisURI.setTimeout(timeout);
    return redisURI;
  }

  private String serialize(RuleChangeMessage message) {
    try {
      return objectMapper.writeValueAsString(message);
    } catch (JsonProcessingException e) {
      throw new RuleChangeNotificationException("Failed to serialize message", e);
    }
  }
}
