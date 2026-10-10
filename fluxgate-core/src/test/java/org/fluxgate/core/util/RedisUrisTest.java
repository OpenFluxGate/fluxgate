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
    "'redis://:p@n1,u@n2', true",
    "'redis://n1:6379,n2:6380?clientName=a@b', true",
    "'redis://host:6379?clientName=a@b', false",
    "'redis://:p@ss@host:6379', false",
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

  @Test
  void singleSchemeKeepsCommaInPassword() {
    assertThat(RedisUris.splitNodes("redis://:pa,ss@host:6379"))
        .containsExactly("redis://:pa,ss@host:6379");
    assertThat(RedisUris.splitNodes("redis://:pa,ss@n1:6379,n2:6380"))
        .containsExactly("redis://:pa,ss@n1:6379", "n2:6380");
  }

  @Test
  void credentialsEndInTheFirstNodesAuthority() {
    // a comma between two '@' separates nodes: hosts never contain '@'
    assertThat(RedisUris.splitNodes("redis://:p@n1,u@n2")).containsExactly("redis://:p@n1", "u@n2");
    // an '@' in the query is not the end of the credentials
    assertThat(RedisUris.splitNodes("redis://n1:6379,n2:6380?clientName=a@b"))
        .containsExactly("redis://n1:6379", "n2:6380?clientName=a@b");
    // an '@' inside the password still belongs to the credentials
    assertThat(RedisUris.splitNodes("redis://:p@ss@n1:6379,n2:6380"))
        .containsExactly("redis://:p@ss@n1:6379", "n2:6380");
  }
}
