package org.fluxgate.redis.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions.RefreshTrigger;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ClusterTopologyRecoveryTest {
  @Test
  @SuppressWarnings("unchecked")
  void discoversPromotedReplicasEvenWhenThePreviouslyCachedMasterCannotReply() {
    var client = mock(RedisClusterClient.class);
    var channel = mock(StatefulRedisClusterConnection.class);
    var commands = mock(RedisAdvancedClusterCommands.class);
    when(client.connect()).thenReturn(channel);
    when(channel.sync()).thenReturn(commands);
    when(commands.ping()).thenReturn("PONG");
    when(commands.clusterNodes()).thenReturn("node 127.0.0.1:6379 master connected");

    try (var factory = mockStatic(RedisClusterClient.class)) {
      factory
          .when(() -> RedisClusterClient.create(anyIterable()))
          .thenAnswer(
              invocation -> {
                Iterable<RedisURI> seeds = invocation.getArgument(0);
                assertThat(seeds)
                    .allSatisfy(
                        uri -> assertThat(uri.getTimeout()).isEqualTo(Duration.ofSeconds(2)));
                return client;
              });
      try (var connection =
          new ClusterRedisConnection(
              List.of("redis://127.0.0.1:6379", "redis://127.0.0.1:6380"), Duration.ofSeconds(2))) {
        var options = ArgumentCaptor.forClass(ClusterClientOptions.class);
        verify(client).setOptions(options.capture());
        var refresh = options.getValue().getTopologyRefreshOptions();
        assertThat(refresh.isPeriodicRefreshEnabled()).isTrue();
        assertThat(refresh.getRefreshPeriod()).isLessThanOrEqualTo(Duration.ofSeconds(5));
        assertThat(refresh.getAdaptiveRefreshTriggers())
            .contains(
                RefreshTrigger.PERSISTENT_RECONNECTS,
                RefreshTrigger.MOVED_REDIRECT,
                RefreshTrigger.UNKNOWN_NODE,
                RefreshTrigger.UNCOVERED_SLOT);
        assertThat(refresh.getAdaptiveRefreshTimeout()).isLessThanOrEqualTo(Duration.ofSeconds(2));
        assertThat(refresh.isCloseStaleConnections()).isTrue();
        assertThat(options.getValue().getSocketOptions().getConnectTimeout())
            .isEqualTo(Duration.ofSeconds(2));
      }
    }
  }
}
