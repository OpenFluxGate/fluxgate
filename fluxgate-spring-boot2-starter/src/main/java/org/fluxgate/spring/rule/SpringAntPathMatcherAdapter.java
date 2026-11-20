package org.fluxgate.spring.rule;

import org.fluxgate.core.match.PathPatternMatcher;
import org.springframework.util.AntPathMatcher;

/**
 * {@link PathPatternMatcher} adapter that delegates to Spring's {@link AntPathMatcher}.
 *
 * <p>Using Spring's matcher instead of {@link org.fluxgate.core.match.SimpleAntPathMatcher} gives
 * users the same Ant matching semantics they already rely on in Spring MVC or Spring Security path
 * configurations. The case-sensitivity flag comes from {@code
 * fluxgate.ratelimit.case-sensitive-patterns} (default {@code true}).
 *
 * @since 0.4.0
 */
public final class SpringAntPathMatcherAdapter implements PathPatternMatcher {

  private final AntPathMatcher delegate;

  /**
   * Creates a new adapter.
   *
   * @param caseSensitive {@code true} to compare path characters case-sensitively
   */
  public SpringAntPathMatcherAdapter(boolean caseSensitive) {
    this.delegate = new AntPathMatcher();
    this.delegate.setCaseSensitive(caseSensitive);
  }

  @Override
  public boolean matches(String pattern, String path) {
    return delegate.match(pattern, path);
  }
}
