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
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p>Connections are created on first publish and recreated when they drop. A reconnect closes the
 * previous client and connection first, so a flapping Redis cannot accumulate Netty event loop
 * groups, and {@link #close()} and {@link #notifyChange(String)} are mutually exclusive, so a
 * publish racing a shutdown cannot resurrect a client after it has been closed.
 *
 * <p>When a secret is configured ({@code fluxgate.control.secret}) every message carries an
 * HMAC-SHA256 signature over its canonical form, and a data plane with the matching {@code
 * fluxgate.reload.pubsub.secret} ignores anything it cannot verify. Without a secret the channel
 * stays as open as the Redis in front of it.
 *
 * <p>The number of subscribers that received each message is logged: a count of zero means the
 * channel names of the control plane ({@code fluxgate.control.redis.channel}) and the data plane
 * ({@code fluxgate.reload.pubsub.channel}) disagree, or that no data plane instance is listening.
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
  private final String secret;
  private final ObjectMapper objectMapper;
  private final boolean isCluster;

  /** Guards connection creation, replacement and shutdown against each other. */
  private final Object connectionLock = new Object();

  /** Whether the "nobody is listening" warning has already been logged. */
  private final AtomicBoolean noReceiverWarned = new AtomicBoolean();

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
    this(redisUri, channel, timeout, source, null);
  }

  /**
   * Creates a new RedisRuleChangeNotifier that signs the messages it publishes.
   *
   * @param redisUri Redis URI (e.g., "redis://localhost:6379" or comma-separated for cluster)
   * @param channel the Pub/Sub channel name
   * @param timeout connection timeout
   * @param source identifier for this application in notifications
   * @param secret shared secret for HMAC-SHA256 signing, or null/blank to publish unsigned messages
   */
  public RedisRuleChangeNotifier(
      String redisUri, String channel, Duration timeout, String source, String secret) {
    this.redisUri = Objects.requireNonNull(redisUri, "redisUri must not be null");
    this.channel = Objects.requireNonNull(channel, "channel must not be null");
    this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    this.source = Objects.requireNonNull(source, "source must not be null");
    this.secret = secret != null && !secret.trim().isEmpty() ? secret : null;
    this.objectMapper = new ObjectMapper();
    this.isCluster = redisUri.contains(",");

    log.info(
        "RedisRuleChangeNotifier initialized: channel={}, cluster={}, source={}, signed={}",
        channel,
        isCluster,
        source,
        this.secret != null);
    if (this.secret == null) {
      log.info(
          "fluxgate.control.secret is not set: rule change notifications are published unsigned, so "
              + "anyone able to PUBLISH to '{}' can reset every token bucket. Set a secret here and "
              + "the matching fluxgate.reload.pubsub.secret on the data plane.",
          channel);
    }
  }

  @Override
  public void notifyChange(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    RuleChangeMessage message = sign(RuleChangeMessage.forRuleSet(ruleSetId, source));
    long receivers = publish(message);
    log.info(
        "Published rule change notification: ruleSetId={} (receivers={})", ruleSetId, receivers);
  }

  @Override
  public void notifyFullReload() {
    RuleChangeMessage message = sign(RuleChangeMessage.fullReload(source));
    long receivers = publish(message);
    log.info("Published full reload notification (receivers={})", receivers);
  }

  /** Signs the message when a secret is configured, otherwise publishes it as-is. */
  private RuleChangeMessage sign(RuleChangeMessage message) {
    return secret != null ? message.signed(secret) : message;
  }

  @Override
  public void close() {
    synchronized (connectionLock) {
      if (closed) {
        return;
      }
      closed = true;

      log.info("Closing RedisRuleChangeNotifier");
      closeConnections();
      shutdownClients();
    }
  }

  /**
   * Publishes a message and returns how many subscribers received it.
   *
   * @param message the message to publish
   * @return the number of receivers reported by Redis
   */
  private long publish(RuleChangeMessage message) {
    String json = serialize(message);

    Long receivers;
    synchronized (connectionLock) {
      if (closed) {
        throw new IllegalStateException("RedisRuleChangeNotifier is closed");
      }
      ensureConnection();

      try {
        if (isCluster) {
          receivers = clusterConnection.sync().publish(channel, json);
        } else {
          receivers = connection.sync().publish(channel, json);
        }
      } catch (Exception e) {
        log.error("Failed to publish rule change notification", e);
        throw new RuleChangeNotificationException("Failed to publish notification", e);
      }
    }

    long count = receivers != null ? receivers : 0L;
    if (count == 0) {
      if (noReceiverWarned.compareAndSet(false, true)) {
        log.warn(
            "No subscriber received the rule change notification on channel '{}'. Check that the "
                + "data plane's fluxgate.reload.pubsub.channel matches "
                + "fluxgate.control.redis.channel and that at least one instance is running.",
            channel);
      }
    } else {
      noReceiverWarned.set(false);
      log.debug(
          "Rule change notification on channel '{}' reached {} subscriber(s)", channel, count);
    }
    return count;
  }

  /** Creates the connection when missing or broken. Must be called while holding the lock. */
  private void ensureConnection() {
    if (closed) {
      throw new IllegalStateException("RedisRuleChangeNotifier is closed");
    }
    if (isCluster) {
      if (clusterConnection == null || !clusterConnection.isOpen()) {
        // Release the previous generation first: Lettuce clients own a Netty event loop group.
        closeConnections();
        shutdownClients();
        createClusterConnection();
      }
    } else {
      if (connection == null || !connection.isOpen()) {
        closeConnections();
        shutdownClients();
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

  /** Closes the current connections. Must be called while holding the lock. */
  private void closeConnections() {
    if (connection != null) {
      try {
        connection.close();
      } catch (Exception e) {
        log.warn("Error closing Redis connection", e);
      }
      connection = null;
    }

    if (clusterConnection != null) {
      try {
        clusterConnection.close();
      } catch (Exception e) {
        log.warn("Error closing Redis cluster connection", e);
      }
      clusterConnection = null;
    }
  }

  /** Shuts down the current clients. Must be called while holding the lock. */
  private void shutdownClients() {
    if (redisClient != null) {
      try {
        redisClient.shutdown();
      } catch (Exception e) {
        log.warn("Error shutting down Redis client", e);
      }
      redisClient = null;
    }

    if (redisClusterClient != null) {
      try {
        redisClusterClient.shutdown();
      } catch (Exception e) {
        log.warn("Error shutting down Redis cluster client", e);
      }
      redisClusterClient = null;
    }
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
