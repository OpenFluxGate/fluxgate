package org.fluxgate.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.match.PathPatternMatcher;

/**
 * Immutable predicate that decides whether a {@link RequestContext} matches a rate limit rule.
 *
 * <p>All constraints are ANDed together. An empty constraint means "match any":
 *
 * <ul>
 *   <li>Empty {@code methods} — any HTTP method matches
 *   <li>Empty {@code pathPatterns} — any path matches
 *   <li>Empty {@code excludePathPatterns} — no path is excluded
 *   <li>Empty {@code headerEquals} — no header value check is performed
 *   <li>Empty {@code headerPresent} — no header presence check is performed
 * </ul>
 *
 * <p>Exclusion beats inclusion: if a path matches an exclude pattern it is rejected regardless of
 * whether it also matches an include pattern.
 *
 * <p>Header names are normalised to lower-case ({@link Locale#ROOT}) on construction and looked up
 * through {@link RequestContext#getHeader(String)}, which is case-insensitive, so header conditions
 * match regardless of how either side spells the name.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * RuleMatcher m = RuleMatcher.builder()
 *     .methods(Set.of("GET", "POST"))
 *     .addPathPattern("/api/**")
 *     .addExcludePathPattern("/api/health")
 *     .headerEquals("x-tier", "premium")
 *     .build();
 * m.matches(context, SimpleAntPathMatcher.INSTANCE);
 * }</pre>
 *
 * @since 0.4.0
 */
public final class RuleMatcher {

  private final Set<String> methods;
  private final List<String> pathPatterns;
  private final List<String> excludePathPatterns;
  private final Map<String, String> headerEquals;
  private final Set<String> headerPresent;

  private RuleMatcher(Builder builder) {
    this.methods = Collections.unmodifiableSet(new HashSet<>(builder.methods));
    this.pathPatterns = Collections.unmodifiableList(new ArrayList<>(builder.pathPatterns));
    this.excludePathPatterns =
        Collections.unmodifiableList(new ArrayList<>(builder.excludePathPatterns));
    this.headerEquals = Collections.unmodifiableMap(new HashMap<>(builder.headerEquals));
    this.headerPresent = Collections.unmodifiableSet(new HashSet<>(builder.headerPresent));
  }

  // ===== factory =====

  /**
   * Returns a matcher that matches every request (all constraint sets are empty).
   *
   * @return the universal matcher
   */
  public static RuleMatcher matchAll() {
    return builder().build();
  }

  /**
   * Creates a new builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  // ===== accessors =====

  /**
   * Returns the set of allowed HTTP methods (upper-case). Empty means any method matches.
   *
   * @return the methods set (never null, unmodifiable)
   */
  public Set<String> getMethods() {
    return methods;
  }

  /**
   * Returns the list of include path patterns (Ant-style). Empty means any path matches.
   *
   * @return the path patterns (never null, unmodifiable)
   */
  public List<String> getPathPatterns() {
    return pathPatterns;
  }

  /**
   * Returns the list of exclude path patterns (Ant-style). A path matching any of these is excluded
   * regardless of the include patterns.
   *
   * @return the exclude patterns (never null, unmodifiable)
   */
  public List<String> getExcludePathPatterns() {
    return excludePathPatterns;
  }

  /**
   * Returns the required exact header values (header name lower-cased, value exact).
   *
   * @return the header equals map (never null, unmodifiable)
   */
  public Map<String, String> getHeaderEquals() {
    return headerEquals;
  }

  /**
   * Returns the set of header names that must be present (lower-cased).
   *
   * @return the required header names (never null, unmodifiable)
   */
  public Set<String> getHeaderPresent() {
    return headerPresent;
  }

  // ===== matching =====

  /**
   * Returns {@code true} when the given context satisfies all constraints.
   *
   * @param context the request context (must not be null)
   * @param pathMatcher the path pattern matcher to use (must not be null)
   * @return {@code true} if this matcher accepts the request
   */
  public boolean matches(RequestContext context, PathPatternMatcher pathMatcher) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(pathMatcher, "pathMatcher must not be null");

    // method check
    if (!methods.isEmpty()) {
      String method = context.getMethod();
      String upperMethod = (method != null) ? method.toUpperCase(Locale.ROOT) : "";
      if (!methods.contains(upperMethod)) {
        return false;
      }
    }

