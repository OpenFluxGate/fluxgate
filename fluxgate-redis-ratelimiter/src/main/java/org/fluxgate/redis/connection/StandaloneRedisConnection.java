package org.fluxgate.redis.connection;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    this(redisUri, Duration.ofSeconds(5));
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

    log.info("Creating standalone Redis connection to: {}", maskPassword(redisUri));

    RedisURI uri = RedisURI.create(redisUri);
    this.redisClient = RedisClient.create(uri);
    this.redisClient.setDefaultTimeout(timeout);

    try {
      this.connection = redisClient.connect();
      this.commands = connection.sync();
      log.info("Standalone Redis connection established successfully");
    } catch (Exception e) {
      redisClient.close();
      throw new RedisConnectionException(
          "Failed to connect to Redis: " + maskPassword(redisUri), e);
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

  @Override
  public RedisMode getMode() {
    return RedisMode.STANDALONE;
  }

  @Override
  public boolean isConnected() {
    try {
      return connection != null && connection.isOpen() && "PONG".equals(commands.ping());
    } catch (Exception e) {
      log.warn("Connection check failed: {}", e.getMessage());
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
  @Deprecated
  public List<String> keys(String pattern) {
    return commands.keys(pattern);
  }

  @Override
  public List<String> scan(String pattern) {
    List<String> result = new ArrayList<>();
    ScanArgs scanArgs = ScanArgs.Builder.matches(pattern).limit(100);

    KeyScanCursor<String> cursor = commands.scan(scanArgs);
    result.addAll(cursor.getKeys());

    while (!cursor.isFinished()) {
      cursor = commands.scan(cursor, scanArgs);
      result.addAll(cursor.getKeys());
    }

    return result;
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
    try {
      if (connection != null) {
        connection.close();
      }
      if (redisClient != null) {
        redisClient.shutdown();
      }
      log.info("Standalone Redis connection closed");
    } catch (Exception e) {
      log.warn("Error closing Redis connection: {}", e.getMessage());
    }
  }

  /**
   * Returns the underlying Lettuce commands for advanced operations.
   *
   * @return the RedisCommands instance
   */
  public RedisCommands<String, String> getCommands() {
    return commands;
  }

  private String maskPassword(String uri) {
    if (uri == null) {
      return null;
    }
    // Mask password in URI for logging
    return uri.replaceAll("://[^:]+:[^@]+@", "://***:***@");
  }
}
