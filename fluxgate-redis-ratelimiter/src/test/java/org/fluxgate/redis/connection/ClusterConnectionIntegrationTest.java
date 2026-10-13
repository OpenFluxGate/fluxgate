package org.fluxgate.redis.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;
import org.fluxgate.redis.health.RedisHealthCheckerImpl;
import org.fluxgate.redis.store.BucketState;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.fluxgate.redis.support.ClusterTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration test for Redis Cluster connection.
 *
 * <p>Requires Redis Cluster running on localhost:7100-7105, or the nodes named by {@code
 * -Dfluxgate.redis.cluster.uri} (see {@link ClusterTestSupport})
 *
 * <p>Start cluster locally: docker compose -f docker/redis-cluster.yml up -d
 *
 * <p>Run with: mvn -pl fluxgate-redis-ratelimiter -Predis-cluster-it verify
 *
 * <p>A cluster cannot be started through Testcontainers as easily as a standalone node, so this
 * class stays behind the {@code redis-cluster-it} profile. Enabling the profile without a running
 * cluster skips the tests rather than failing them, unless {@code
 * -Dfluxgate.redis.cluster.require=true} is set (see {@link ClusterTestSupport}).
 */
@EnabledIfSystemProperty(named = "fluxgate.redis.cluster.tests", matches = "true")
class ClusterConnectionIntegrationTest {

  // Comma-separated nodes -> automatically detected as Cluster mode
  private static final String CLUSTER_URI = ClusterTestSupport.clusterUri();

  private static RedisRateLimiterConfig config;
  private static RedisConnectionProvider connectionProvider;
  private static RedisTokenBucketStore tokenBucketStore;

  @BeforeAll
  static void setUp() {
    System.out.println("\n=== Connecting to Redis Cluster ===");
    System.out.println("URI: " + CLUSTER_URI);

    boolean connected = false;
    String failure = null;
    try {
      config = new RedisRateLimiterConfig(CLUSTER_URI);
      connectionProvider = config.getConnectionProvider();
      tokenBucketStore = config.getTokenBucketStore();
      connected = connectionProvider.isConnected();
    } catch (RuntimeException e) {
      failure = e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    if (!connected) {
      closeQuietly();
      ClusterTestSupport.clusterUnavailable(
          "No Redis Cluster reachable at "
              + CLUSTER_URI
              + (failure == null ? "" : " (" + failure + ")"));
    }

    System.out.println("Mode: " + connectionProvider.getMode());
    System.out.println("Connected: " + connectionProvider.isConnected());
    System.out.println();
  }

  @AfterAll
  static void tearDown() {
    closeQuietly();
    System.out.println("=== Connection closed ===\n");
  }

  private static void closeQuietly() {
    if (config != null) {
      config.close();
      config = null;
    }
  }

  @Test
  @DisplayName("Should detect CLUSTER mode from comma-separated URIs")
  void shouldDetectClusterMode() {
    assertThat(connectionProvider.getMode()).isEqualTo(RedisMode.CLUSTER);
  }

  @Test
  @DisplayName("Should be connected")
  void shouldBeConnected() {
    assertThat(connectionProvider.isConnected()).isTrue();
  }

  @Test
  @DisplayName("Should respond to PING")
  void shouldRespondToPing() {
    assertThat(connectionProvider.ping()).isEqualTo("PONG");
  }

  @Test
  @DisplayName("Should have cluster nodes")
  void shouldHaveClusterNodes() {
    var nodes = connectionProvider.clusterNodes();

    assertThat(nodes).isNotEmpty();
    System.out.println("Cluster nodes (" + nodes.size() + "):");
    for (String node : nodes) {
      System.out.println("  " + node);
    }
  }

  @Test
  @DisplayName("Should execute rate limiting in cluster mode")
  void shouldExecuteRateLimitingInCluster() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 10).label("test").build();
    String bucketKey = "cluster-test:bucket:" + System.currentTimeMillis();

    // when
    BucketState state = tokenBucketStore.tryConsume(bucketKey, band, 3);

