package org.fluxgate.redis.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

class ClusterTestSupportTest {

  // the cluster CI job runs this class with the property set; keep its value intact
  private String saved;

  @BeforeEach
  void saveRequire() {
    saved = System.getProperty(ClusterTestSupport.REQUIRE_PROPERTY);
  }

  @AfterEach
  void restoreRequire() {
    if (saved == null) {
      System.clearProperty(ClusterTestSupport.REQUIRE_PROPERTY);
    } else {
      System.setProperty(ClusterTestSupport.REQUIRE_PROPERTY, saved);
    }
  }

  @Test
  @DisplayName("An unreachable cluster skips the tests when the cluster is not required")
  void unreachableClusterSkipsByDefault() {
    assertThatThrownBy(() -> ClusterTestSupport.clusterUnavailable("no cluster", false))
        .isInstanceOf(TestAbortedException.class)
        .hasMessageContaining("no cluster");
  }

  @Test
  @DisplayName("An unreachable cluster fails the tests when the cluster is required")
  void unreachableClusterFailsWhenRequired() {
    assertThatThrownBy(() -> ClusterTestSupport.clusterUnavailable("no cluster", true))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("no cluster")
        .hasMessageContaining(ClusterTestSupport.REQUIRE_PROPERTY);
  }

  @Test
  @DisplayName("fluxgate.redis.cluster.require=true makes the cluster required")
  void requirePropertyMakesTheClusterRequired() {
    System.setProperty(ClusterTestSupport.REQUIRE_PROPERTY, "true");
    assertThat(ClusterTestSupport.clusterRequired()).isTrue();

    System.setProperty(ClusterTestSupport.REQUIRE_PROPERTY, "false");
    assertThat(ClusterTestSupport.clusterRequired()).isFalse();
  }
}
