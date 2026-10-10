package org.fluxgate.spring.reload.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.fluxgate.core.reload.ReloadSource;
import org.fluxgate.core.reload.RuleReloadEvent;
import org.fluxgate.core.util.HmacSigner;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;
import org.fluxgate.redis.connection.RedisUriUtils;
import org.fluxgate.spring.util.LogSanitizer;

/**
 * Redis Pub/Sub based reload strategy for real-time rule change notifications.
 *
 * <p>This strategy subscribes to a Redis channel and listens for rule change messages. When a
 * message is received, it triggers a reload event to invalidate cached rules.
 *
 * <p>Message format:
 *
 * <ul>
 *   <li>JSON: <code>{"version":2,"ruleSetId":"xxx","fullReload":false,"nonce":"..."}</code> -
 *       {@code version} is optional and defaults to {@link #LEGACY_MESSAGE_SCHEMA_VERSION};
 *       versions 1 and 2 are understood; {@code fullReload} must be set explicitly to request a
 *       full reload
 *   <li>{@code "*"} - full reload, kept for backward compatibility with plain-text publishers
 *   <li>{@code "ruleSetId"} - reload that one rule set
 * </ul>
 *
 * <p><b>Anything else is logged at WARN and ignored.</b> The channel is an ordinary Redis channel
 * with no authentication, and on a shared Redis it can carry traffic from unrelated applications,
 * so an empty message, a schema mismatch or a parse failure must never be turned into the most
 * destructive action available - it used to trigger a full reload and wipe every bucket.
 *
 * <p><b>Signed messages.</b> Parsing hardening keeps an accidental message from doing damage; it
 * does nothing against a deliberate one, because {@code PUBLISH fluxgate:rule-reload '*'} is all a
 * full reset takes. Set {@code fluxgate.reload.pubsub.secret} to the same value as the publisher's
 * {@code fluxgate.control.secret} and this strategy accepts only JSON messages carrying a valid
 * HMAC-SHA256 {@code signature} whose {@code timestamp} lies inside {@code
 * fluxgate.reload.pubsub.max-message-age} (60s by default) and which it has not seen before.
 * Version 2 messages are verified over {@link HmacSigner#canonicalRuleChangeV2}, which binds the
 * channel this strategy subscribes to and a per-message {@code nonce}; the nonce is remembered for
 * the replay window, so a captured message published again is ignored. Version 1 messages (no
 * nonce, no channel binding) are still verified over {@link HmacSigner#canonicalRuleChange} and are
 * deduplicated by their signature, as long as {@link #setAcceptLegacySigned(boolean)} (starter
 * property {@code fluxgate.reload.pubsub.accept-legacy-signed}, {@code true} by default) allows
 * them; the first one accepted is reported at WARN, and once every publisher signs version 2 the
 * property should be set to {@code false}. Everything else - an unsigned message, a wrong
 * signature, a stale one, a replay, a version 2 message without a nonce, and the plain-text {@code
 * "*"} and {@code "ruleSetId"} forms - is logged at WARN and ignored. Both sides normalise the
 * secret with {@link HmacSigner#normalizeSecret(String)}, and a secret shorter than {@value
 * HmacSigner#MIN_RECOMMENDED_SECRET_BYTES} bytes is reported at WARN. Constructed without a secret,
 * this class accepts unsigned messages and says so in a single INFO line at startup; the starter
 * only does that when {@code fluxgate.reload.pubsub.allow-unsigned=true}; otherwise {@code AUTO}
 * polls instead and an explicit {@code PUBSUB} refuses to start (H-3, since 0.4).
 *
 * <p>Redis Pub/Sub is at-most-once, so a dropped message would leave this instance serving stale
 * rules. The starter therefore composes this strategy with a low-frequency polling backstop; see
 * {@code fluxgate.reload.pubsub.backstop-polling-interval}.
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
 *       backstop-polling-interval: 60s
 *       secret: ${FLUXGATE_RELOAD_SECRET}
 *       max-message-age: 60s
 * </pre>
 */
public class RedisPubSubReloadStrategy extends AbstractReloadStrategy {

  /** Message indicating a full reload should occur. */
  public static final String FULL_RELOAD_MESSAGE = "*";

