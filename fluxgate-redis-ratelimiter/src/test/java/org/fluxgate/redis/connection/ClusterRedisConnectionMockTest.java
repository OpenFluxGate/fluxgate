package org.fluxgate.redis.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions.RefreshTrigger;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link ClusterRedisConnection}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClusterRedisConnectionMockTest {

  @Mock private RedisAdvancedClusterCommands<String, String> commands;

  private ClusterRedisConnection connection;

  @BeforeEach
  void setUp() {
    connection = new ClusterRedisConnection(commands);
  }

  @Test
  @DisplayName(
      "Every node URI carries the configured timeout (Lettuce cluster ignores the default)")
  void everyNodeUriCarriesTheConfiguredTimeout() {
    List<RedisURI> uris =
        ClusterRedisConnection.toRedisUris(
            List.of("redis://127.0.0.1:7100", "redis://127.0.0.1:7101"), Duration.ofMillis(750));

    assertThat(uris).hasSize(2);
    assertThat(uris)
        .allSatisfy(uri -> assertThat(uri.getTimeout()).isEqualTo(Duration.ofMillis(750)));
  }

  @Test
  @DisplayName("Client options enforce the configured command timeout")
  void clientOptionsEnforceTheCommandTimeout() {
    ClusterClientOptions options =
        ClusterRedisConnection.clientOptions(Duration.ofMillis(750), Duration.ofSeconds(30));

    TimeoutOptions timeoutOptions = options.getTimeoutOptions();
    assertThat(timeoutOptions.isTimeoutCommands()).isTrue();
    assertThat(
            timeoutOptions
                .getSource()
                .getTimeUnit()
                .toMillis(timeoutOptions.getSource().getTimeout(null)))
        .isEqualTo(750L);
    assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofMillis(750));
  }

  @Test
  @DisplayName("Client options refresh the topology periodically and on every adaptive trigger")
  void clientOptionsRefreshTheTopology() {
    ClusterClientOptions options =
        ClusterRedisConnection.clientOptions(Duration.ofSeconds(5), Duration.ofSeconds(30));

    ClusterTopologyRefreshOptions refresh = options.getTopologyRefreshOptions();
    assertThat(refresh.isPeriodicRefreshEnabled()).isTrue();
    assertThat(refresh.getRefreshPeriod()).isEqualTo(Duration.ofSeconds(30));
    assertThat(refresh.getAdaptiveRefreshTriggers())
        .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(RefreshTrigger.class));
  }

  @Test
  @DisplayName(
      "The default topology refresh period supports failover discovery within five seconds")
  void defaultTopologyRefreshPeriod() {
    assertThat(ClusterRedisConnection.DEFAULT_TOPOLOGY_REFRESH_PERIOD)
        .isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void shouldThrowWhenCommandsIsNull() {
    assertThatThrownBy(
            () -> new ClusterRedisConnection((RedisAdvancedClusterCommands<String, String>) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("commands must not be null");
  }

  @Test
  void shouldReturnClusterMode() {
    assertThat(connection.getMode()).isEqualTo(RedisMode.CLUSTER);
  }

  @Test
  void shouldLoadScript() {
    when(commands.scriptLoad("test-script")).thenReturn("sha256-hash");

    String sha = connection.scriptLoad("test-script");

    assertThat(sha).isEqualTo("sha256-hash");
    verify(commands).scriptLoad("test-script");
  }

  @Test
  void shouldThrowWhenScriptIsNull() {
    assertThatThrownBy(() -> connection.scriptLoad(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("script must not be null");
  }

  @Test
  void shouldExecuteEvalsha() {
    List<Long> result = Arrays.asList(1L, 99L, 0L, System.currentTimeMillis());
    when(commands.evalsha(
            eq("test-sha"), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
        .thenReturn(result);

    List<Long> actual =
        connection.evalsha("test-sha", new String[] {"key1"}, new String[] {"arg1"});

    assertThat(actual).isEqualTo(result);
  }

  @Test
  void shouldThrowWhenEvalshaParamsNull() {
    assertThatThrownBy(() -> connection.evalsha(null, new String[] {}, new String[] {}))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("sha must not be null");

    assertThatThrownBy(() -> connection.evalsha("sha", null, new String[] {}))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("keys must not be null");

    assertThatThrownBy(() -> connection.evalsha("sha", new String[] {}, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("args must not be null");
  }

  @Test
  void shouldSetHashField() {
    when(commands.hset("key", "field", "value")).thenReturn(true);

    boolean result = connection.hset("key", "field", "value");

    assertThat(result).isTrue();
    verify(commands).hset("key", "field", "value");
  }

  @Test
  void shouldSetHashMap() {
    Map<String, String> map = new HashMap<>();
    map.put("field1", "value1");
    map.put("field2", "value2");
    when(commands.hset("key", map)).thenReturn(2L);

    long result = connection.hset("key", map);

    assertThat(result).isEqualTo(2L);
    verify(commands).hset("key", map);
  }

  @Test
  void shouldGetAllHashFields() {
    Map<String, String> expected = new HashMap<>();
    expected.put("field1", "value1");
    expected.put("field2", "value2");
    when(commands.hgetall("key")).thenReturn(expected);

    Map<String, String> result = connection.hgetall("key");

    assertThat(result).isEqualTo(expected);
  }

  @Test
  void shouldDeleteKeys() {
    when(commands.del("key1", "key2")).thenReturn(2L);

    long result = connection.del("key1", "key2");

    assertThat(result).isEqualTo(2L);
  }

  @Test
  void shouldAddToSet() {
    when(commands.sadd("set-key", "member1", "member2")).thenReturn(2L);

    long result = connection.sadd("set-key", "member1", "member2");

    assertThat(result).isEqualTo(2L);
  }

  @Test
  void shouldGetSetMembers() {
    Set<String> expected = new HashSet<>(Arrays.asList("member1", "member2"));
    when(commands.smembers("set-key")).thenReturn(expected);

    Set<String> result = connection.smembers("set-key");

    assertThat(result).isEqualTo(expected);
  }

  @Test
  void shouldRemoveFromSet() {
    when(commands.srem("set-key", "member1")).thenReturn(1L);

    long result = connection.srem("set-key", "member1");

    assertThat(result).isEqualTo(1L);
  }

  @Test
  void shouldCheckKeyExists() {
    when(commands.exists("existing-key")).thenReturn(1L);
    when(commands.exists("missing-key")).thenReturn(0L);

    assertThat(connection.exists("existing-key")).isTrue();
    assertThat(connection.exists("missing-key")).isFalse();
  }

  @Test
  void shouldGetTtl() {
    when(commands.ttl("key")).thenReturn(60L);

    long ttl = connection.ttl("key");

    assertThat(ttl).isEqualTo(60L);
  }

  @Test
  void shouldGetKeys() {
    when(commands.keys("fluxgate:*")).thenReturn(Arrays.asList("fluxgate:key1", "fluxgate:key2"));

    List<String> keys = connection.keys("fluxgate:*");

    assertThat(keys).containsExactly("fluxgate:key1", "fluxgate:key2");
  }

  @Test
  void shouldFlushDb() {
    when(commands.flushdb()).thenReturn("OK");

    String result = connection.flushdb();

    assertThat(result).isEqualTo("OK");
  }

  @Test
  void shouldPing() {
    when(commands.ping()).thenReturn("PONG");

    String result = connection.ping();

    assertThat(result).isEqualTo("PONG");
  }

  @Test
  void shouldGetClusterNodes() {
    String clusterNodesInfo = "node1 127.0.0.1:6379\nnode2 127.0.0.1:6380\nnode3 127.0.0.1:6381";
    when(commands.clusterNodes()).thenReturn(clusterNodesInfo);

    List<String> nodes = connection.clusterNodes();

    assertThat(nodes).hasSize(3);
    assertThat(nodes.get(0)).contains("node1");
    assertThat(nodes.get(1)).contains("node2");
    assertThat(nodes.get(2)).contains("node3");
  }

  @Test
  void shouldReturnEmptyListWhenClusterNodesFails() {
    when(commands.clusterNodes()).thenThrow(new RuntimeException("Connection failed"));

    List<String> nodes = connection.clusterNodes();

    assertThat(nodes).isEmpty();
  }

  @Test
  void shouldCloseWithoutError() {
    // Should not throw
    connection.close();
  }

  @Test
  void shouldReturnUnderlyingCommands() {
    assertThat(connection.getCommands()).isEqualTo(commands);
  }

  @Test
  @DisplayName("isConnected works when the commands come from outside (H-12)")
  void isConnectedShouldPingOnlyForExternallySuppliedCommands() {
    when(commands.ping()).thenReturn("PONG");

    assertThat(connection.isConnected()).isTrue();
    verify(commands).ping();
  }

  @Test
  void isConnectedShouldBeFalseWhenPingThrows() {
    when(commands.ping()).thenThrow(new RuntimeException("connection reset"));

    assertThat(connection.isConnected()).isFalse();
  }

  @Test
  void shouldUnlinkKeys() {
    when(commands.unlink("a", "b")).thenReturn(2L);

    assertThat(connection.unlink("a", "b")).isEqualTo(2L);
    verify(commands).unlink("a", "b");
  }

  @Test
  @DisplayName("scanKeys hands every SCAN page to the consumer as it arrives")
  void scanKeysStreamsPageByPage() {
    when(commands.scan(any(ScanCursor.class), any(ScanArgs.class)))
        .thenReturn(page("1", false, "k1", "k2"))
        .thenReturn(page("0", true, "k3"));

    List<List<String>> pages = new ArrayList<>();
    connection.scanKeys("fluxgate:bucket:*", 100L, pages::add);

    assertThat(pages).containsExactly(List.of("k1", "k2"), List.of("k3"));
  }

  @Test
  @DisplayName("scanKeys does not call the consumer for empty SCAN pages")
  void scanKeysSkipsEmptyPages() {
    when(commands.scan(any(ScanCursor.class), any(ScanArgs.class)))
        .thenReturn(page("7", false))
        .thenReturn(page("0", true, "k1"));

    List<List<String>> pages = new ArrayList<>();
    connection.scanKeys("fluxgate:bucket:*", 100L, pages::add);

    assertThat(pages).containsExactly(List.of("k1"));
  }

  private static KeyScanCursor<String> page(String cursor, boolean finished, String... keys) {
    KeyScanCursor<String> page = new KeyScanCursor<>();
    page.getKeys().addAll(Arrays.asList(keys));
    page.setCursor(cursor);
    page.setFinished(finished);
    return page;
  }
}