    // path checks
    String path = context.getEndpoint();
    if (path == null) {
      path = "";
    }

    // exclude beats include
    for (String exclude : excludePathPatterns) {
      if (pathMatcher.matches(exclude, path)) {
        return false;
      }
    }

    if (!pathPatterns.isEmpty()) {
      boolean included = false;
      for (String include : pathPatterns) {
        if (pathMatcher.matches(include, path)) {
          included = true;
          break;
        }
      }
      if (!included) {
        return false;
      }
    }

    // header present check
    for (String headerName : headerPresent) {
      if (context.getHeader(headerName) == null) {
        return false;
      }
    }

    // header equals check
    for (Map.Entry<String, String> entry : headerEquals.entrySet()) {
      String actualValue = context.getHeader(entry.getKey());
      if (!entry.getValue().equals(actualValue)) {
        return false;
      }
    }

    return true;
  }

  // ===== equals / hashCode / toString =====

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RuleMatcher)) return false;
    RuleMatcher that = (RuleMatcher) o;
    return Objects.equals(methods, that.methods)
        && Objects.equals(pathPatterns, that.pathPatterns)
        && Objects.equals(excludePathPatterns, that.excludePathPatterns)
        && Objects.equals(headerEquals, that.headerEquals)
        && Objects.equals(headerPresent, that.headerPresent);
  }

  @Override
  public int hashCode() {
    return Objects.hash(methods, pathPatterns, excludePathPatterns, headerEquals, headerPresent);
  }

  @Override
  public String toString() {
    return "RuleMatcher{"
        + "methods="
        + methods
        + ", pathPatterns="
        + pathPatterns
        + ", excludePathPatterns="
        + excludePathPatterns
        + ", headerEquals="
        + headerEquals
        + ", headerPresent="
        + headerPresent
        + '}';
  }

  // ===== builder =====

  /** Builder for {@link RuleMatcher}. */
  public static final class Builder {
    private final Set<String> methods = new HashSet<>();
    private final List<String> pathPatterns = new ArrayList<>();
    private final List<String> excludePathPatterns = new ArrayList<>();
    private final Map<String, String> headerEquals = new HashMap<>();
    private final Set<String> headerPresent = new HashSet<>();

    private Builder() {}

    /**
     * Sets the required HTTP methods (upper-case). Replaces any previously added methods.
     *
     * @param methods the methods set (may be null or empty to mean "any")
     * @return this builder
     */
    public Builder methods(Set<String> methods) {
      this.methods.clear();
      if (methods != null) {
        for (String m : methods) {
          if (m != null) {
            this.methods.add(m.toUpperCase(Locale.ROOT));
          }
        }
      }
      return this;
    }

    /**
     * Adds an Ant-style include path pattern.
     *
     * @param pattern the pattern (must not be null)
     * @return this builder
     */
    public Builder addPathPattern(String pattern) {
      this.pathPatterns.add(Objects.requireNonNull(pattern, "pattern must not be null"));
      return this;
    }

    /**
     * Adds an Ant-style exclude path pattern. A path matching this is excluded even if it matches
     * an include pattern.
     *
     * @param pattern the pattern (must not be null)
     * @return this builder
     */
    public Builder addExcludePathPattern(String pattern) {
      this.excludePathPatterns.add(Objects.requireNonNull(pattern, "pattern must not be null"));
      return this;
    }

    /**
     * Requires a header to have an exact value. The header name is lower-cased automatically.
     *
     * @param headerName the header name (must not be null)
     * @param value the required value (must not be null)
     * @return this builder
     */
    public Builder headerEquals(String headerName, String value) {
      Objects.requireNonNull(headerName, "headerName must not be null");
      Objects.requireNonNull(value, "value must not be null");
      this.headerEquals.put(headerName.toLowerCase(Locale.ROOT), value);
      return this;
    }

    /**
     * Requires a header to be present (any value). The header name is lower-cased automatically.
     *
     * @param headerName the header name (must not be null)
     * @return this builder
     */
    public Builder headerPresent(String headerName) {
      Objects.requireNonNull(headerName, "headerName must not be null");
      this.headerPresent.add(headerName.toLowerCase(Locale.ROOT));
      return this;
    }

    /**
     * Builds the matcher.
     *
     * @return the matcher
     */
    public RuleMatcher build() {
      return new RuleMatcher(this);
    }
  }
}
