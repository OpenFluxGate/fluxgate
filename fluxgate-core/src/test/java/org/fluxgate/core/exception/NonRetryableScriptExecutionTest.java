package org.fluxgate.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NonRetryableScriptExecutionTest {
  @Test
  void canPreserveCauseWhilePreventingWholeRequestReplay() {
    RuntimeException cause = new RuntimeException("STALE_POLICY");
    ScriptExecutionException failure =
        new ScriptExecutionException("STALE_POLICY", "consume", cause, false);
    assertThat(failure.isRetryable()).isFalse();
    assertThat(failure.getCause()).isSameAs(cause);
    assertThat(failure.getScriptName()).isEqualTo("consume");
  }
}
