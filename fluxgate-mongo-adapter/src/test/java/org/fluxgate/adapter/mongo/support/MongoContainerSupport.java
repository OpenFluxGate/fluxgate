package org.fluxgate.adapter.mongo.support;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Resolves the MongoDB instance the integration tier talks to, in this order:
 *
 * <ol>
 *   <li>{@code -Dfluxgate.mongo.uri} or {@code FLUXGATE_MONGO_URI} when supplied — CI keeps its own
 *       service containers and no Docker-in-Docker is needed.
 *   <li>a singleton {@code mongo:7.0} container started once per JVM, when a Docker daemon is
 *       reachable.
 *   <li>otherwise the calling test is <em>skipped</em> through {@link Assumptions}, never failed.
 * </ol>
 *
 * <p>This class is the only place that knows a MongoDB credential, so no test source has to embed
 * one. Tests isolate themselves with {@link #uniqueCollectionName(String)} instead of dropping
 * shared collections: the resolved target may be a developer's shared MongoDB.
 */
public final class MongoContainerSupport {

  /** Root user of the disposable container, matching the user CI's service container creates. */
  public static final String ROOT_USER = "fluxgate";

  /**
   * Root password of the disposable container.
   *
   * <p>The {@code ci-test-only-} prefix marks it as a throwaway value for secret scanners and for
   * anyone reading a failure log; it never protects real data.
   */
  public static final String ROOT_PASSWORD = "ci-test-only-fluxgate";

  /** Database the rules and metrics collections live in. */
  public static final String DEFAULT_DATABASE = "fluxgate";

  private static final DockerImageName IMAGE = DockerImageName.parse("mongo:7.0");
  private static final int MONGO_PORT = 27017;

  private static final Object LOCK = new Object();

  private static GenericContainer<?> container;
  private static Boolean dockerAvailable;

  private MongoContainerSupport() {}

  /**
   * Returns the MongoDB connection string the integration tier should use.
   *
   * @return a driver-compatible {@code mongodb://} URI
   */
  public static String mongoUri() {
    String supplied = suppliedUri();
    if (supplied != null) {
      return supplied;
    }
    GenericContainer<?> mongo = sharedContainer();
    return "mongodb://"
        + ROOT_USER
        + ":"
        + ROOT_PASSWORD
        + "@"
        + mongo.getHost()
        + ":"
        + mongo.getMappedPort(MONGO_PORT)
        + "/"
        + DEFAULT_DATABASE
        + "?authSource=admin";
  }

  /**
   * Returns the database name the integration tier should use.
   *
   * @return the supplied database name, or {@value #DEFAULT_DATABASE}
   */
  public static String databaseName() {
    String name = System.getProperty("fluxgate.mongo.db");
    if (name == null || name.trim().isEmpty()) {
      name = System.getenv("FLUXGATE_MONGO_DB");
    }
    return name == null || name.trim().isEmpty() ? DEFAULT_DATABASE : name.trim();
  }

  /**
   * Returns a collection name unique to the calling test.
   *
   * <p>Per-test collections let a test drop its own collection without touching the data of a
   * shared MongoDB.
   *
   * @param baseName the logical collection name, such as {@code rate_limit_rules}
   * @return the base name with a short unique suffix appended
   */
  public static String uniqueCollectionName(String baseName) {
    return baseName + "_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }

  /**
   * Skips the calling test unless a MongoDB target is reachable.
   *
   * <p>Useful for tests that need the guard before they build any configuration.
   */
  public static void assumeMongoAvailable() {
    if (suppliedUri() == null) {
      sharedContainer();
    }
  }

  private static String suppliedUri() {
    String uri = System.getProperty("fluxgate.mongo.uri");
    if (uri == null || uri.trim().isEmpty()) {
      uri = System.getenv("FLUXGATE_MONGO_URI");
    }
    return uri == null || uri.trim().isEmpty() ? null : uri.trim();
  }

  private static GenericContainer<?> sharedContainer() {
    synchronized (LOCK) {
      Assumptions.assumeTrue(
          isDockerAvailable(),
          "Skipping MongoDB integration test: neither FLUXGATE_MONGO_URI /"
              + " -Dfluxgate.mongo.uri nor a Docker daemon for Testcontainers is available");
      if (container == null) {
        GenericContainer<?> mongo =
            new GenericContainer<>(IMAGE)
                .withExposedPorts(MONGO_PORT)
                .withEnv("MONGO_INITDB_ROOT_USERNAME", ROOT_USER)
                .withEnv("MONGO_INITDB_ROOT_PASSWORD", ROOT_PASSWORD)
                .withEnv("MONGO_INITDB_DATABASE", DEFAULT_DATABASE)
                .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 2))
                .withStartupTimeout(Duration.ofSeconds(120));
        mongo.start();
        // One container per JVM; the Testcontainers reaper removes it when the JVM exits.
        container = mongo;
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