  /**
   * Newest schema version understood by this strategy. Messages with a version other than this one
   * or {@link #LEGACY_MESSAGE_SCHEMA_VERSION} are dropped.
   */
  public static final int MESSAGE_SCHEMA_VERSION = 2;

  /** Schema version of a message without nonce or channel binding, and of one without a version. */
  public static final int LEGACY_MESSAGE_SCHEMA_VERSION = 1;

  /** Default replay window for signed messages. */
  public static final Duration DEFAULT_MAX_MESSAGE_AGE = Duration.ofSeconds(60);

  /**
   * Upper bound of remembered message identities. Only authentic messages are remembered, so
   * reaching it takes a control plane publishing this many changes inside one replay window; a
   * message that would exceed it is ignored rather than accepted without replay protection.
   */
  static final int MAX_REMEMBERED_MESSAGES = 100_000;

  /**
   * Messages waiting for the listener thread. A full one drops further messages with a WARN instead
   * of growing without bound when a publisher floods the channel; the backstop polling (when
   * enabled) still picks up the changes.
   */
  static final int MAX_PENDING_MESSAGES = 10_000;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /** Characters of an untrusted payload that reach the log. */
  private static final int MAX_LOGGED_MESSAGE = 256;

  /** Characters of an untrusted rule set id that reach the log. */
  private static final int MAX_LOGGED_RULE_SET_ID = 128;

  private final String redisUri;
  private final String channel;
  private final boolean retryOnFailure;
  private final Duration retryInterval;
  private final boolean isCluster;
  private final Duration timeout;

  /** Shared secret messages must be signed with, or null to accept unsigned messages. */
  private final String secret;

  /** Replay window applied to a signed message's timestamp. */
  private final Duration maxMessageAge;

  /**
   * Whether signed version 1 messages (no nonce, no channel binding) are still accepted. Defaults
   * to true for rolling upgrades from pre-0.4 control planes.
   */
  private volatile boolean acceptLegacySigned = true;

  /** Whether accepting a signed version 1 message has already been reported. */
  private final AtomicBoolean legacySignedWarned = new AtomicBoolean();

  /** Identity (nonce, or signature for version 1) of accepted signed messages -> forget-after. */
  private final ConcurrentHashMap<String, Long> seenMessages = new ConcurrentHashMap<>();

  /** Lettuce client; a {@link RedisClient} or a {@link RedisClusterClient}, created lazily. */
  private final AtomicReference<Object> redisClientRef = new AtomicReference<>();

  /** Whether this strategy created the client and is therefore responsible for shutting it down. */
  private final boolean ownsClient;

  private final AtomicReference<StatefulRedisPubSubConnection<String, String>> connectionRef =
      new AtomicReference<>();
  private final AtomicReference<ScheduledExecutorService> retrySchedulerRef =
      new AtomicReference<>();

