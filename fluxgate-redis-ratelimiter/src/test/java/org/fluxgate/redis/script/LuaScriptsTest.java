package org.fluxgate.redis.script;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link LuaScripts}. */
class LuaScriptsTest {

  @BeforeEach
  void setUp() {
    // Reset state before each test
    LuaScripts.setTokenBucketConsumeSha(null);
    LuaScripts.setTokenBucketConsumeScript(null);
  }

  @AfterEach
  void tearDown() {
    // Clean up after each test
    LuaScripts.setTokenBucketConsumeSha(null);
    LuaScripts.setTokenBucketConsumeScript(null);
  }

  @Test
  void shouldReturnNullWhenShaNotSet() {
    assertThat(LuaScripts.getTokenBucketConsumeSha()).isNull();
  }

  @Test
  void shouldSetAndGetTokenBucketConsumeSha() {
    String sha = "abc123def456";
    LuaScripts.setTokenBucketConsumeSha(sha);

    assertThat(LuaScripts.getTokenBucketConsumeSha()).isEqualTo(sha);
  }

  @Test
  void shouldReturnNullWhenScriptNotSet() {
    assertThat(LuaScripts.getTokenBucketConsumeScript()).isNull();
  }

  @Test
  void shouldSetAndGetTokenBucketConsumeScript() {
    String script = "return redis.call('GET', KEYS[1])";
    LuaScripts.setTokenBucketConsumeScript(script);

    assertThat(LuaScripts.getTokenBucketConsumeScript()).isEqualTo(script);
  }

  @Test
  void shouldOverwritePreviousValues() {
    LuaScripts.setTokenBucketConsumeSha("old-sha");
    LuaScripts.setTokenBucketConsumeScript("old-script");

    LuaScripts.setTokenBucketConsumeSha("new-sha");
    LuaScripts.setTokenBucketConsumeScript("new-script");

    assertThat(LuaScripts.getTokenBucketConsumeSha()).isEqualTo("new-sha");
    assertThat(LuaScripts.getTokenBucketConsumeScript()).isEqualTo("new-script");
  }
}
