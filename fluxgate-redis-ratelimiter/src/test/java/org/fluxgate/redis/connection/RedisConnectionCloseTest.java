package org.fluxgate.redis.connection;

import static org.mockito.Mockito.*;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Regression tests for H-13: shutting the client down must not be skipped because closing the
 * connection threw, or the Netty event loop group leaks for the rest of the JVM's life.
 */
@ExtendWith(MockitoExtension.class)
class RedisConnectionCloseTest {

  @Mock private RedisClient redisClient;
  @Mock private StatefulRedisConnection<String, String> statefulConnection;
  @Mock private RedisCommands<String, String> commands;

  @Mock private RedisClusterClient clusterClient;
  @Mock private StatefulRedisClusterConnection<String, String> statefulClusterConnection;
  @Mock private RedisAdvancedClusterCommands<String, String> clusterCommands;

  @Test
  @DisplayName("Standalone: the client is shut down even when the connection fails to close")
  void standaloneShouldShutDownTheClientWhenTheConnectionThrows() {
    doThrow(new RuntimeException("connection already gone")).when(statefulConnection).close();

    StandaloneRedisConnection connection =
        new StandaloneRedisConnection(redisClient, statefulConnection, commands);

    connection.close();

    verify(statefulConnection).close();
    verify(redisClient).shutdown();
  }

  @Test
  @DisplayName("Standalone: a failing client shutdown is swallowed, not propagated")
  void standaloneShouldNotPropagateAFailingShutdown() {
    doThrow(new RuntimeException("already shut down")).when(redisClient).shutdown();

    StandaloneRedisConnection connection =
        new StandaloneRedisConnection(redisClient, statefulConnection, commands);

    connection.close();

    verify(redisClient).shutdown();
  }

  @Test
  @DisplayName("Cluster: the client is shut down even when the connection fails to close")
  void clusterShouldShutDownTheClientWhenTheConnectionThrows() {
    doThrow(new RuntimeException("connection already gone"))
        .when(statefulClusterConnection)
        .close();

    ClusterRedisConnection connection =
        new ClusterRedisConnection(clusterClient, statefulClusterConnection, clusterCommands);

    connection.close();

    verify(statefulClusterConnection).close();
    verify(clusterClient).shutdown();
  }
}