  /**
   * Runs the reload listeners off the Lettuce event loop, one message at a time in arrival order. A
   * slow listener (a full bucket reset scanning a large keyspace) would otherwise block the thread
   * that reads the subscription and every other command multiplexed on that event loop.
   */
  private final AtomicReference<ExecutorService> messageExecutorRef = new AtomicReference<>();

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
    this(redisUri, channel, retryOnFailure, retryInterval, timeout, null, null);
  }

  /**
   * Creates a new Redis Pub/Sub reload strategy using URI, verifying message signatures.
   *
   * @param redisUri the Redis URI (single for standalone, comma-separated for cluster)
   * @param channel the channel to subscribe to
   * @param retryOnFailure whether to retry subscription on failure
   * @param retryInterval interval between retry attempts
   * @param timeout connection timeout
   * @param secret shared secret every message must be signed with, or null/blank to accept unsigned
   *     messages as before
   * @param maxMessageAge replay window for signed messages, or null for {@link
   *     #DEFAULT_MAX_MESSAGE_AGE}
   */
  public RedisPubSubReloadStrategy(
      String redisUri,
      String channel,
      boolean retryOnFailure,
      Duration retryInterval,
      Duration timeout,
      String secret,
      Duration maxMessageAge) {
    this.redisUri = Objects.requireNonNull(redisUri, "redisUri must not be null");
    this.channel = Objects.requireNonNull(channel, "channel must not be null");
    this.retryOnFailure = retryOnFailure;
    this.retryInterval = retryInterval != null ? retryInterval : Duration.ofSeconds(5);
    this.timeout = timeout != null ? timeout : Duration.ofSeconds(5);
    this.isCluster = RedisUriUtils.detectMode(redisUri) == RedisMode.CLUSTER;
    this.ownsClient = true;
    this.secret = normalizeSecret(secret);
    this.maxMessageAge = maxMessageAge != null ? maxMessageAge : DEFAULT_MAX_MESSAGE_AGE;
  }

  /**
   * Creates a new Redis Pub/Sub reload strategy for standalone Redis.
   *
   * <p>The caller keeps ownership of the client: {@link #stop()} closes the subscription but does
   * not shut the client down.
   *
   * @param redisClient the Lettuce Redis client
   * @param channel the channel to subscribe to
   * @param retryOnFailure whether to retry subscription on failure
   * @param retryInterval interval between retry attempts
   */
  public RedisPubSubReloadStrategy(
      RedisClient redisClient, String channel, boolean retryOnFailure, Duration retryInterval) {
    this(redisClient, channel, retryOnFailure, retryInterval, null, null);
  }

  /**
   * Creates a new Redis Pub/Sub reload strategy for standalone Redis, verifying message signatures.
   *
   * @param redisClient the Lettuce Redis client
   * @param channel the channel to subscribe to
   * @param retryOnFailure whether to retry subscription on failure
   * @param retryInterval interval between retry attempts
   * @param secret shared secret every message must be signed with, or null/blank to accept unsigned
   *     messages as before
   * @param maxMessageAge replay window for signed messages, or null for {@link
   *     #DEFAULT_MAX_MESSAGE_AGE}
   */
  public RedisPubSubReloadStrategy(
      RedisClient redisClient,
      String channel,
      boolean retryOnFailure,
      Duration retryInterval,
      String secret,
      Duration maxMessageAge) {
    this.redisClientRef.set(Objects.requireNonNull(redisClient, "redisClient must not be null"));
    this.redisUri = null;
    this.channel = Objects.requireNonNull(channel, "channel must not be null");
    this.retryOnFailure = retryOnFailure;
    this.retryInterval = retryInterval != null ? retryInterval : Duration.ofSeconds(5);
    this.timeout = Duration.ofSeconds(5);
    this.isCluster = false;
    this.ownsClient = false;
    this.secret = normalizeSecret(secret);
    this.maxMessageAge = maxMessageAge != null ? maxMessageAge : DEFAULT_MAX_MESSAGE_AGE;
  }

  /**
   * Creates a new Redis Pub/Sub reload strategy for Redis Cluster.
   *
   * <p>The caller keeps ownership of the client: {@link #stop()} closes the subscription but does
   * not shut the client down.
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
    this(clusterClient, channel, retryOnFailure, retryInterval, null, null);
  }

  /**
   * Creates a new Redis Pub/Sub reload strategy for Redis Cluster, verifying message signatures.
   *
   * @param clusterClient the Lettuce Redis cluster client
   * @param channel the channel to subscribe to
   * @param retryOnFailure whether to retry subscription on failure
   * @param retryInterval interval between retry attempts
   * @param secret shared secret every message must be signed with, or null/blank to accept unsigned
   *     messages as before
   * @param maxMessageAge replay window for signed messages, or null for {@link
   *     #DEFAULT_MAX_MESSAGE_AGE}
   */
  public RedisPubSubReloadStrategy(
      RedisClusterClient clusterClient,
      String channel,
      boolean retryOnFailure,
      Duration retryInterval,
      String secret,
      Duration maxMessageAge) {
    this.redisClientRef.set(
        Objects.requireNonNull(clusterClient, "clusterClient must not be null"));
    this.redisUri = null;
    this.channel = Objects.requireNonNull(channel, "channel must not be null");
    this.retryOnFailure = retryOnFailure;
    this.retryInterval = retryInterval != null ? retryInterval : Duration.ofSeconds(5);
    this.timeout = Duration.ofSeconds(5);
    this.isCluster = true;
    this.ownsClient = false;
    this.secret = normalizeSecret(secret);
    this.maxMessageAge = maxMessageAge != null ? maxMessageAge : DEFAULT_MAX_MESSAGE_AGE;
  }

  private static String normalizeSecret(String secret) {
    return HmacSigner.normalizeSecret(secret);
  }

  @Override
  protected ReloadSource getReloadSource() {
    return ReloadSource.PUBSUB;
  }

  @Override
  protected void doStart() {
    messageExecutorRef.set(
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(MAX_PENDING_MESSAGES),
            r -> {
              Thread t = new Thread(r, "fluxgate-pubsub-listener");
              t.setDaemon(true);
              return t;
            }));

    if (retryOnFailure) {
      retrySchedulerRef.set(
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "fluxgate-pubsub-retry");
                t.setDaemon(true);
                return t;
              }));
    }

    subscribe();
    if (secret != null) {
      log.info(
          "Redis Pub/Sub reload strategy started on channel: {} (verifying HMAC-SHA256 signatures, "
              + "replay window {})",
          channel,
          maxMessageAge);
      if (HmacSigner.isWeakSecret(secret)) {
        log.warn(
            "fluxgate.reload.pubsub.secret is shorter than {} bytes. A short HMAC secret can be "
                + "brute-forced offline from a single captured message; use at least {} random bytes.",
            HmacSigner.MIN_RECOMMENDED_SECRET_BYTES,
            HmacSigner.MIN_RECOMMENDED_SECRET_BYTES);
      }
    } else {
      log.info("Redis Pub/Sub reload strategy started on channel: {}", channel);
      log.info(
          "fluxgate.reload.pubsub.secret is not set: any publisher able to reach this Redis can "
              + "trigger a full reload on channel '{}' and drop every token bucket. Set it here and "
              + "fluxgate.control.secret on the control plane to the same value.",
          channel);
    }
  }

  @Override
  protected void doStop() {
    closeConnection(connectionRef.getAndSet(null), true);
    shutdownAndAwait(messageExecutorRef.getAndSet(null));

    shutdownAndAwait(retrySchedulerRef.getAndSet(null));

    // The client owns a Netty event loop group: leaking it leaks threads and file descriptors on
    // every context restart.
    if (ownsClient) {
      shutdownClient(redisClientRef.getAndSet(null));
    }

    log.info("Redis Pub/Sub reload strategy stopped");
  }

  /** Shuts an executor down, waiting up to five seconds for the running task. */
  private static void shutdownAndAwait(ExecutorService executor) {
    if (executor == null) {
      return;
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /** Creates the Redis client if not already created. */
  private Object ensureClient() {
    Object existing = redisClientRef.get();
    if (existing != null) {
      return existing;
    }

    if (redisUri == null) {
      throw new IllegalStateException("No Redis URI or client provided");
    }

    Object created;
    if (isCluster) {
      List<RedisURI> uris =
          RedisUriUtils.splitNodes(redisUri).stream()
              .map(this::createRedisUri)
              .collect(Collectors.toList());
      created = RedisClusterClient.create(uris);
      log.info("Created Redis Cluster client for Pub/Sub with {} nodes", uris.size());
    } else {
      created = RedisClient.create(createRedisUri(redisUri));
      log.info("Created Redis Standalone client for Pub/Sub");
    }

    if (!redisClientRef.compareAndSet(null, created)) {
      // Another thread won the race; discard ours rather than leaking it.
      shutdownClient(created);
      return redisClientRef.get();
    }
    return created;
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

  /** Establishes the Pub/Sub subscription. */
  private void subscribe() {
    try {
      Object client = ensureClient();

      StatefulRedisPubSubConnection<String, String> connection;
      if (isCluster) {
        connection = ((RedisClusterClient) client).connectPubSub();
      } else {
        connection = ((RedisClient) client).connectPubSub();
      }

      connection.addListener(
          new RedisPubSubAdapter<String, String>() {
            @Override
            public void message(String channel, String message) {
              dispatch(message);
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

      // A retry must not leave the previous connection open: a flapping Redis would otherwise pile
      // them up until the process runs out of connections.
      closeConnection(connectionRef.getAndSet(connection), false);
    } catch (Exception e) {
      log.error("Failed to subscribe to Redis channel: {}", channel, e);
      if (retryOnFailure) {
        scheduleRetry();
      }
    }
  }

  /** Unsubscribes and closes a Pub/Sub connection, ignoring failures. */
  private void closeConnection(
      StatefulRedisPubSubConnection<String, String> connection, boolean unsubscribe) {
    if (connection == null) {
      return;
    }
    try {
      if (unsubscribe && connection.isOpen()) {
        connection.sync().unsubscribe(channel);
      }
      connection.close();
    } catch (Exception e) {
      log.warn("Error closing Pub/Sub connection", e);
    }
  }

  /** Shuts down a Lettuce client, ignoring failures. */
  private void shutdownClient(Object client) {
    if (client == null) {
      return;
    }
    try {
      if (client instanceof RedisClusterClient) {
        ((RedisClusterClient) client).shutdown();
      } else if (client instanceof RedisClient) {
        ((RedisClient) client).shutdown();
      }
    } catch (Exception e) {
      log.warn("Error shutting down Redis Pub/Sub client", e);
    }
  }

  /** Schedules a retry attempt for subscription. */
  private void scheduleRetry() {
    ScheduledExecutorService scheduler = retrySchedulerRef.get();
    if (scheduler == null || scheduler.isShutdown()) {
      return;
    }
    log.info("Scheduling Pub/Sub subscription retry in {}", retryInterval);
    try {
      scheduler.schedule(
          () -> {
            if (isRunning()) {
              log.info("Retrying Pub/Sub subscription...");
              subscribe();
            }
          },
          retryInterval.toMillis(),
          TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException ignored) {
      // Scheduler was shut down concurrently; retry silently dropped.
      log.debug("Pub/Sub retry scheduling rejected (scheduler shutting down)");
    }
  }

  /**
   * Hands a message received on the Lettuce event loop to the listener thread, which handles the
   * messages one by one in the order they arrived.
   */
  private void dispatch(String message) {
    ExecutorService executor = messageExecutorRef.get();
    if (executor == null) {
      log.debug("Ignoring Pub/Sub message received while stopped");
      return;
    }
    try {
      executor.execute(() -> handleMessage(message));
    } catch (RejectedExecutionException e) {
      if (executor.isShutdown()) {
        log.debug("Ignoring Pub/Sub message received while stopping");
      } else {
        log.warn(
            "Dropping a rule reload message on channel {}: {} messages are already waiting",
            channel,
            MAX_PENDING_MESSAGES);
      }
    }
  }

  /**
   * Handles an incoming Pub/Sub message, ignoring anything it cannot understand.
   *
   * @param message the message received
   */
  private void handleMessage(String message) {
    log.debug("Received Pub/Sub message: {}", sanitizeMessage(message));
    parseMessage(message).ifPresent(this::notifyListeners);
  }

  /**
   * Parses a Pub/Sub message into a reload event.
   *
   * <p>Package-private so the message table can be tested without a Redis connection.
   *
   * @param message the raw message
   * @return the event to publish, or empty when the message must be ignored
   */
  Optional<RuleReloadEvent> parseMessage(String message) {
    if (message == null || message.trim().isEmpty()) {
      log.warn("Ignoring empty rule reload message on channel {}", channel);
      return Optional.empty();
    }

    String trimmed = message.trim();

    if (secret != null && !trimmed.startsWith("{")) {
      // Only the JSON form can carry a signature, so the plain-text forms - including the legacy
      // "*" full reload - are exactly the shapes an attacker would use.
      log.warn(
          "Ignoring unsigned rule reload message on channel {} (a secret is configured): {}",
          channel,
          sanitizeMessage(trimmed));
      return Optional.empty();
    }

    if (FULL_RELOAD_MESSAGE.equals(trimmed)) {
      log.info("Full reload triggered via Pub/Sub");
      return Optional.of(fullReloadEvent());
    }

    // Anything that looks like JSON goes through the JSON path, so a JSON array or a malformed
    // object is dropped rather than used verbatim as a rule set id.
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      return parseJsonMessage(trimmed);
    }

    log.info("Reload triggered via Pub/Sub for ruleSetId: {}", sanitizeRuleSetId(trimmed));
    return Optional.of(RuleReloadEvent.forRuleSet(trimmed, ReloadSource.PUBSUB));
  }

  /**
   * Parses a JSON format message.
   *
   * @param message the JSON message
   * @return the parsed reload event, or empty when the message must be ignored
   */
  private Optional<RuleReloadEvent> parseJsonMessage(String message) {
    JsonNode root;
    try {
      root = OBJECT_MAPPER.readTree(message);
    } catch (Exception e) {
      log.warn(
          "Ignoring unparseable rule reload message on channel {}: {} ({})",
          channel,
          sanitizeMessage(message),
          e.getMessage());
      return Optional.empty();
    }

    if (root == null || !root.isObject()) {
      log.warn(
          "Ignoring non-object rule reload message on channel {}: {}",
          channel,
          sanitizeMessage(message));
      return Optional.empty();
    }

    int version = root.path("version").asInt(LEGACY_MESSAGE_SCHEMA_VERSION);
    if (version != MESSAGE_SCHEMA_VERSION && version != LEGACY_MESSAGE_SCHEMA_VERSION) {
      log.warn(
          "Ignoring rule reload message with unknown schema version {} on channel {}: {}",
          version,
          channel,
          sanitizeMessage(message));
      return Optional.empty();
    }

    boolean fullReload = root.path("fullReload").asBoolean(false);
    String ruleSetId = root.path("ruleSetId").asText(null);

    if (secret != null
        && !isAuthentic(
            message,
            version,
            ruleSetId,
            fullReload,
            root.path("timestamp").asLong(0L),
            root.path("source").asText(null),
            root.path("nonce").asText(null),
            root.path("signature").asText(null))) {
      return Optional.empty();
    }

    if (fullReload) {
      log.info("Full reload triggered via Pub/Sub (JSON)");
      return Optional.of(fullReloadEvent());
    }

    if (ruleSetId != null && !ruleSetId.trim().isEmpty()) {
      log.info("Reload triggered via Pub/Sub for ruleSetId: {}", sanitizeRuleSetId(ruleSetId));
      return Optional.of(RuleReloadEvent.forRuleSet(ruleSetId, ReloadSource.PUBSUB));
    }

    log.warn(
        "Ignoring rule reload message without ruleSetId and without fullReload on channel {}: {}",
        channel,
        sanitizeMessage(message));
    return Optional.empty();
  }

  /**
   * Checks the signature, the replay window and the replay cache of a message.
   *
   * <p>Only called when a secret is configured. A missing signature, a signature computed with
   * another secret or for another channel, a tampered field, a timestamp outside the replay window
   * and a message already seen all end here, because from the outside they are the same thing: a
   * message this data plane did not authorise (this time).
   *
   * @return true when the message may be acted on
   */
  private boolean isAuthentic(
      String message,
      int version,
      String ruleSetId,
      boolean fullReload,
      long timestamp,
      String source,
      String nonce,
      String signature) {

    if (signature == null || signature.trim().isEmpty()) {
      log.warn(
          "Ignoring unsigned rule reload message on channel {} (a secret is configured): {}",
          channel,
          sanitizeMessage(message));
      return false;
    }

    boolean legacy = version == LEGACY_MESSAGE_SCHEMA_VERSION;
    if (legacy && !acceptLegacySigned) {
      log.warn(
          "Ignoring signed version 1 rule reload message on channel {}: "
              + "fluxgate.reload.pubsub.accept-legacy-signed=false (version 1 binds neither the "
              + "channel nor a nonce): {}",
          channel,
          sanitizeMessage(message));
      return false;
    }
    if (!legacy && (nonce == null || nonce.trim().isEmpty())) {
      log.warn(
          "Ignoring version {} rule reload message without a nonce on channel {}: {}",
          version,
          channel,
          sanitizeMessage(message));
      return false;
    }

    String canonical =
        legacy
            ? HmacSigner.canonicalRuleChange(version, ruleSetId, fullReload, timestamp, source)
            : HmacSigner.canonicalRuleChangeV2(
                version, channel, ruleSetId, fullReload, timestamp, source, nonce);
    if (!HmacSigner.verify(secret, canonical, signature)) {
      log.warn(
          "Ignoring rule reload message with an invalid signature on channel {}: {}",
          channel,
          sanitizeMessage(message));
      return false;
    }

    long ageMillis = System.currentTimeMillis() - timestamp;
    if (Math.abs(ageMillis) > maxMessageAge.toMillis()) {
      log.warn(
          "Ignoring rule reload message on channel {}: its timestamp is {}ms away from now, outside "
              + "the {} replay window",
          channel,
          ageMillis,
          maxMessageAge);
      return false;
    }

    // Only an authentic, fresh message reaches the cache, so a forger cannot fill it.
    String identity =
        legacy ? "sig:" + signature.trim().toLowerCase(Locale.ROOT) : "nonce:" + nonce;
    if (!rememberFirstDelivery(identity, timestamp)) {
      log.warn(
          "Ignoring replayed rule reload message on channel {} (already processed inside the {} "
              + "replay window): {}",
          channel,
          maxMessageAge,
          sanitizeMessage(message));
      return false;
    }

    if (legacy && legacySignedWarned.compareAndSet(false, true)) {
      log.warn(
          "Accepted a signed version 1 rule reload message on channel {}. Version 1 binds neither "
              + "the channel nor a nonce; upgrade the publisher (fluxgate-control-support 0.4+ "
              + "signs version 2) and then set fluxgate.reload.pubsub.accept-legacy-signed=false. "
              + "Reported once.",
          channel);
    }
    return true;
  }

  /**
   * Records a message identity for the rest of the replay window.
   *
   * <p>The identity has to be remembered for as long as the timestamp check would still accept the
   * message, which is until {@code timestamp + maxMessageAge}; the local clock is used as the floor
   * so a message from a publisher whose clock runs behind is not forgotten early.
   *
   * @return true on the first delivery, false for a replay (or when the cache is full)
   */
  private boolean rememberFirstDelivery(String identity, long timestamp) {
    long now = System.currentTimeMillis();
    seenMessages.values().removeIf(forgetAfter -> forgetAfter < now);
    if (seenMessages.size() >= MAX_REMEMBERED_MESSAGES) {
      log.warn(
          "Replay cache is full ({} messages inside the {} window); ignoring the message",
          MAX_REMEMBERED_MESSAGES,
          maxMessageAge);
      return false;
    }
    long forgetAfter = Math.max(now, timestamp) + maxMessageAge.toMillis();
    return seenMessages.putIfAbsent(identity, forgetAfter) == null;
  }

  /** Builds a full reload event with the flag set explicitly rather than relying on a null id. */
  private RuleReloadEvent fullReloadEvent() {
    return RuleReloadEvent.builder().source(ReloadSource.PUBSUB).fullReload(true).build();
  }

  /** Caps and strips control characters from an untrusted payload before it reaches the log. */
  private static String sanitizeMessage(String message) {
    return LogSanitizer.sanitize(message, MAX_LOGGED_MESSAGE);
  }

  /** Caps and strips control characters from an untrusted rule set id before it reaches the log. */
  private static String sanitizeRuleSetId(String ruleSetId) {
    return LogSanitizer.sanitize(ruleSetId, MAX_LOGGED_RULE_SET_ID);
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
   * Returns whether this strategy requires messages to be signed.
   *
   * @return true when a secret is configured
   */
  public boolean isVerifyingSignatures() {
    return secret != null;
  }

  /**
   * Sets whether signed version 1 messages are accepted (only relevant when a secret is
   * configured).
   *
   * @param acceptLegacySigned false to accept only version 2 signed messages
   * @since 0.4.0
   */
  public void setAcceptLegacySigned(boolean acceptLegacySigned) {
    this.acceptLegacySigned = acceptLegacySigned;
  }

  /**
   * Returns whether signed version 1 messages are accepted.
   *
   * @return true when version 1 signed messages are accepted (the default)
   * @since 0.4.0
   */
  public boolean isAcceptingLegacySigned() {
    return acceptLegacySigned;
  }

  /**
   * Returns the replay window applied to signed messages.
   *
   * @return the maximum accepted message age
   */
  public Duration getMaxMessageAge() {
    return maxMessageAge;
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
