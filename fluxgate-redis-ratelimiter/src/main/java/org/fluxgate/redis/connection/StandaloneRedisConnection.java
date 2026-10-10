package org.fluxgate.redis.connection;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
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
 * Standalone (single-node) Redis connection implementation.
 *
 * <p>This implementation uses Lettuce's {@link RedisClient} for connecting to a single Redis node.
 */
public class StandaloneRedisConnection implements RedisConnectionProvider {

  private static final Logger log = LoggerFactory.getLogger(StandaloneRedisConnection.class);

  private final RedisClient redisClient;
  private final StatefulRedisConnection<String, String> connection;
  private final RedisCommands<String, String> commands;

  /**
   * Creates a new standalone Redis connection.
   *
   * @param redisUri the Redis URI (e.g., "redis://localhost:6379")
   */
  public StandaloneRedisConnection(String redisUri) {
    this(redisUri, RedisUriUtils.DEFAULT_TIMEOUT);
  }

  /**
   * Creates a new standalone Redis connection with custom timeout.
   *
   * @param redisUri the Redis URI
   * @param timeout the connection timeout
   */
  public StandaloneRedisConnection(String redisUri, Duration timeout) {
    Objects.requireNonNull(redisUri, "redisUri must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");

    log.info("Creating standalone Redis connection to: {}", RedisUriUtils.mask(redisUri));

    RedisURI uri = RedisUriUtils.parse(redisUri);
    uri.setTimeout(timeout);
    this.redisClient = RedisClient.create(uri);
    this.redisClient.setOptions(
        ClientOptions.builder()
            .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
            .build());
    this.redisClient.setDefaultTimeout(timeout);

    try {
      this.connection = redisClient.connect();
      this.commands = connection.sync();
      log.info("Standalone Redis connection established successfully");
    } catch (Exception e) {
      redisClient.close();
      throw new org.fluxgate.core.exception.RedisConnectionException(
          "Failed to connect to Redis",
          RedisUriUtils.mask(redisUri),
          e,
          org.fluxgate.core.exception.RedisConnectionException.Phase.CONNECT);
    }
  }

  /**
   * Creates a new standalone Redis connection from existing Lettuce commands. Useful for testing
   * and when connection is managed externally.
   *
   * @param commands the Lettuce RedisCommands instance
   */
  public StandaloneRedisConnection(RedisCommands<String, String> commands) {
    Objects.requireNonNull(commands, "commands must not be null");
    this.redisClient = null;
    this.connection = null;
    this.commands = commands;
    log.debug("Standalone Redis connection created from existing commands");
  }

  /**
   * Visible for testing: wraps Lettuce objects that were created elsewhere, so that shutdown
   * behaviour can be exercised without a Redis server.
   */
  StandaloneRedisConnection(
      RedisClient redisClient,
      StatefulRedisConnection<String, String> connection,
      RedisCommands<String, String> commands) {
    this.redisClient = redisClient;
    this.connection = connection;
    this.commands = commands;
  }

  @Override
  public RedisMode getMode() {
    return RedisMode.STANDALONE;
  }

  @Override
  public boolean isConnected() {
    try {
      // connection == null means the commands were supplied from outside and this class does not
      // own a StatefulRedisConnection to inspect; PING alone is then the whole answer.
      return (connection == null || connection.isOpen()) && "PONG".equals(commands.ping());
    } catch (Exception e) {
      log.warn("Connection check failed", e);
      return false;
    }
  }

  @Override
  public String scriptLoad(String script) {
    Objects.requireNonNull(script, "script must not be null");
    return commands.scriptLoad(script);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T evalsha(String sha, String[] keys, String[] args) {
    Objects.requireNonNull(sha, "sha must not be null");
    Objects.requireNonNull(keys, "keys must not be null");
    Objects.requireNonNull(args, "args must not be null");

    return (T) commands.evalsha(sha, ScriptOutputType.MULTI, keys, args);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T eval(String script, String[] keys, String[] args) {
    Objects.requireNonNull(script, "script must not be null");
    Objects.requireNonNull(keys, "keys must not be null");
    Objects.requireNonNull(args, "args must not be null");

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
    return commands.keys(pattern);
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
    return commands.flushdb();
  }

  @Override
  public String ping() {
    return commands.ping();
  }

  @Override
  public List<String> clusterNodes() {
    // Not applicable for standalone mode
    return Collections.emptyList();
  }

  @Override
  public void close() {
    log.info("Closing standalone Redis connection");

    // Independent blocks: a connection that fails to close must not leak the client's Netty
    // event loop group along with it.
    try {
      if (connection != null) {
        connection.close();
      }
    } catch (Exception e) {
      log.warn("Error closing Redis connection", e);
    }

    try {
      if (redisClient != null) {
        redisClient.shutdown();
      }
    } catch (Exception e) {
      log.warn("Error shutting down Redis client", e);
    }

    log.info("Standalone Redis connection closed");
  }

  /**
   * Returns the underlying Lettuce commands for advanced operations.
   *
   * @return the RedisCommands instance
   */
  public RedisCommands<String, String> getCommands() {
    return commands;
  }
}
