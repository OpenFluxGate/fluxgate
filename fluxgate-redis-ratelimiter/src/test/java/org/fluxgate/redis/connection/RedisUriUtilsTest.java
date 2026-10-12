package org.fluxgate.redis.connection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for {@link RedisUriUtils}. */
class RedisUriUtilsTest {

  @Test
  void defaultTimeoutShouldBeFiveSeconds() {
    assertThat(RedisUriUtils.DEFAULT_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
  }

  // ===== detectMode =====

  @ParameterizedTest
  @CsvSource({
    "redis://localhost:6379, STANDALONE",
    "redis://localhost:6379/2, STANDALONE",
    "rediss://localhost:6380, STANDALONE",
    "'redis://node1:6379,redis://node2:6379', CLUSTER",
    "'redis://node1:6379, redis://node2:6379', CLUSTER",
    "'localhost:6379,localhost:6380', CLUSTER",
  })
  void shouldDetectMode(String uri, RedisMode expected) {
    assertThat(RedisUriUtils.detectMode(uri)).isEqualTo(expected);
  }

  @Test
  @DisplayName("A comma inside the credentials does not make a URI a cluster")
  void shouldIgnoreCommasInsideCredentials() {
    assertThat(RedisUriUtils.detectMode("redis://:pa,ss@localhost:6379"))
        .isEqualTo(RedisMode.STANDALONE);
    assertThat(RedisUriUtils.detectMode("redis://user:pa,ss@localhost:6379"))
        .isEqualTo(RedisMode.STANDALONE);
    assertThat(RedisUriUtils.detectMode("redis://:pa,ss@node1:6379,redis://:pa,ss@node2:6379"))
        .isEqualTo(RedisMode.CLUSTER);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void shouldDefaultToStandaloneForBlankUris(String uri) {
    assertThat(RedisUriUtils.detectMode(uri)).isEqualTo(RedisMode.STANDALONE);
  }

  @Test
  void shouldDefaultToStandaloneForNullUri() {
    assertThat(RedisUriUtils.detectMode(null)).isEqualTo(RedisMode.STANDALONE);
  }

  // ===== splitNodes =====

  @Test
  void shouldSplitNodesInFrontOfEachScheme() {
    assertThat(RedisUriUtils.splitNodes("redis://:pa,ss@node1:6379,redis://:pa,ss@node2:6379"))
        .containsExactly("redis://:pa,ss@node1:6379", "redis://:pa,ss@node2:6379");
  }

  @Test
  void shouldTrimAndDropEmptyNodes() {
    assertThat(RedisUriUtils.splitNodes("redis://node1:6379, redis://node2:6379,"))
        .containsExactly("redis://node1:6379", "redis://node2:6379");
  }

  @Test
  void shouldReturnEmptyListForBlankUri() {
    assertThat(RedisUriUtils.splitNodes(null)).isEmpty();
    assertThat(RedisUriUtils.splitNodes("  ")).isEmpty();
  }

  // ===== mask =====

  @ParameterizedTest
  @CsvSource({
    // the password-only form the docs recommend - the old regex leaked this one
    "redis://:s3cr3t@localhost:6379, localhost:6379",
    "redis://user:s3cr3t@localhost:6379, localhost:6379",
    "redis://localhost:6379, localhost:6379",
    "redis://localhost:6379/3, localhost:6379/3",
    "redis://localhost:6379?password=s3cr3t, localhost:6379",
    "redis://user:s3cr3t@localhost:6379/1?timeout=10s, localhost:6379/1",
  })
  void shouldMaskEverythingButTheEndpoint(String uri, String expected) {
    assertThat(RedisUriUtils.mask(uri)).isEqualTo(expected);
  }

  @Test
  void shouldMaskEveryNodeOfAClusterUri() {
    assertThat(RedisUriUtils.mask("redis://:pw@node1:6379,redis://:pw@node2:6380"))
        .isEqualTo("node1:6379,node2:6380");
  }

  @Test
  void shouldNeverEchoAnUnparseableUri() {
    assertThat(RedisUriUtils.mask("not a uri at all")).doesNotContain("not a uri");
    assertThat(RedisUriUtils.mask(null)).isEqualTo("null");
    assertThat(RedisUriUtils.mask("   ")).isEqualTo("<empty redis uri>");
  }
}
