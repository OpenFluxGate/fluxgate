package org.fluxgate.core.match;

import java.util.Arrays;
import java.util.Objects;

/**
 * Ant-style path pattern matcher.
 *
 * <p>Supported wildcard tokens:
 *
 * <ul>
 *   <li>{@code ?} — matches exactly one character (never {@code /})
 *   <li>{@code *} — matches zero or more characters within a single path segment (never {@code /})
 *   <li>{@code **} — matches any sequence of characters including {@code /}; when written as a
 *       whole segment ({@code /**}{@code /}) it matches zero or more complete path segments, so
 *       {@code /**}{@code /health} matches {@code /health} and {@code /a/b/health} but not {@code
 *       /api/unhealth}
 * </ul>
 *
 * <p>Matching runs in {@code O(pattern * path)} time without backtracking.
 *
 * <p>A leading or trailing slash in the pattern is significant: {@code /api/users} does not match
 * the pattern {@code api/users}.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * PathPatternMatcher m = SimpleAntPathMatcher.INSTANCE; // case-sensitive
 * m.matches("/api/**",    "/api/users/123"); // true
 * m.matches("/api/*",     "/api/users");     // true
 * m.matches("/api/*",     "/api/users/123"); // false – * does not cross /
 * m.matches("/a?c",       "/abc");           // true
 * m.matches("/a?c",       "/a/c");           // false – ? does not match /
 * }</pre>
 *
 * @since 0.4.0
 */
public final class SimpleAntPathMatcher implements PathPatternMatcher {

  /** Default case-sensitive instance; use this in most situations. */
  public static final SimpleAntPathMatcher INSTANCE = new SimpleAntPathMatcher(true);

  private final boolean caseSensitive;

  /**
   * Creates a new matcher.
   *
   * @param caseSensitive {@code true} to compare characters case-sensitively
   */
  public SimpleAntPathMatcher(boolean caseSensitive) {
    this.caseSensitive = caseSensitive;
  }

  @Override
  public boolean matches(String pattern, String path) {
    Objects.requireNonNull(pattern, "pattern must not be null");
    Objects.requireNonNull(path, "path must not be null");
    if (pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0) {
      return literalMatch(pattern, path);
    }
    return dpMatch(tokenize(pattern), path);
  }

  // ===== internal =====

  // Token values: literal characters are their (non-negative) char value; wildcards are negative.
  private static final int STAR = -1;
  private static final int DOUBLE_STAR = -2;
  private static final int QUESTION = -3;

  private boolean literalMatch(String pattern, String path) {
    if (pattern.length() != path.length()) {
      return false;
    }
    for (int i = 0; i < pattern.length(); i++) {
      if (!charMatch(pattern.charAt(i), path.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Splits the pattern into tokens. Runs of two or more stars collapse into one {@code **}, and a
   * {@code **}{@code /}{@code **} whose first {@code **} starts a segment collapses into a single
   * {@code **}, since both are equivalent there.
   */
  private static int[] tokenize(String pattern) {
    int[] tokens = new int[pattern.length()];
    int n = 0;
    int i = 0;
    while (i < pattern.length()) {
      char c = pattern.charAt(i);
      if (c == '*') {
        int j = i;
        while (j < pattern.length() && pattern.charAt(j) == '*') {
          j++;
        }
        if (j - i == 1) {
          tokens[n++] = STAR;
        } else if (n >= 2
            && tokens[n - 2] == DOUBLE_STAR
            && tokens[n - 1] == '/'
            && (n == 2 || tokens[n - 3] == '/')) {
          // "/**/**" is equivalent to "/**": drop the separator, keep the previous **. Only when
          // that ** starts a segment: "a**/**" still needs the '/' ("abc" must not match).
          n--;
        } else {
          tokens[n++] = DOUBLE_STAR;
        }
        i = j;
      } else {
        tokens[n++] = (c == '?') ? QUESTION : c;
        i++;
      }
    }
    return Arrays.copyOf(tokens, n);
  }

  /**
   * Bottom-up dynamic programming over (token, path position): {@code O(tokens * path)} time and
   * {@code O(path)} memory, without backtracking. Row {@code t} holds, for every path offset {@code
   * i}, whether {@code tokens[t..]} matches {@code path[i..]}.
   */
  private boolean dpMatch(int[] tokens, String path) {
    final int n = tokens.length;
    final int sLen = path.length();
    boolean[] next2 = new boolean[sLen + 1]; // row t + 2
    boolean[] next = new boolean[sLen + 1]; // row t + 1
    boolean[] cur = new boolean[sLen + 1]; // row t
    next[sLen] = true; // the empty pattern matches only the empty remainder

    for (int t = n - 1; t >= 0; t--) {
      int tok = tokens[t];
      Arrays.fill(cur, false);

      if (tok == DOUBLE_STAR) {
        if (t == n - 1) {
          Arrays.fill(cur, true); // trailing ** matches everything remaining
        } else {
          // ** consumes any characters (including '/') before the rest of the pattern
          for (int i = sLen; i >= 0; i--) {
            cur[i] = next[i] || (i < sLen && cur[i + 1]);
          }
          // "**/" may also match zero segments by skipping its separator, but only when **
          // starts a segment and only at a segment boundary of the path, never mid-segment.
          boolean followedBySlash = tokens[t + 1] == '/';
          boolean startsSegment = t == 0 || tokens[t - 1] == '/';
          if (followedBySlash && startsSegment) {
            for (int i = 0; i <= sLen; i++) {
              if (next2[i] && (i == 0 || path.charAt(i - 1) == '/')) {
                cur[i] = true;
              }
            }
          }
        }
      } else if (tok == STAR) {
        for (int i = sLen; i >= 0; i--) {
          cur[i] = next[i] || (i < sLen && path.charAt(i) != '/' && cur[i + 1]);
        }
      } else if (tok == QUESTION) {
        for (int i = 0; i < sLen; i++) {
          cur[i] = path.charAt(i) != '/' && next[i + 1];
        }
      } else {
        for (int i = 0; i < sLen; i++) {
          cur[i] = charMatch((char) tok, path.charAt(i)) && next[i + 1];
        }
      }

      boolean[] recycled = next2;
      next2 = next;
      next = cur;
      cur = recycled;
    }
    return next[0];
  }

  private boolean charMatch(char pc, char sc) {
    if (caseSensitive) {
      return pc == sc;
    }
    return Character.toLowerCase(pc) == Character.toLowerCase(sc);
  }
}
