package org.fluxgate.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.Set;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.match.SimpleAntPathMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RuleMatcher Tests")
class RuleMatcherTest {

  private static final SimpleAntPathMatcher PATH_MATCHER = SimpleAntPathMatcher.INSTANCE;

  private static RequestContext ctx(String method, String path) {
    return RequestContext.builder().method(method).endpoint(path).build();
  }

  private static RequestContext ctxWithHeader(
      String method, String path, String hName, String hVal) {
    return RequestContext.builder().method(method).endpoint(path).header(hName, hVal).build();
  }

  // ===== matchAll =====

  @Test
  @DisplayName("matchAll() accepts any request")
  void matchAll_acceptsEverything() {
    RuleMatcher m = RuleMatcher.matchAll();
    assertThat(m.matches(ctx("GET", "/anything/at/all"), PATH_MATCHER)).isTrue();
    assertThat(m.matches(ctx("DELETE", "/admin/delete"), PATH_MATCHER)).isTrue();
  }

  // ===== methods =====

  @Nested
  @DisplayName("Method matching")
  class MethodTests {

    @Test
    @DisplayName("method filter accepts listed method")
    void methodMatchesListed() {
      Set<String> methods = Collections.singleton("GET");
      RuleMatcher m = RuleMatcher.builder().methods(methods).build();
      assertThat(m.matches(ctx("GET", "/api"), PATH_MATCHER)).isTrue();
    }

    @Test
    @DisplayName("method filter rejects unlisted method")
    void methodRejectsUnlisted() {
      Set<String> methods = Collections.singleton("GET");
      RuleMatcher m = RuleMatcher.builder().methods(methods).build();
      assertThat(m.matches(ctx("POST", "/api"), PATH_MATCHER)).isFalse();
    }

    @Test
    @DisplayName(
        "method comparison is case-insensitive on input (builder normalises to upper-case)")
    void methodInputCaseInsensitive() {
      Set<String> methods = Collections.singleton("get"); // lower-case input
      RuleMatcher m = RuleMatcher.builder().methods(methods).build();
      assertThat(m.matches(ctx("GET", "/api"), PATH_MATCHER)).isTrue();
    }
  }

  // ===== path patterns =====

  @Nested
  @DisplayName("Path matching")
  class PathTests {

    @Test
    @DisplayName("include pattern matches /api/ prefix")
    void includePattern() {
      RuleMatcher m = RuleMatcher.builder().addPathPattern("/api/**").build();
      assertThat(m.matches(ctx("GET", "/api/users/1"), PATH_MATCHER)).isTrue();
      assertThat(m.matches(ctx("GET", "/admin/users"), PATH_MATCHER)).isFalse();
    }

    @Test
    @DisplayName("exclude beats include")
    void excludeBeatsInclude() {
      RuleMatcher m =
          RuleMatcher.builder()
              .addPathPattern("/api/**")
              .addExcludePathPattern("/api/health")
              .build();
      assertThat(m.matches(ctx("GET", "/api/users"), PATH_MATCHER)).isTrue();
      assertThat(m.matches(ctx("GET", "/api/health"), PATH_MATCHER)).isFalse();
    }

    @Test
    @DisplayName("no patterns means any path accepted")
    void noPatternsAcceptsAll() {
      RuleMatcher m = RuleMatcher.builder().build();
      assertThat(m.matches(ctx("GET", "/anything"), PATH_MATCHER)).isTrue();
    }
  }

  // ===== header matching =====

  @Nested
  @DisplayName("Header matching")
  class HeaderTests {

    @Test
    @DisplayName("headerEquals matches exact value")
    void headerEqualsExact() {
      RuleMatcher m = RuleMatcher.builder().headerEquals("x-tier", "premium").build();
      RequestContext yes = ctxWithHeader("GET", "/api", "x-tier", "premium");
      RequestContext no = ctxWithHeader("GET", "/api", "x-tier", "free");
      assertThat(m.matches(yes, PATH_MATCHER)).isTrue();
      assertThat(m.matches(no, PATH_MATCHER)).isFalse();
    }

    @Test
    @DisplayName("headerEquals name is case-insensitive (builder lower-cases it)")
    void headerNameCaseInsensitive() {
      RuleMatcher m = RuleMatcher.builder().headerEquals("X-Tier", "premium").build();
      // context header stored with lower-case name
      RequestContext yes = ctxWithHeader("GET", "/api", "x-tier", "premium");
      assertThat(m.matches(yes, PATH_MATCHER)).isTrue();
    }

    @Test
    @DisplayName("headerPresent requires the header to be present")
    void headerPresent() {
      RuleMatcher m = RuleMatcher.builder().headerPresent("x-api-version").build();
      RequestContext yes = ctxWithHeader("GET", "/api", "x-api-version", "2");
      RequestContext no = ctx("GET", "/api");
      assertThat(m.matches(yes, PATH_MATCHER)).isTrue();
      assertThat(m.matches(no, PATH_MATCHER)).isFalse();
    }
  }

  // ===== combined =====

  @Test
  @DisplayName("all constraints are ANDed")
  void allConstraintsAnded() {
    RuleMatcher m =
        RuleMatcher.builder()
            .methods(Collections.singleton("POST"))
            .addPathPattern("/api/**")
            .headerPresent("authorization")
            .build();

    RequestContext ok =
        RequestContext.builder()
            .method("POST")
            .endpoint("/api/orders")
            .header("authorization", "Bearer token")
            .build();
    RequestContext wrongMethod =
        RequestContext.builder()
            .method("GET")
            .endpoint("/api/orders")
            .header("authorization", "Bearer token")
            .build();
    RequestContext missingHeader =
        RequestContext.builder().method("POST").endpoint("/api/orders").build();

    assertThat(m.matches(ok, PATH_MATCHER)).isTrue();
    assertThat(m.matches(wrongMethod, PATH_MATCHER)).isFalse();
    assertThat(m.matches(missingHeader, PATH_MATCHER)).isFalse();
  }
}
