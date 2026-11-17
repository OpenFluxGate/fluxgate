package org.fluxgate.core.reload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PublishedEpochResetGuardTest {
  @Test
  void neverRegistersAsListenerOrDeletesCounters() {
    BucketResetHandler guard = new PublishedEpochResetGuard();
    assertThat(guard).isNotInstanceOf(RuleReloadListener.class);
    assertThatThrownBy(() -> guard.resetBuckets("orders"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("explicit policy reset");
    assertThatThrownBy(guard::resetAllBuckets).isInstanceOf(UnsupportedOperationException.class);
  }
}
