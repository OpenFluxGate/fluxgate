package org.fluxgate.redis.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.fluxgate.core.exception.RedisConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * N1 item 3: TCP connect timeout is enforced via SocketOptions so that connection attempts to
 * unreachable hosts fail quickly. Uses 192.0.2.1 (TEST-NET-1, RFC 5737): packets are silently
 * dropped, giving a clean timeout signal without a RST.
 */
@DisplayName("N1 Connect timeout")
class ConnectTimeoutTest {

  private static final Duration TIMEOUT = Duration.ofMillis(300);
  private static final long MAX_WALL_MILLIS = TIMEOUT.toMillis() * 3;

  @Test
  @DisplayName("StandaloneRedisConnection fails within the configured timeout")
  void standaloneConnectTimeout() {
    Instant start = Instant.now();
    assertThatThrownBy(() -> new StandaloneRedisConnection("redis://192.0.2.1:6379", TIMEOUT))
        .isInstanceOf(RedisConnectionException.class);
    long elapsed = Duration.between(start, Instant.now()).toMillis();
    assertThat(elapsed)
        .as("connect failed after %d ms (limit %d ms)", elapsed, MAX_WALL_MILLIS)
        .isLessThan(MAX_WALL_MILLIS);
  }

  @Test
  @DisplayName("ClusterRedisConnection fails within the configured timeout")
  void clusterConnectTimeout() {
    Instant start = Instant.now();
    assertThatThrownBy(() -> new ClusterRedisConnection(List.of("redis://192.0.2.1:6379"), TIMEOUT))
        .isInstanceOf(RedisConnectionException.class);
    long elapsed = Duration.between(start, Instant.now()).toMillis();
    assertThat(elapsed)
        .as("connect failed after %d ms (limit %d ms)", elapsed, MAX_WALL_MILLIS)
        .isLessThan(MAX_WALL_MILLIS);
  }
}
