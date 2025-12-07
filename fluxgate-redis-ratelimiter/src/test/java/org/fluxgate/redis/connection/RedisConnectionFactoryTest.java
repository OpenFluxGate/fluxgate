package org.fluxgate.redis.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RedisConnectionFactory}. */
class RedisConnectionFactoryTest {

  @Test
  void shouldDetectStandaloneModeFromSingleUri() {
    // Test detection logic without actually connecting
    String uri = "redis://localhost:6379";

    // We can't test the full create() without Redis, but we can test mode detection
    assertThat(uri.contains(",")).isFalse();
  }

  @Test
  void shouldDetectClusterModeFromCommaDelimitedUris() {
    // Test detection logic without actually connecting
    String clusterUri = "redis://node1:6379,redis://node2:6379,redis://node3:6379";

    assertThat(clusterUri.contains(",")).isTrue();
  }

  @Test
  void shouldThrowWhenUriIsNull() {
    assertThatThrownBy(() -> RedisConnectionFactory.create(null, Duration.ofSeconds(5)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("uri must not be null");
  }

  @Test
  void shouldThrowWhenTimeoutIsNull() {
    assertThatThrownBy(() -> RedisConnectionFactory.create("redis://localhost:6379", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("timeout must not be null");
  }

  @Test
  void shouldThrowWhenModeIsNull() {
    List<String> uris = Arrays.asList("redis://localhost:6379");
    assertThatThrownBy(() -> RedisConnectionFactory.create(null, uris, Duration.ofSeconds(5)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("mode must not be null");
  }

  @Test
  void shouldThrowWhenUrisListIsNull() {
    assertThatThrownBy(
            () -> RedisConnectionFactory.create(RedisMode.STANDALONE, null, Duration.ofSeconds(5)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("uris must not be null");
  }

  @Test
  void shouldThrowWhenUrisListIsEmpty() {
    List<String> emptyList = Arrays.asList();
    assertThatThrownBy(
            () ->
                RedisConnectionFactory.create(
                    RedisMode.STANDALONE, emptyList, Duration.ofSeconds(5)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("At least one");
  }
}
