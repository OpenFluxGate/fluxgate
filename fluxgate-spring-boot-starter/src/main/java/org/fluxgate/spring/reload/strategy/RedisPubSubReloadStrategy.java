package org.fluxgate.spring.reload.strategy;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.fluxgate.core.reload.ReloadSource;
import org.fluxgate.core.reload.RuleReloadEvent;

/**
 * Redis Pub/Sub based reload strategy for real-time rule change notifications.
 *
 * <p>This strategy subscribes to a Redis channel and listens for rule change messages. When a
 * message is received, it triggers a reload event to invalidate cached rules.
 *
 * <p>Message format:
 *
 * <ul>
 *   <li>"*" or empty - Full reload (all rules)
 *   <li>"ruleSetId" - Reload specific rule set
 * </ul>
 *
 * <p>Configuration example:
 *
 * <pre>
 * fluxgate:
 *   reload:
 *     strategy: PUBSUB
 *     pubsub:
 *       channel: fluxgate:rule-reload
 *       retry-on-failure: true
 *       retry-interval: 5s
 * </pre>
 */
public class RedisPubSubReloadStrategy extends AbstractReloadStrategy {

  /** Message indicating a full reload should occur. */
  public static final String FULL_RELOAD_MESSAGE = "*";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final String redisUri;
  private final String channel;
  private final boolean retryOnFailure;
  private final Duration retryInterval;
  private final boolean isCluster;
  private final Duration timeout;

  private Object redisClient; // Created lazily
  private final AtomicReference<StatefulRedisPubSubConnection<String, String>> connectionRef =
      new AtomicReference<>();
  private ScheduledExecutorService retryScheduler;

  /**
   * Creates a new Redis Pub/Sub reload strategy using URI.
   *
   * @param redisUri the Redis URI (single for standalone, comma-separated for cluster)
   * @param channel the channel to subscribe to
   * @param retryOnFailure whether to retry subscription on failure
   * @param retryInterval interval between retry attempts
   * @param timeout connection timeout
   */
  public RedisPubSubReloadStrategy(
      String redisUri,
      String channel,
      boolean retryOnFailure,
      Duration retryInterval,
      Duration timeout) {
    this.redisUri = Objects.requireNonNull(redisUri, "redisUri must not be null");
    this.channel = Objects.requireNonNull(channel, "channel must not be null");
    this.retryOnFailure = retryOnFailure;
    this.retryInterval = retryInterval != null ? retryInterval : Duration.ofSeconds(5);
    this.timeout = timeout != null ? timeout : Duration.ofSeconds(5);
    this.isCluster = redisUri.contains(",");
  }

  /**
   * Creates a new Redis Pub/Sub reload strategy for standalone Redis.
   *
   * @param redisClient the Lettuce Redis client
   * @param channel the channel to subscribe to
   * @param retryOnFailure whether to retry subscription on failure
   * @param retryInterval interval between retry attempts
   */
  public RedisPubSubReloadStrategy(
      RedisClient redisClient, String channel, boolean retryOnFailure, Duration retryInterval) {
    this.redisClient = Objects.requireNonNull(redisClient, "redisClient must not be null");
    this.redisUri = null;
    this.channel = Objects.requireNonNull(channel, "channel must not be null");
    this.retryOnFailure = retryOnFailure;
    this.retryInterval = retryInterval != null ? retryInterval : Duration.ofSeconds(5);
    this.timeout = Duration.ofSeconds(5);
    this.isCluster = false;
  }

  /**
   * Creates a new Redis Pub/Sub reload strategy for Redis Cluster.
   *
   * @param clusterClient the Lettuce Redis cluster client
   * @param channel the channel to subscribe to
   * @param retryOnFailure whether to retry subscription on failure
   * @param retryInterval interval between retry attempts
   */
  public RedisPubSubReloadStrategy(
      RedisClusterClient clusterClient,
      String channel,
      boolean retryOnFailure,
      Duration retryInterval) {
    this.redisClient = Objects.requireNonNull(clusterClient, "clusterClient must not be null");
    this.redisUri = null;
    this.channel = Objects.requireNonNull(channel, "channel must not be null");
    this.retryOnFailure = retryOnFailure;
    this.retryInterval = retryInterval != null ? retryInterval : Duration.ofSeconds(5);
    this.timeout = Duration.ofSeconds(5);
    this.isCluster = true;
  }

  @Override
  protected ReloadSource getReloadSource() {
    return ReloadSource.PUBSUB;
  }

