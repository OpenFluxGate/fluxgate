package org.fluxgate.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RedisUrisTest {

  @ParameterizedTest
  @CsvSource({
    "redis://localhost:6379, false",
    "redis://localhost:6379/2, false",
    "rediss://localhost:6380, false",
    "'redis://node1:6379,redis://node2:6379', true",
    "'redis://node1:6379, redis://node2:6379', true",
    "'localhost:6379,localhost:6380', true",
    "'redis://:pa,ss@localhost:6379', false",
    "'redis://user:p,w@localhost:6379/1', false",
    "'redis://:s,1@n1:6379,redis://:s,1@n2:6379', true",
  })
  void isCluster(String uri, boolean expected) {
    assertThat(RedisUris.isCluster(uri)).isEqualTo(expected);
  }

  @Test
  void blankIsNotACluster() {
    assertThat(RedisUris.isCluster(null)).isFalse();
    assertThat(RedisUris.isCluster("  ")).isFalse();
  }

  @Test
  void splitKeepsCommasInsideCredentials() {
    assertThat(RedisUris.splitNodes("redis://:s,1@n1:6379, redis://:t,2@n2:6379"))
        .containsExactly("redis://:s,1@n1:6379", "redis://:t,2@n2:6379");
  }

  @Test
  void splitWithoutSchemesUsesPlainCommas() {
    assertThat(RedisUris.splitNodes(",a:1,b:2,")).containsExactly("a:1", "b:2");
  }

  @Test
  void splitOfBlankIsEmpty() {
    assertThat(RedisUris.splitNodes(null)).isEmpty();
    assertThat(RedisUris.splitNodes(" ")).isEmpty();
  }
}
