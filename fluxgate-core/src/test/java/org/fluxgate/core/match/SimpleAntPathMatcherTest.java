package org.fluxgate.core.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
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

  // ===== ** segment boundaries and complexity =====

  @Nested
  @DisplayName("** segment boundaries")
  class DoubleStarBoundaryTests {

    @Test
    @DisplayName("/**/health matches only a whole 'health' segment")
    void leadingDoubleStarRespectsSegmentBoundary() {
      assertThat(matcher.matches("/**/health", "/health")).isTrue();
      assertThat(matcher.matches("/**/health", "/api/health")).isTrue();
      assertThat(matcher.matches("/**/health", "/a/b/health")).isTrue();
      assertThat(matcher.matches("/**/health", "/api/unhealth")).isFalse();
      assertThat(matcher.matches("/**/health", "/unhealth")).isFalse();
    }

    @Test
    @DisplayName("/api/**/users does not match a segment that merely ends with 'users'")
    void middleDoubleStarRespectsSegmentBoundary() {
      assertThat(matcher.matches("/api/**/users", "/api/v1/superusers")).isFalse();
      assertThat(matcher.matches("/api/**/users", "/api/xusers")).isFalse();
      assertThat(matcher.matches("/api/**/users", "/api/v1/users")).isTrue();
      assertThat(matcher.matches("/api/**/users", "/api/users")).isTrue();
    }

    @Test
    @DisplayName("**/ at the pattern start matches zero or more leading segments")
    void patternStartDoubleStar() {
      assertThat(matcher.matches("**/x.json", "x.json")).isTrue();
      assertThat(matcher.matches("**/x.json", "a/b/x.json")).isTrue();
      assertThat(matcher.matches("**/x.json", "ax.json")).isFalse();
    }

    @Test
    @DisplayName("consecutive ** are equivalent to a single **")
    void consecutiveDoubleStars() {
      assertThat(matcher.matches("/a/**/**/b", "/a/b")).isTrue();
      assertThat(matcher.matches("/a/**/**/b", "/a/x/y/b")).isTrue();
      assertThat(matcher.matches("/a/**/**/b", "/a/xb")).isFalse();
      assertThat(matcher.matches("/a/****", "/a/x/y")).isTrue();
    }

    @Test
    @DisplayName("a ** that does not start a segment is not merged with a following /**")
    void midSegmentDoubleStarIsNotCollapsed() {
      assertThat(matcher.matches("a**/**", "abc")).isFalse();
      assertThat(matcher.matches("a**/**", "abc/")).isTrue();
      assertThat(matcher.matches("a**/**", "ab/c/d")).isTrue();
      assertThat(matcher.matches("/a**/**/b", "/ab")).isFalse();
      assertThat(matcher.matches("/a**/**/b", "/ax/b")).isTrue();
    }

    @Test
    @DisplayName("pathological pattern against a 6000-char path completes quickly")
    void pathologicalPatternIsFast() {
      String pattern = "/**/a*/**/a*/**/a*/**/a*/**/a*/**/a*/**/b";
      StringBuilder sb = new StringBuilder();
      while (sb.length() < 6000) {
        sb.append("/aaaaaaaa");
      }
      String path = sb.append("/c").toString();

      boolean result =
          assertTimeoutPreemptively(Duration.ofMillis(200), () -> matcher.matches(pattern, path));

      assertThat(result).isFalse();
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
