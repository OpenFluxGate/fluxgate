package org.fluxgate.control.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;

/**
 * Receiver counting of {@link RedisRuleChangeNotifier} in standalone and cluster mode.
 *
 * <p>In Redis Cluster a {@code PUBLISH} reports only the subscribers connected to the node that
 * served it, so the count says nothing about the cluster as a whole.
 */
class RedisRuleChangeNotifierReceiversTest {

  private static final String CHANNEL = "fluxgate:rule-reload";

  private final Logger logger = (Logger) LoggerFactory.getLogger(RedisRuleChangeNotifier.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void attachAppender() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
  }

  private long warnings() {
    return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
  }

  @Test
  @DisplayName("standalone: the PUBLISH count is returned and zero receivers warns once")
  @SuppressWarnings("unchecked")
  void standalone_reportsCountAndWarnsOnZero() {
    RedisClient client = mock(RedisClient.class);
    StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
    RedisCommands<String, String> commands = mock(RedisCommands.class);
    when(client.connect()).thenReturn(connection);
    when(connection.isOpen()).thenReturn(true);
    when(connection.sync()).thenReturn(commands);
    when(commands.publish(anyString(), anyString())).thenReturn(0L, 0L, 2L);

    try (MockedStatic<RedisClient> statik = mockStatic(RedisClient.class)) {
      statik.when(() -> RedisClient.create(any(RedisURI.class))).thenReturn(client);
      RedisRuleChangeNotifier notifier =
          new RedisRuleChangeNotifier("redis://localhost:6379", CHANNEL);

      assertThat(notifier.publishFullReload()).isZero();
      assertThat(notifier.publishFullReload()).isZero();
      assertThat(warnings()).isEqualTo(1);
      assertThat(notifier.publishChange("orders")).isEqualTo(2L);
      notifier.close();
    }
  }

  @Test
  @DisplayName("cluster: the node-local PUBLISH count is reported as UNKNOWN_RECEIVERS, no WARN")
  @SuppressWarnings("unchecked")
  void cluster_reportsUnknownAndDoesNotWarn() {
    RedisClusterClient client = mock(RedisClusterClient.class);
    StatefulRedisClusterConnection<String, String> connection =
        mock(StatefulRedisClusterConnection.class);
    RedisAdvancedClusterCommands<String, String> commands =
        mock(RedisAdvancedClusterCommands.class);
    when(client.connect()).thenReturn(connection);
    when(connection.isOpen()).thenReturn(true);
    when(connection.sync()).thenReturn(commands);
    // The node that served PUBLISH has no local subscriber; other nodes may well have some.
    when(commands.publish(anyString(), anyString())).thenReturn(0L, 3L);

    try (MockedStatic<RedisClusterClient> statik = mockStatic(RedisClusterClient.class)) {
      statik.when(() -> RedisClusterClient.create(any(Iterable.class))).thenReturn(client);
      RedisRuleChangeNotifier notifier =
          new RedisRuleChangeNotifier("redis://node1:7000,redis://node2:7001", CHANNEL);

      assertThat(notifier.publishChange("orders")).isEqualTo(RuleChangeNotifier.UNKNOWN_RECEIVERS);
      assertThat(notifier.publishFullReload()).isEqualTo(RuleChangeNotifier.UNKNOWN_RECEIVERS);
      assertThat(warnings()).isZero();
      notifier.close();
    }
  }
}
