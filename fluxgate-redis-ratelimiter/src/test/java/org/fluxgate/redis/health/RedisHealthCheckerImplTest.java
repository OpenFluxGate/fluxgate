package org.fluxgate.redis.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
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
  void shouldIssueExactlyOnePingPerCheck() {
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
    when(connectionProvider.ping()).thenReturn("PONG");

    RedisHealthCheckerImpl checker = new RedisHealthCheckerImpl(connectionProvider);
    HealthCheckResult result = checker.check();

    assertThat(result.isHealthy()).isTrue();
    // The PONG is the liveness proof; isConnected() would send a second PING.
    verify(connectionProvider, times(1)).ping();
    verify(connectionProvider, never()).isConnected();
  }

  @Test
  void shouldIncludeClusterDetailsInClusterMode() {
    ClusterRedisConnection clusterConnection = mock(ClusterRedisConnection.class);
    when(clusterConnection.getMode()).thenReturn(RedisMode.CLUSTER);
    when(clusterConnection.ping()).thenReturn("PONG");
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
    when(clusterConnection.clusterNodes()).thenThrow(new RuntimeException("Cluster error"));

    RedisHealthCheckerImpl checker = new RedisHealthCheckerImpl(clusterConnection);
    HealthCheckResult result = checker.check();

    // A cluster whose topology cannot be read is not known to be serving: DOWN, not UP
    assertThat(result.isHealthy()).isFalse();
    assertThat(result.status()).isEqualTo("DOWN");
    assertThat(result.details()).containsEntry("mode", "CLUSTER");
    assertThat(result.details()).containsKey("cluster_error");
  }

  @Test
  void shouldMeasureLatency() {
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
    when(connectionProvider.ping()).thenReturn("PONG");

    RedisHealthCheckerImpl checker = new RedisHealthCheckerImpl(connectionProvider);
    HealthCheckResult result = checker.check();

    assertThat(result.details()).containsKey("latency_ms");
    Object latency = result.details().get("latency_ms");
    assertThat(latency).isInstanceOf(Long.class);
    assertThat((Long) latency).isGreaterThanOrEqualTo(0);
  }

  @Test
  void healthCheckResultUpShouldCreateHealthyResult() {
    HealthCheckResult result = HealthCheckResult.up("Test message", Map.of("key", "value"));

    assertThat(result.isHealthy()).isTrue();
    assertThat(result.status()).isEqualTo("UP");
    assertThat(result.message()).isEqualTo("Test message");
    assertThat(result.details()).containsEntry("key", "value");
  }

  @Test
  void healthCheckResultDownShouldCreateUnhealthyResult() {
    HealthCheckResult result = HealthCheckResult.down("Error message", Map.of("error", "test"));

    assertThat(result.isHealthy()).isFalse();
    assertThat(result.status()).isEqualTo("DOWN");
    assertThat(result.message()).isEqualTo("Error message");
    assertThat(result.details()).containsEntry("error", "test");
  }

  @Test
  void shouldHandleClusterWithFailingSlots() {
    ClusterRedisConnection clusterConnection = mock(ClusterRedisConnection.class);
    when(clusterConnection.getMode()).thenReturn(RedisMode.CLUSTER);
    when(clusterConnection.ping()).thenReturn("PONG");
    when(clusterConnection.clusterNodes())
        .thenReturn(
            List.of(
                "node1 127.0.0.1:7000 master - 0 0 1 connected 0-5460",
                "node2 127.0.0.1:7001 master,fail - 0 0 2 connected 5461-10922"));
    when(clusterConnection.getClusterInfo())
        .thenReturn(
            "cluster_state:fail\n"
                + "cluster_slots_assigned:16384\n"
                + "cluster_slots_ok:5461\n"
                + "cluster_slots_fail:10923\n"
                + "cluster_known_nodes:2\n"
                + "cluster_size:2");

    RedisHealthCheckerImpl checker = new RedisHealthCheckerImpl(clusterConnection);
    HealthCheckResult result = checker.check();

    assertThat(result.isHealthy()).isFalse();
    assertThat(result.status()).isEqualTo("DOWN");
    assertThat(result.message()).contains("cluster_state=fail");
    assertThat(result.details()).containsEntry("cluster_state", "fail");
    assertThat(result.details()).containsEntry("cluster_slots_fail", 10923);
  }

  @Test
  void shouldReportDownWhenSlotsFailWhileTheStateIsStillOk() {
    ClusterRedisConnection clusterConnection = mock(ClusterRedisConnection.class);
    when(clusterConnection.getMode()).thenReturn(RedisMode.CLUSTER);
    when(clusterConnection.ping()).thenReturn("PONG");
    when(clusterConnection.clusterNodes())
        .thenReturn(List.of("node1 127.0.0.1:7000 master - 0 0 1 connected 0-16383"));
    when(clusterConnection.getClusterInfo())
        .thenReturn("cluster_state:ok\ncluster_slots_ok:16000\ncluster_slots_fail:384");

    HealthCheckResult result = new RedisHealthCheckerImpl(clusterConnection).check();

    assertThat(result.isHealthy()).isFalse();
    assertThat(result.status()).isEqualTo("DOWN");
    assertThat(result.message()).contains("cluster_slots_fail=384");
  }

  @Test
  void shouldReportDownWhenNoClusterNodeIsReported() {
    ClusterRedisConnection clusterConnection = mock(ClusterRedisConnection.class);
    when(clusterConnection.getMode()).thenReturn(RedisMode.CLUSTER);
    when(clusterConnection.ping()).thenReturn("PONG");
    // ClusterRedisConnection.clusterNodes() answers an empty list when CLUSTER NODES fails
    when(clusterConnection.clusterNodes()).thenReturn(List.of());

    HealthCheckResult result = new RedisHealthCheckerImpl(clusterConnection).check();

    assertThat(result.isHealthy()).isFalse();
    assertThat(result.status()).isEqualTo("DOWN");
  }

  @Test
  void shouldTrustTheNodeListOfAClusterProviderWithoutClusterInfo() {
    when(connectionProvider.getMode()).thenReturn(RedisMode.CLUSTER);
    when(connectionProvider.ping()).thenReturn("PONG");
    when(connectionProvider.clusterNodes())
        .thenReturn(List.of("node1 127.0.0.1:7000 master - 0 0 1 connected 0-16383"));

    HealthCheckResult result = new RedisHealthCheckerImpl(connectionProvider).check();

    assertThat(result.isHealthy()).isTrue();
    assertThat(result.details()).containsEntry("cluster_masters", 1);
  }

  @Test
  void shouldHandleEmptyClusterInfo() {
    ClusterRedisConnection clusterConnection = mock(ClusterRedisConnection.class);
    when(clusterConnection.getMode()).thenReturn(RedisMode.CLUSTER);
    when(clusterConnection.ping()).thenReturn("PONG");
    when(clusterConnection.clusterNodes())
        .thenReturn(List.of("node1 127.0.0.1:7000 master - 0 0 1 connected 0-16383"));
    // ClusterRedisConnection.getClusterInfo() answers "" when CLUSTER INFO fails
    when(clusterConnection.getClusterInfo()).thenReturn("");

    RedisHealthCheckerImpl checker = new RedisHealthCheckerImpl(clusterConnection);
    HealthCheckResult result = checker.check();

    assertThat(result.isHealthy()).isFalse();
    assertThat(result.status()).isEqualTo("DOWN");
    assertThat(result.details()).containsEntry("mode", "CLUSTER");
  }

  @Test
  void shouldHandleNullClusterInfo() {
    ClusterRedisConnection clusterConnection = mock(ClusterRedisConnection.class);
    when(clusterConnection.getMode()).thenReturn(RedisMode.CLUSTER);
    when(clusterConnection.ping()).thenReturn("PONG");
    when(clusterConnection.clusterNodes())
        .thenReturn(List.of("node1 127.0.0.1:7000 master - 0 0 1 connected 0-16383"));
    when(clusterConnection.getClusterInfo()).thenReturn(null);

    RedisHealthCheckerImpl checker = new RedisHealthCheckerImpl(clusterConnection);
    HealthCheckResult result = checker.check();

    assertThat(result.isHealthy()).isFalse();
  }

  @Test
  void shouldHandleInvalidNumberInClusterInfo() {
    ClusterRedisConnection clusterConnection = mock(ClusterRedisConnection.class);
    when(clusterConnection.getMode()).thenReturn(RedisMode.CLUSTER);
    when(clusterConnection.ping()).thenReturn("PONG");
    when(clusterConnection.clusterNodes())
        .thenReturn(List.of("node1 127.0.0.1:7000 master - 0 0 1 connected 0-16383"));
    when(clusterConnection.getClusterInfo())
        .thenReturn("cluster_slots_ok:not_a_number\ncluster_state:ok");

    RedisHealthCheckerImpl checker = new RedisHealthCheckerImpl(clusterConnection);
    HealthCheckResult result = checker.check();

    assertThat(result.isHealthy()).isTrue();
    assertThat(result.details()).containsEntry("cluster_slots_ok", -1);
    assertThat(result.details()).containsEntry("cluster_state", "ok");
  }

  @Test
  void shouldCountRolesFromTheFlagsColumnOnly() {
    ClusterRedisConnection clusterConnection = mock(ClusterRedisConnection.class);
    when(clusterConnection.getMode()).thenReturn(RedisMode.CLUSTER);
    when(clusterConnection.ping()).thenReturn("PONG");
    // The replica's node id spells "master" and its host is "master-host"; only the third
    // column (flags) decides the role.
    when(clusterConnection.clusterNodes())
        .thenReturn(
            List.of(
                "aaa 127.0.0.1:7000@17000 myself,master - 0 0 1 connected 0-16383",
                "master1 master-host:7001@17001 slave aaa 0 0 2 connected"));
    when(clusterConnection.getClusterInfo()).thenReturn("cluster_state:ok");

    RedisHealthCheckerImpl checker = new RedisHealthCheckerImpl(clusterConnection);
    HealthCheckResult result = checker.check();

    assertThat(result.details()).containsEntry("cluster_masters", 1);
    assertThat(result.details()).containsEntry("cluster_replicas", 1);
  }
}
