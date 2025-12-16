package org.fluxgate.redis.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;
import org.fluxgate.redis.connection.ClusterRedisConnection;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;
import org.fluxgate.redis.health.RedisHealthCheckerImpl.HealthCheckResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for {@link RedisHealthCheckerImpl}. */
@ExtendWith(MockitoExtension.class)
class RedisHealthCheckerImplTest {

  @Mock private RedisConnectionProvider connectionProvider;

  @Test
  void shouldReturnUpWhenStandaloneConnectionIsHealthy() {
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
    when(connectionProvider.ping()).thenReturn("PONG");
    when(connectionProvider.isConnected()).thenReturn(true);

    RedisHealthCheckerImpl checker = new RedisHealthCheckerImpl(connectionProvider);
    HealthCheckResult result = checker.check();

    assertThat(result.isHealthy()).isTrue();
    assertThat(result.status()).isEqualTo("UP");
    assertThat(result.message()).contains("standalone");
    assertThat(result.details()).containsEntry("mode", "STANDALONE");
    assertThat(result.details()).containsKey("latency_ms");
  }

  @Test
  void shouldReturnDownWhenPingFails() {
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
    when(connectionProvider.ping()).thenThrow(new RuntimeException("Connection refused"));

    RedisHealthCheckerImpl checker = new RedisHealthCheckerImpl(connectionProvider);
    HealthCheckResult result = checker.check();

    assertThat(result.isHealthy()).isFalse();
    assertThat(result.status()).isEqualTo("DOWN");
    assertThat(result.message()).contains("failed");
    assertThat(result.details()).containsKey("error");
  }

  @Test
  void shouldReturnDownWhenUnexpectedPingResponse() {
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
    when(connectionProvider.ping()).thenReturn("ERROR");

    RedisHealthCheckerImpl checker = new RedisHealthCheckerImpl(connectionProvider);
    HealthCheckResult result = checker.check();

    assertThat(result.isHealthy()).isFalse();
    assertThat(result.status()).isEqualTo("DOWN");
    assertThat(result.message()).contains("Unexpected PING response");
  }

  @Test
  void shouldReturnDownWhenConnectionIsNotActive() {
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
    when(connectionProvider.ping()).thenReturn("PONG");
    when(connectionProvider.isConnected()).thenReturn(false);

    RedisHealthCheckerImpl checker = new RedisHealthCheckerImpl(connectionProvider);
    HealthCheckResult result = checker.check();

    assertThat(result.isHealthy()).isFalse();
    assertThat(result.status()).isEqualTo("DOWN");
    assertThat(result.message()).contains("not active");
  }

  @Test
  void shouldIncludeClusterDetailsInClusterMode() {
    ClusterRedisConnection clusterConnection = mock(ClusterRedisConnection.class);
    when(clusterConnection.getMode()).thenReturn(RedisMode.CLUSTER);
    when(clusterConnection.ping()).thenReturn("PONG");
    when(clusterConnection.isConnected()).thenReturn(true);
    when(clusterConnection.clusterNodes())
        .thenReturn(
            List.of(
                "node1 127.0.0.1:7000 master - 0 0 1 connected 0-5460",
                "node2 127.0.0.1:7001 master - 0 0 2 connected 5461-10922",
                "node3 127.0.0.1:7002 master - 0 0 3 connected 10923-16383",
                "node4 127.0.0.1:7003 slave node1 0 0 4 connected",
                "node5 127.0.0.1:7004 slave node2 0 0 5 connected",
                "node6 127.0.0.1:7005 slave node3 0 0 6 connected"));
    when(clusterConnection.getClusterInfo())
        .thenReturn(
            "cluster_state:ok\n"
                + "cluster_slots_assigned:16384\n"
                + "cluster_slots_ok:16384\n"
                + "cluster_slots_fail:0\n"
                + "cluster_known_nodes:6\n"
                + "cluster_size:3");

    RedisHealthCheckerImpl checker = new RedisHealthCheckerImpl(clusterConnection);
    HealthCheckResult result = checker.check();

    assertThat(result.isHealthy()).isTrue();
    assertThat(result.status()).isEqualTo("UP");
    assertThat(result.message()).contains("cluster");
    assertThat(result.details()).containsEntry("mode", "CLUSTER");
    assertThat(result.details()).containsEntry("cluster_nodes", 6);
    assertThat(result.details()).containsEntry("cluster_masters", 3);
    assertThat(result.details()).containsEntry("cluster_replicas", 3);
    assertThat(result.details()).containsEntry("cluster_state", "ok");
    assertThat(result.details()).containsEntry("cluster_slots_ok", 16384);
    assertThat(result.details()).containsEntry("cluster_slots_fail", 0);
  }

  @Test
  void shouldHandleClusterInfoError() {
    ClusterRedisConnection clusterConnection = mock(ClusterRedisConnection.class);
    when(clusterConnection.getMode()).thenReturn(RedisMode.CLUSTER);
    when(clusterConnection.ping()).thenReturn("PONG");
    when(clusterConnection.isConnected()).thenReturn(true);
    when(clusterConnection.clusterNodes()).thenThrow(new RuntimeException("Cluster error"));

    RedisHealthCheckerImpl checker = new RedisHealthCheckerImpl(clusterConnection);
    HealthCheckResult result = checker.check();

    // Should still be healthy, just with limited cluster info
    assertThat(result.isHealthy()).isTrue();
    assertThat(result.details()).containsEntry("mode", "CLUSTER");
    assertThat(result.details()).containsKey("cluster_error");
  }

  @Test
  void shouldMeasureLatency() {
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
    when(connectionProvider.ping()).thenReturn("PONG");
    when(connectionProvider.isConnected()).thenReturn(true);

    RedisHealthCheckerImpl checker = new RedisHealthCheckerImpl(connectionProvider);
    HealthCheckResult result = checker.check();

    assertThat(result.details()).containsKey("latency_ms");
    Object latency = result.details().get("latency_ms");
    assertThat(latency).isInstanceOf(Long.class);
    assertThat((Long) latency).isGreaterThanOrEqualTo(0);
  }
}
