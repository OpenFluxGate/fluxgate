package org.fluxgate.redis.connection;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating Redis connections based on configuration.
 *
 * <p>Automatically detects whether to use standalone or cluster mode based on the provided URIs.
 */
public final class RedisConnectionFactory {

  private static final Logger log = LoggerFactory.getLogger(RedisConnectionFactory.class);

  private RedisConnectionFactory() {
    // Utility class
  }

  /**
   * Creates a Redis connection based on the provided URI.
   *
   * <p>Automatically detects cluster mode if the URI contains commas (multiple nodes).
   *
   * @param uri single URI or comma-separated URIs for cluster
   * @return the appropriate Redis connection provider
   */
  public static RedisConnectionProvider create(String uri) {
    return create(uri, RedisUriUtils.DEFAULT_TIMEOUT);
  }

  /**
   * Creates a Redis connection based on the provided URI with custom timeout.
   *
   * @param uri single URI or comma-separated URIs for cluster
   * @param timeout connection timeout
   * @return the appropriate Redis connection provider
   */
  public static RedisConnectionProvider create(String uri, Duration timeout) {
    Objects.requireNonNull(uri, "uri must not be null");

    // Check if it's a cluster configuration (comma-separated nodes)
    if (RedisUriUtils.detectMode(uri) == RedisConnectionProvider.RedisMode.CLUSTER) {
      List<String> nodes = RedisUriUtils.splitNodes(uri);
      log.info("Detected cluster mode with {} nodes", nodes.size());
      return new ClusterRedisConnection(nodes, timeout);
    }

    log.info("Using standalone mode");
    return new StandaloneRedisConnection(uri, timeout);
  }

  /**
   * Creates a Redis connection with explicit mode selection.
   *
   * @param mode the desired Redis mode
   * @param uris the Redis URIs (single for standalone, multiple for cluster)
   * @param timeout connection timeout
   * @return the appropriate Redis connection provider
   */
  public static RedisConnectionProvider create(
      RedisConnectionProvider.RedisMode mode, List<String> uris, Duration timeout) {
    Objects.requireNonNull(mode, "mode must not be null");
    Objects.requireNonNull(uris, "uris must not be null");

    if (uris.isEmpty()) {
      throw new IllegalArgumentException("At least one Redis URI is required");
    }

    switch (mode) {
      case STANDALONE:
        if (uris.size() > 1) {
          log.warn("Multiple URIs provided for standalone mode, using first: {}", uris.get(0));
        }
        return new StandaloneRedisConnection(uris.get(0), timeout);
      case CLUSTER:
        return new ClusterRedisConnection(uris, timeout);
      default:
        throw new IllegalArgumentException("Unknown Redis mode: " + mode);
    }
  }

  /**
   * Detects the Redis mode from a URI string.
   *
   * @param uri the URI to check
   * @return the detected Redis mode
   * @see RedisUriUtils#detectMode(String)
   */
  public static RedisConnectionProvider.RedisMode detectMode(String uri) {
    return RedisUriUtils.detectMode(uri);
  }
}
