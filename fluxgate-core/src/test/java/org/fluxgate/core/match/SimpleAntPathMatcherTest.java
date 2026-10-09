package org.fluxgate.core.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SimpleAntPathMatcher Tests")
class SimpleAntPathMatcherTest {

  private final SimpleAntPathMatcher matcher = SimpleAntPathMatcher.INSTANCE;

  // ===== static INSTANCE =====

  @Test
  @DisplayName("INSTANCE is case-sensitive")
  void instance_isCaseSensitive() {
    assertThat(matcher.matches("/Api/users", "/api/users")).isFalse();
    assertThat(matcher.matches("/api/users", "/api/users")).isTrue();
  }

  // ===== ** (double-star) =====

  @Nested
  @DisplayName("** wildcard")
  class DoubleStarTests {

    @Test
    @DisplayName("/** matches any path")
    void doubleStarMatchesAnything() {
      assertThat(matcher.matches("/**", "/")).isTrue();
      assertThat(matcher.matches("/**", "/a")).isTrue();
      assertThat(matcher.matches("/**", "/a/b/c")).isTrue();
    }

    @Test
    @DisplayName("/a/** matches /a and descendants")
    void doubleStarMatchesDescendants() {
      assertThat(matcher.matches("/a/**", "/a/b")).isTrue();
      assertThat(matcher.matches("/a/**", "/a/b/c")).isTrue();
      assertThat(matcher.matches("/a/**", "/a/")).isTrue();
    }

    @Test
    @DisplayName("/a/** does not match /b/c")
    void doubleStarDoesNotMatchOtherPrefix() {
      assertThat(matcher.matches("/a/**", "/b/c")).isFalse();
    }

    @Test
    @DisplayName("/api/**/users matches path with any segments in between")
    void doubleStarMiddle() {
      assertThat(matcher.matches("/api/**/users", "/api/v1/users")).isTrue();
      assertThat(matcher.matches("/api/**/users", "/api/v1/v2/users")).isTrue();
      assertThat(matcher.matches("/api/**/users", "/api/users")).isTrue();
    }
  }

  // ===== * (single-star) =====

  @Nested
  @DisplayName("* wildcard (single segment)")
  class SingleStarTests {

    @Test
    @DisplayName("/a/* matches /a/b but not /a/b/c")
    void singleStarOneSegment() {
      assertThat(matcher.matches("/a/*", "/a/b")).isTrue();
      assertThat(matcher.matches("/a/*", "/a/b/c")).isFalse();
    }

    @Test
    @DisplayName("/a/* matches /a/ (empty segment after slash)")
    void singleStarEmptySegment() {
      assertThat(matcher.matches("/a/*", "/a/")).isTrue();
    }

    @Test
    @DisplayName("prefix* matches within segment")
    void singleStarPrefix() {
      assertThat(matcher.matches("/api*", "/apiv2")).isTrue();
      assertThat(matcher.matches("/api*", "/api/v2")).isFalse();
    }

    @Test
    @DisplayName("*suffix matches within segment")
    void singleStarSuffix() {
      assertThat(matcher.matches("*.json", "data.json")).isTrue();
      assertThat(matcher.matches("*.json", "data.xml")).isFalse();
    }
  }

  // ===== ? (question mark) =====

  @Nested
  @DisplayName("? wildcard (single char)")
  class QuestionMarkTests {

    @Test
    @DisplayName("? matches any single non-/ character")
    void questionMatchesSingleChar() {
      assertThat(matcher.matches("/a?c", "/abc")).isTrue();
      assertThat(matcher.matches("/a?c", "/axc")).isTrue();
    }

    @Test
    @DisplayName("? does not match /")
    void questionDoesNotMatchSlash() {
      assertThat(matcher.matches("/a?c", "/a/c")).isFalse();
    }

    @Test
    @DisplayName("? does not match empty")
    void questionDoesNotMatchEmpty() {
      assertThat(matcher.matches("/a?c", "/ac")).isFalse();
    }
  }

  // ===== trailing slash =====

  @Nested
  @DisplayName("Trailing slash")
  class TrailingSlashTests {

    @Test
    @DisplayName("pattern /a/ does not match /a")
    void trailingSlashPatternNotMatchNoSlash() {
      assertThat(matcher.matches("/a/", "/a")).isFalse();
    }

    @Test
    @DisplayName("pattern /a does not match /a/")
    void noTrailingSlashPatternNotMatchSlash() {
      assertThat(matcher.matches("/a", "/a/")).isFalse();
    }
  }

  // ===== case sensitivity =====

  @Nested
  @DisplayName("Case sensitivity")
  class CaseSensitivityTests {

    @Test
    @DisplayName("case-sensitive instance rejects different case")
    void caseSensitiveMismatch() {
      assertThat(new SimpleAntPathMatcher(true).matches("/Api/**", "/api/users")).isFalse();
    }

    @Test
    @DisplayName("case-insensitive instance accepts different case")
    void caseInsensitiveMatch() {
      assertThat(new SimpleAntPathMatcher(false).matches("/Api/**", "/api/users")).isTrue();
    }
  }

  // ===== no-match edge cases =====

  @Nested
  @DisplayName("No-match cases")
  class NoMatchTests {

    @Test
    @DisplayName("different path prefix returns false")
    void differentPrefix() {
      assertThat(matcher.matches("/admin/**", "/api/users")).isFalse();
    }

    @Test
    @DisplayName("exact match returns true")
    void exactMatch() {
      assertThat(matcher.matches("/api/users", "/api/users")).isTrue();
    }

    @Test
    @DisplayName("null pattern throws NullPointerException")
    void nullPatternThrows() {
      assertThatNullPointerException().isThrownBy(() -> matcher.matches(null, "/path"));
    }

    @Test
    @DisplayName("null path throws NullPointerException")
    void nullPathThrows() {
      assertThatNullPointerException().isThrownBy(() -> matcher.matches("/pattern", null));
    }
  }
}
