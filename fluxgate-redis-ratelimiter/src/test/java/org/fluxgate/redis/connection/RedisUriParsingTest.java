package org.fluxgate.redis.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A URI that Lettuce cannot parse must not leak its credentials through the exception. */
class RedisUriParsingTest {

  private static final String SECRET = "s3cret";

  @Test
  @DisplayName("Standalone: an unparseable URI is reported masked, without the password")
  void standaloneParseFailureIsMasked() {
    Throwable thrown =
        catchThrowable(() -> new StandaloneRedisConnection("redis://:" + SECRET + "@ho st:6379"));

    assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    assertNoSecretInChain(thrown);
  }

  @Test
  @DisplayName("Cluster: an unparseable node URI is reported masked, without the password")
  void clusterParseFailureIsMasked() {
    Throwable thrown =
        catchThrowable(
            () ->
                new ClusterRedisConnection(
                    List.of("redis://:" + SECRET + "@[bad", "redis://127.0.0.1:1"),
                    Duration.ofMillis(200)));

    assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    assertNoSecretInChain(thrown);
  }

  private static void assertNoSecretInChain(Throwable thrown) {
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      assertThat(String.valueOf(t.getMessage())).doesNotContain(SECRET);
      for (Throwable suppressed : t.getSuppressed()) {
        assertThat(String.valueOf(suppressed.getMessage())).doesNotContain(SECRET);
      }
    }
  }
}
