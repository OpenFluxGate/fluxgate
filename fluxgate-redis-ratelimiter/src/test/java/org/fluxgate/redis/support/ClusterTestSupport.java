package org.fluxgate.redis.support;

import org.junit.jupiter.api.Assumptions;

/**
 * Resolves the Redis Cluster the {@code redis-cluster-it} tier talks to.
 *
 * <p>Defaults to the cluster of {@code docker/redis-cluster.yml} (ports 7100-7102). Set {@code
 * -Dfluxgate.redis.cluster.uri} or {@code FLUXGATE_REDIS_CLUSTER_URI} to a comma-separated node
 * list to run against a cluster published on other ports, for example when several checkouts run
 * their cluster tests side by side.
 *
 * <p>When no cluster answers, the cluster tests skip themselves - unless {@code
 * -Dfluxgate.redis.cluster.require=true} (or {@code FLUXGATE_REDIS_CLUSTER_REQUIRE=true}) is set,
 * which turns the skip into a failure. A CI job that starts a cluster sets it, so a cluster that
 * did not come up cannot pass the gate with every test skipped.
 */
public final class ClusterTestSupport {

  /** Node list of the cluster started by {@code docker/redis-cluster.yml}. */
  public static final String DEFAULT_CLUSTER_URI =
      "redis://127.0.0.1:7100,redis://127.0.0.1:7101,redis://127.0.0.1:7102";

  /** System property that makes an unreachable cluster fail the tests instead of skipping them. */
  public static final String REQUIRE_PROPERTY = "fluxgate.redis.cluster.require";

  /** Environment variable equivalent of {@link #REQUIRE_PROPERTY}. */
  public static final String REQUIRE_ENV = "FLUXGATE_REDIS_CLUSTER_REQUIRE";

  private ClusterTestSupport() {}

  /**
   * Returns the comma-separated node URIs of the test cluster.
   *
   * @return the supplied node list, or {@link #DEFAULT_CLUSTER_URI}
   */
  public static String clusterUri() {
    String uri = System.getProperty("fluxgate.redis.cluster.uri");
    if (uri == null || uri.trim().isEmpty()) {
      uri = System.getenv("FLUXGATE_REDIS_CLUSTER_URI");
    }
    return uri == null || uri.trim().isEmpty() ? DEFAULT_CLUSTER_URI : uri.trim();
  }

  /**
   * Tells whether the cluster must be reachable: {@link #REQUIRE_PROPERTY}, or else {@link
   * #REQUIRE_ENV}, is {@code true}.
   *
   * @return {@code true} when an unreachable cluster must fail the tests
   */
  public static boolean clusterRequired() {
    String value = System.getProperty(REQUIRE_PROPERTY);
    if (value == null || value.trim().isEmpty()) {
      value = System.getenv(REQUIRE_ENV);
    }
    return value != null && Boolean.parseBoolean(value.trim());
  }

  /**
   * Ends the calling test class because no cluster is reachable: a skip, or a failure when {@link
   * #clusterRequired()}.
   *
   * @param reason why the cluster is unavailable
   */
  public static void clusterUnavailable(String reason) {
    clusterUnavailable(reason, clusterRequired());
  }

  static void clusterUnavailable(String reason, boolean required) {
    if (required) {
      throw new AssertionError(reason + " (" + REQUIRE_PROPERTY + "=true: a cluster is required)");
    }
    Assumptions.abort(reason);
  }
}
