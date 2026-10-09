package org.fluxgate.redis.connection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;
import org.fluxgate.redis.store.BucketState;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration test for Redis Cluster connection.
 *
 * <p>Requires Redis Cluster running on localhost:7100-7105
 *
 * <p>Start cluster locally: docker compose -f docker/redis-cluster.yml up -d
 *
 * <p>Run with: mvn -pl fluxgate-redis-ratelimiter -Predis-cluster-it verify
 *
 * <p>A cluster cannot be started through Testcontainers as easily as a standalone node, so this
 * class stays behind the {@code redis-cluster-it} profile. Enabling the profile without a running
 * cluster skips the tests rather than failing them.
 */
@EnabledIfSystemProperty(named = "fluxgate.redis.cluster.tests", matches = "true")
class ClusterConnectionIntegrationTest {

  // Comma-separated nodes -> automatically detected as Cluster mode
  // When using grokzen/redis-cluster image, ports 7100-7105
  private static final String CLUSTER_URI =
      "redis://127.0.0.1:7100,redis://127.0.0.1:7101,redis://127.0.0.1:7102";

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
      Assumptions.abort(
          "Skipping Redis Cluster integration test: no cluster reachable at "
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
}
