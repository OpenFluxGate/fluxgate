package org.fluxgate.core.match;

import java.util.Objects;

/**
 * Ant-style path pattern matcher.
 *
 * <p>Supported wildcard tokens:
 *
 * <ul>
 *   <li>{@code ?} — matches exactly one character (never {@code /})
 *   <li>{@code *} — matches zero or more characters within a single path segment (never {@code /})
 *   <li>{@code **} — matches zero or more path segments, i.e. any sequence including {@code /}
 * </ul>
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
    return doMatch(pattern, 0, path, 0);
  }

  // ===== internal =====

  private boolean doMatch(String pattern, int pi, String path, int si) {
    final int pLen = pattern.length();
    final int sLen = path.length();

    while (pi < pLen) {
      char pc = pattern.charAt(pi);

      if (pc == '*') {
        boolean isDouble = (pi + 1 < pLen && pattern.charAt(pi + 1) == '*');
        if (isDouble) {
          // ** — skip the two stars
          int nextPi = pi + 2;
          if (nextPi >= pLen) {
            // ** at end matches everything remaining
            return true;
          }
          // optional separator after **
          int altPi = nextPi;
          if (altPi < pLen && pattern.charAt(altPi) == '/') {
            altPi++;
          }
          // try matching the rest of the pattern at every position from si to sLen
          for (int i = si; i <= sLen; i++) {
            if (doMatch(pattern, nextPi, path, i)) {
              return true;
            }
            if (nextPi != altPi && doMatch(pattern, altPi, path, i)) {
              return true;
            }
          }
          return false;
        } else {
          // single * — matches zero or more non-/ chars
          int nextPi = pi + 1;
          for (int i = si; i <= sLen; i++) {
            if (doMatch(pattern, nextPi, path, i)) {
              return true;
            }
            if (i < sLen && path.charAt(i) == '/') {
              break; // * must not cross a path separator
            }
          }
          return false;
        }
      }

      if (pc == '?') {
        if (si >= sLen || path.charAt(si) == '/') {
          return false; // ? must match exactly one non-/ char
        }
        pi++;
        si++;
        continue;
      }

      // literal character
      if (si >= sLen) {
        return false;
      }
      if (!charMatch(pc, path.charAt(si))) {
        return false;
      }
      pi++;
      si++;
    }

    return si == sLen;
  }

  private boolean charMatch(char pc, char sc) {
    if (caseSensitive) {
      return pc == sc;
    }
    return Character.toLowerCase(pc) == Character.toLowerCase(sc);
  }
}