  @Override
  protected void doStart() {
    if (retryOnFailure) {
      retryScheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "fluxgate-pubsub-retry");
                t.setDaemon(true);
                return t;
              });
    }

    subscribe();
    log.info("Redis Pub/Sub reload strategy started on channel: {}", channel);
  }

  @Override
  protected void doStop() {
    StatefulRedisPubSubConnection<String, String> connection = connectionRef.getAndSet(null);
    if (connection != null) {
      try {
        connection.sync().unsubscribe(channel);
        connection.close();
      } catch (Exception e) {
        log.warn("Error closing Pub/Sub connection", e);
      }
    }

    if (retryScheduler != null) {
      retryScheduler.shutdown();
      try {
        if (!retryScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          retryScheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        retryScheduler.shutdownNow();
        Thread.currentThread().interrupt();
      }
      retryScheduler = null;
    }

    log.info("Redis Pub/Sub reload strategy stopped");
  }

  /** Creates the Redis client if not already created. */
  private void ensureClient() {
    if (redisClient != null) {
      return;
    }

    if (redisUri == null) {
      throw new IllegalStateException("No Redis URI or client provided");
    }

    if (isCluster) {
      List<RedisURI> uris =
          Arrays.stream(redisUri.split(",")).map(String::trim).map(this::createRedisUri).toList();
      redisClient = RedisClusterClient.create(uris);
      log.info("Created Redis Cluster client for Pub/Sub with {} nodes", uris.size());
    } else {
      RedisURI uri = createRedisUri(redisUri);
      redisClient = RedisClient.create(uri);
      log.info("Created Redis Standalone client for Pub/Sub");
    }
  }

  private RedisURI createRedisUri(String uri) {
    RedisURI redisURI = RedisURI.create(uri);
    redisURI.setTimeout(timeout);
    return redisURI;
  }

  /** Establishes the Pub/Sub subscription. */
  @SuppressWarnings("unchecked")
  private void subscribe() {
    try {
      ensureClient();

      StatefulRedisPubSubConnection<String, String> connection;
      if (isCluster) {
        connection = ((RedisClusterClient) redisClient).connectPubSub();
      } else {
        connection = ((RedisClient) redisClient).connectPubSub();
      }

      connection.addListener(
          new RedisPubSubAdapter<String, String>() {
            @Override
            public void message(String channel, String message) {
              handleMessage(message);
            }

            @Override
            public void subscribed(String channel, long count) {
              log.info("Subscribed to channel: {} (active subscriptions: {})", channel, count);
            }

            @Override
            public void unsubscribed(String channel, long count) {
              log.info("Unsubscribed from channel: {} (active subscriptions: {})", channel, count);
              if (isRunning() && retryOnFailure && count == 0) {
                scheduleRetry();
              }
            }
          });

      RedisPubSubCommands<String, String> sync = connection.sync();
      sync.subscribe(channel);

      connectionRef.set(connection);
    } catch (Exception e) {
      log.error("Failed to subscribe to Redis channel: {}", channel, e);
      if (retryOnFailure) {
        scheduleRetry();
      }
    }
  }

  /** Schedules a retry attempt for subscription. */
  private void scheduleRetry() {
    if (retryScheduler != null && !retryScheduler.isShutdown()) {
      log.info("Scheduling Pub/Sub subscription retry in {}", retryInterval);
      retryScheduler.schedule(
          () -> {
            if (isRunning()) {
              log.info("Retrying Pub/Sub subscription...");
              subscribe();
            }
          },
          retryInterval.toMillis(),
          TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Handles an incoming Pub/Sub message.
   *
   * <p>Supports two message formats:
   *
   * <ul>
   *   <li>JSON: {"ruleSetId": "xxx", "fullReload": false, ...}
   *   <li>Plain text: "*" for full reload, or ruleSetId directly
   * </ul>
   *
   * @param message the message received
   */
  private void handleMessage(String message) {
    log.debug("Received Pub/Sub message: {}", message);

    RuleReloadEvent event;
    if (message == null || message.isEmpty() || FULL_RELOAD_MESSAGE.equals(message)) {
      event = RuleReloadEvent.fullReload(ReloadSource.PUBSUB);
      log.info("Full reload triggered via Pub/Sub");
    } else if (message.trim().startsWith("{")) {
      // JSON format message
      event = parseJsonMessage(message);
    } else {
      // Plain text ruleSetId
      event = RuleReloadEvent.forRuleSet(message, ReloadSource.PUBSUB);
      log.info("Reload triggered via Pub/Sub for ruleSetId: {}", message);
    }

    notifyListeners(event);
  }

  /**
   * Parses a JSON format message.
   *
   * @param message the JSON message
   * @return the parsed reload event
   */
  private RuleReloadEvent parseJsonMessage(String message) {
    try {
      JsonNode root = OBJECT_MAPPER.readTree(message);

      boolean fullReload = root.path("fullReload").asBoolean(false);
      if (fullReload) {
        log.info("Full reload triggered via Pub/Sub (JSON)");
        return RuleReloadEvent.fullReload(ReloadSource.PUBSUB);
      }

      String ruleSetId = root.path("ruleSetId").asText(null);
      if (ruleSetId != null && !ruleSetId.isEmpty()) {
        log.info("Reload triggered via Pub/Sub for ruleSetId: {}", ruleSetId);
        return RuleReloadEvent.forRuleSet(ruleSetId, ReloadSource.PUBSUB);
      }

      log.warn("Invalid JSON message, triggering full reload: {}", message);
      return RuleReloadEvent.fullReload(ReloadSource.PUBSUB);
    } catch (JsonProcessingException e) {
      log.warn("Failed to parse JSON message, treating as ruleSetId: {}", message, e);
      return RuleReloadEvent.forRuleSet(message, ReloadSource.PUBSUB);
    }
  }

  /**
   * Returns the configured channel name.
   *
   * @return channel name
   */
  public String getChannel() {
    return channel;
  }

  /**
   * Returns whether retry on failure is enabled.
   *
   * @return true if retry is enabled
   */
  public boolean isRetryOnFailure() {
    return retryOnFailure;
  }

  /**
   * Returns the retry interval.
   *
   * @return retry interval
   */
  public Duration getRetryInterval() {
    return retryInterval;
  }

  /**
   * Checks if currently connected to Redis Pub/Sub.
   *
   * @return true if connected
   */
  public boolean isConnected() {
    StatefulRedisPubSubConnection<String, String> connection = connectionRef.get();
    return connection != null && connection.isOpen();
  }
}