    // then
    assertThat(state.consumed()).isTrue();
    assertThat(state.remainingTokens()).isEqualTo(7); // 10 - 3 = 7

    System.out.println("Rate limiting in cluster mode:");
    System.out.println("  Bucket: " + bucketKey);
    System.out.println("  Consumed: " + state.consumed());
    System.out.println("  Remaining: " + state.remainingTokens());
  }

  @Test
  @DisplayName("Should exhaust tokens and reject in cluster mode")
  void shouldExhaustTokensAndRejectInCluster() {
    // given
    RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(60), 5).label("test").build();
    String bucketKey = "cluster-test:exhaust:" + System.currentTimeMillis();

    // when: consume all tokens
    BucketState first = tokenBucketStore.tryConsume(bucketKey, band, 5);
    BucketState second = tokenBucketStore.tryConsume(bucketKey, band, 1);

    // then
    assertThat(first.consumed()).isTrue();
    assertThat(first.remainingTokens()).isZero();

    assertThat(second.consumed()).isFalse();
    assertThat(second.nanosToWaitForRefill()).isGreaterThan(0);

    System.out.println("Token exhaustion test:");
    System.out.println("  First request: consumed=" + first.consumed());
    System.out.println(
        "  Second request: consumed="
            + second.consumed()
            + ", wait="
            + second.nanosToWaitForRefill()
            + "ns");
  }

  @Test
  @DisplayName("A cluster command times out at the configured timeout, not Lettuce's 60 s default")
  void clusterCommandTimesOutAtTheConfiguredTimeout() throws InterruptedException {
    Duration timeout = Duration.ofMillis(500);
    long pauseMillis = 3_000L;
    List<String> masters = masterAddresses();
    assertThat(masters).isNotEmpty();

    RedisClient adminClient = RedisClient.create();
    List<StatefulRedisConnection<String, String>> admins = new ArrayList<>();
    try (ClusterRedisConnection connection =
        new ClusterRedisConnection(RedisUriUtils.splitNodes(CLUSTER_URI), timeout)) {
      for (String master : masters) {
        admins.add(adminClient.connect(RedisURI.create("redis://" + master)));
      }
      String key = "fluxgate:test:timeout:" + System.nanoTime();
      connection.hset(key, "f", "v");

      long pausedAt = System.currentTimeMillis();
      for (StatefulRedisConnection<String, String> admin : admins) {
        admin.sync().clientPause(pauseMillis);
      }
      long start = System.nanoTime();
      Throwable thrown = catchThrowable(() -> connection.hgetall(key));
      long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

      // Let the pause run out before the connection is used (or closed) again.
      long remaining = pauseMillis - (System.currentTimeMillis() - pausedAt) + 200L;
      if (remaining > 0) {
        Thread.sleep(remaining);
      }
      connection.del(key);

      assertThat(thrown)
          .as("a paused cluster must time the command out (took %d ms)", elapsedMillis)
          .isInstanceOf(RedisCommandTimeoutException.class);
      assertThat(elapsedMillis).isLessThan(pauseMillis - 1_000L);
    } finally {
      admins.forEach(StatefulRedisConnection::close);
      adminClient.shutdown();
    }
  }

  @Test
  @DisplayName("A healthy cluster is reported UP with cluster_state ok and no failing slots")
  void healthyClusterIsUp() {
    RedisHealthCheckerImpl.HealthCheckResult result =
        new RedisHealthCheckerImpl(connectionProvider).check();

    assertThat(result.isHealthy()).as(result.message()).isTrue();
    assertThat(result.details()).containsEntry("cluster_state", "ok");
    assertThat(result.details()).containsEntry("cluster_slots_fail", 0);
  }

  /** {@code host:port} of every master, as the cluster announces it. */
  private static List<String> masterAddresses() {
    List<String> masters = new ArrayList<>();
    for (String line : connectionProvider.clusterNodes()) {
      String[] columns = line.split("\\s+");
      if (columns.length > 2 && columns[2].contains("master")) {
        masters.add(columns[1].split("@")[0]);
      }
    }
    return masters;
  }
}
