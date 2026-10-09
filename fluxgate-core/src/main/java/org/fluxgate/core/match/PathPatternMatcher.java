package org.fluxgate.core.match;

/**
 * Matches a concrete path against an Ant-style pattern.
 *
 * <p>The interface is intentionally narrow so that implementations can be stateless singletons.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * PathPatternMatcher m = SimpleAntPathMatcher.INSTANCE;
 * m.matches("/api/**", "/api/users/123"); // true
 * m.matches("/api/*",  "/api/users/123"); // false
 * }</pre>
 *
 * @since 0.4.0
 */
public interface PathPatternMatcher {

  /**
   * Returns {@code true} when {@code path} matches {@code pattern}.
   *
   * @param pattern the Ant-style pattern (must not be {@code null})
   * @param path the concrete path to test (must not be {@code null})
   * @return {@code true} if the path matches the pattern
   */
  boolean matches(String pattern, String path);
}
