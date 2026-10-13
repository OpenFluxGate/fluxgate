package org.fluxgate.control.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.fluxgate.core.util.HmacSigner;
import org.fluxgate.core.util.RedisUris;
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
 * groups. Obtaining or replacing the connection and {@link #close()} are mutually exclusive, so a
 * publish racing a shutdown cannot resurrect a client after it has been closed; the {@code PUBLISH}
 * itself runs outside that lock, so one slow publish does not serialise every other caller behind
 * it. Any failure to connect or publish surfaces as a {@link RuleChangeNotificationException}.
 *
 * <p>When a secret is configured ({@code fluxgate.control.secret}) every message carries an
 * HMAC-SHA256 signature over its canonical form (bound to the channel, with a per-message nonce),
 * and a data plane with the matching {@code fluxgate.reload.pubsub.secret} ignores anything it
 * cannot verify. Without a secret the channel stays as open as the Redis in front of it; the
 * auto-configuration therefore refuses to create an unsigned notifier unless {@code
 * fluxgate.control.allow-unsigned=true} (H-3, since 0.4). The secret is normalised with {@link
 * HmacSigner#normalizeSecret(String)} - the data plane does the same - and a secret shorter than
 * {@value HmacSigner#MIN_RECOMMENDED_SECRET_BYTES} bytes is reported at WARN.
 *
 * <p>Against a standalone Redis the number of subscribers that received each message is logged and
 * returned: a count of zero means the channel names of the control plane ({@code
 * fluxgate.control.redis.channel}) and the data plane ({@code fluxgate.reload.pubsub.channel})
 * disagree, or that no data plane instance is listening, and is reported once at WARN.
 *
 * <p>In Redis Cluster mode a {@code PUBLISH} reply counts only the subscribers connected to the
 * node that served the command; the message is still propagated to every node, so subscribers
 * elsewhere receive it without being counted. The reply therefore cannot tell "nobody listens"
 * apart from "everyone listens on another node". In cluster mode {@link #publishChange(String)} and
 * {@link #publishFullReload()} return {@link RuleChangeNotifier#UNKNOWN_RECEIVERS} and the "no
 * receivers / channel mismatch" WARN is not logged; check the channel names and data plane metrics
 * instead.
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
    this.secret = HmacSigner.normalizeSecret(secret);
    this.objectMapper = new ObjectMapper();
    this.isCluster = RedisUris.isCluster(redisUri);

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
    } else if (HmacSigner.isWeakSecret(this.secret)) {
      log.warn(
          "fluxgate.control.secret is shorter than {} bytes. A short HMAC secret can be brute-forced "
              + "offline from a single captured notification; use at least {} random bytes.",
          HmacSigner.MIN_RECOMMENDED_SECRET_BYTES,
          HmacSigner.MIN_RECOMMENDED_SECRET_BYTES);
    }
  }

  @Override
  public void notifyChange(String ruleSetId) {
    publishChange(ruleSetId);
  }

  @Override
  public void notifyFullReload() {
    publishFullReload();
  }

  @Override
  public long publishChange(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    RuleChangeMessage message = sign(RuleChangeMessage.forRuleSet(ruleSetId, source));
    long receivers = publish(message);
    log.info(
        "Published rule change notification: ruleSetId={} (receivers={})", ruleSetId, receivers);
    return receivers;
  }

  @Override
  public long publishFullReload() {
    RuleChangeMessage message = sign(RuleChangeMessage.fullReload(source));
    long receivers = publish(message);
    log.info("Published full reload notification (receivers={})", receivers);
    return receivers;
  }

  /**
   * Whether published messages carry an HMAC-SHA256 signature.
   *
   * @return true when a secret is configured
   */
  public boolean isSigning() {
    return secret != null;
  }

  /** Signs the message when a secret is configured, otherwise publishes it as-is. */
  private RuleChangeMessage sign(RuleChangeMessage message) {
    return secret != null ? message.signed(secret, channel) : message;
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
   * @return the number of receivers reported by a standalone Redis, or {@link
   *     RuleChangeNotifier#UNKNOWN_RECEIVERS} in cluster mode
   */
  private long publish(RuleChangeMessage message) {
    String json = serialize(message);

    // The lock only guards obtaining (or replacing) the connection; the PUBLISH runs outside it.
    StatefulRedisConnection<String, String> standalone;
    StatefulRedisClusterConnection<String, String> cluster;
    synchronized (connectionLock) {
      if (closed) {
        throw new IllegalStateException("RedisRuleChangeNotifier is closed");
      }
      try {
        ensureConnection();
      } catch (RuntimeException e) {
        log.error("Failed to connect to Redis to publish a rule change notification", e);
        throw new RuleChangeNotificationException(
            "Failed to connect to Redis to publish notification", e);
      }
      standalone = connection;
      cluster = clusterConnection;
    }

    Long receivers;
    try {
      receivers =
          isCluster
              ? cluster.sync().publish(channel, json)
              : standalone.sync().publish(channel, json);
    } catch (Exception e) {
      log.error("Failed to publish rule change notification", e);
      throw new RuleChangeNotificationException("Failed to publish notification", e);
    }

    if (isCluster) {
      // A cluster PUBLISH counts only the serving node's subscribers: a 0 here proves nothing.
      log.debug(
          "Rule change notification on channel '{}' published to the cluster; the node-local "
              + "receiver count ({}) is not reported",
          channel,
          receivers);
      return UNKNOWN_RECEIVERS;
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
        RedisUris.splitNodes(redisUri).stream()
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
    RedisURI redisURI;
    try {
      redisURI = RedisURI.create(uri);
    } catch (RuntimeException e) {
      // The parser's message (and the URISyntaxException behind it) quotes the whole URI,
      // password included, so neither may reach a log line.
      throw new IllegalArgumentException(
          "Invalid Redis URI (" + e.getClass().getSimpleName() + "); check the configured URI");
    }
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
