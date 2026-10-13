package org.fluxgate.redis.support;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Resolves the Redis instance the integration tier talks to, in this order:
 *
 * <ol>
 *   <li>{@code -Dfluxgate.redis.uri} or {@code FLUXGATE_REDIS_URI} when supplied — CI keeps its own
 *       service containers and no Docker-in-Docker is needed.
 *   <li>a singleton {@code redis:7-alpine} container started once per JVM, when a Docker daemon is
 *       reachable.
 *   <li>otherwise the calling test is <em>skipped</em> through {@link Assumptions}, never failed.
 * </ol>
 *
 * <p>Tests must clean up with {@link #deleteKeys(RedisConnectionProvider, String)}, which refuses
 * any pattern outside {@value #KEY_PREFIX}. Calling {@code flushdb()} from a test is forbidden: the
 * resolved target may be a developer's shared Redis.
 */
public final class RedisContainerSupport {

  /** Prefix shared by every FluxGate key; cleanup never looks outside it. */
  public static final String KEY_PREFIX = "fluxgate:";

  private static final DockerImageName IMAGE = DockerImageName.parse("redis:7-alpine");
  private static final int REDIS_PORT = 6379;
  private static final long SCAN_BATCH = 500L;

  private static final Object LOCK = new Object();

  private static GenericContainer<?> container;
  private static Boolean dockerAvailable;

  private RedisContainerSupport() {}

  /**
   * Returns the Redis URI the integration tier should use.
   *
   * @return a Lettuce-compatible {@code redis://} URI
   */
  public static String redisUri() {
    String supplied = suppliedUri();
    if (supplied != null) {
      return supplied;
    }
    GenericContainer<?> redis = sharedContainer();
    return "redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT);
  }

  /**
   * Returns a short token that makes every key of this JVM run unique.
   *
   * <p>Tests build rule set ids from it so that a rerun against the same Redis cannot inherit
   * buckets from the previous run.
   *
   * @return an 8 character hexadecimal token
   */
  public static String newRunId() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }

  /**
   * Deletes the keys matching the given pattern with SCAN + DEL.
   *
   * @param provider the connection to issue the commands on
   * @param pattern a glob pattern that must start with {@value #KEY_PREFIX}
   * @throws IllegalArgumentException if the pattern could match keys outside the FluxGate namespace
   */
  public static void deleteKeys(RedisConnectionProvider provider, String pattern) {
    if (provider == null || pattern == null) {
      return;
    }
    if (!pattern.startsWith(KEY_PREFIX)) {
      throw new IllegalArgumentException(
          "Refusing to delete keys outside the " + KEY_PREFIX + " namespace: " + pattern);
    }
    List<String> keys = provider.scanKeys(pattern, SCAN_BATCH);
    if (!keys.isEmpty()) {
      provider.del(keys.toArray(new String[0]));
    }
  }

  /**
   * Skips the calling test unless a Redis target is reachable.
   *
   * <p>Useful for tests that need the guard before they build any configuration.
   */
  public static void assumeRedisAvailable() {
    if (suppliedUri() == null) {
      sharedContainer();
    }
  }

  private static String suppliedUri() {
    String uri = System.getProperty("fluxgate.redis.uri");
    if (uri == null || uri.trim().isEmpty()) {
      uri = System.getenv("FLUXGATE_REDIS_URI");
    }
    return uri == null || uri.trim().isEmpty() ? null : uri.trim();
  }

  private static GenericContainer<?> sharedContainer() {
    synchronized (LOCK) {
      Assumptions.assumeTrue(
          isDockerAvailable(),
          "Skipping Redis integration test: neither FLUXGATE_REDIS_URI /"
              + " -Dfluxgate.redis.uri nor a Docker daemon for Testcontainers is available");
      if (container == null) {
        GenericContainer<?> redis =
            new GenericContainer<>(IMAGE)
                .withExposedPorts(REDIS_PORT)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
                .withStartupTimeout(Duration.ofSeconds(60));
        redis.start();
        // One container per JVM; the Testcontainers reaper removes it when the JVM exits.
        container = redis;
      }
      return container;
    }
  }

  private static boolean isDockerAvailable() {
    synchronized (LOCK) {
      if (dockerAvailable == null) {
        try {
          dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException | LinkageError e) {
          dockerAvailable = Boolean.FALSE;
        }
      }
      return dockerAvailable;
    }
  }
}
