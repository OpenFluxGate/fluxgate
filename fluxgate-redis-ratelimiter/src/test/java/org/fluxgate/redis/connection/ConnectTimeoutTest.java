package org.fluxgate.redis.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.fluxgate.core.exception.RedisConnectionException;
import org.fluxgate.core.resilience.DefaultRetryExecutor;
import org.fluxgate.core.resilience.RetryConfig;
import org.junit.jupiter.api.BeforeAll;
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

  /**
   * Upper bound for one failed connect. The first Lettuce client in a JVM also pays for class
   * loading and event loop start-up (over a second on CI runners), so the bound is kept well below
   * the 5 s {@link RedisUriUtils#DEFAULT_TIMEOUT} rather than a small multiple of {@link #TIMEOUT}:
   * it still fails if the configured timeout were ignored.
   */
  private static final long MAX_WALL_MILLIS = 2_500;

  @BeforeAll
  static void warmUpLettuce() {
    // A refused connect fails at once and loads the client classes before anything is timed.
    assertThatThrownBy(() -> new StandaloneRedisConnection("redis://127.0.0.1:1", TIMEOUT))
        .isInstanceOf(RedisConnectionException.class);
  }

  @Test
  @DisplayName("StandaloneRedisConnection fails within the configured timeout")
  void standaloneConnectTimeout() {
    Instant start = Instant.now();
    assertThatThrownBy(() -> new StandaloneRedisConnection("redis://192.0.2.1:6379", TIMEOUT))
        .isInstanceOfSatisfying(RedisConnectionException.class, ConnectTimeoutTest::isConnectPhase);
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
        .isInstanceOfSatisfying(RedisConnectionException.class, ConnectTimeoutTest::isConnectPhase);
    long elapsed = Duration.between(start, Instant.now()).toMillis();
    assertThat(elapsed)
        .as("connect failed after %d ms (limit %d ms)", elapsed, MAX_WALL_MILLIS)
        .isLessThan(MAX_WALL_MILLIS);
  }

  @Test
  @DisplayName("A refused connect is a CONNECT-phase failure that the retry policy retries")
  void connectFailureIsRetried() {
    AtomicInteger attempts = new AtomicInteger();
    DefaultRetryExecutor retry =
        new DefaultRetryExecutor(
            RetryConfig.builder().maxAttempts(3).initialBackoff(Duration.ofMillis(1)).build());

    assertThatThrownBy(
            () ->
                retry.execute(
                    () -> {
                      attempts.incrementAndGet();
                      return new StandaloneRedisConnection("redis://127.0.0.1:1", TIMEOUT);
                    }))
        .isInstanceOfSatisfying(
            RedisConnectionException.class,
            e -> {
              isConnectPhase(e);
              assertThat(RetryConfig.defaults().shouldRetry(e)).isTrue();
            });
    assertThat(attempts).hasValue(3);
  }

  private static void isConnectPhase(RedisConnectionException e) {
    assertThat(e.getPhase()).isEqualTo(RedisConnectionException.Phase.CONNECT);
    assertThat(e.isRetryable()).isTrue();
  }
}
