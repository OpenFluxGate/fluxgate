package org.fluxgate.spring.util;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

/** Unit tests for {@link RequestPathResolver}. */
class RequestPathResolverTest {

  @Test
  void shouldReturnThePathAsIsWhenAlreadyNormalized() {
    assertThat(resolve("/api/users", "")).isEqualTo("/api/users");
  }

  @Test
  void shouldStripMatrixParameters() {
    // /api/login;jsessionid=1 reaches the same handler as /api/login and must match its pattern.
    assertThat(resolve("/api/login;jsessionid=ABC123", "")).isEqualTo("/api/login");
  }

  @Test
  void shouldDecodeEncodedSlashes() {
    assertThat(resolve("/api%2Flogin", "")).isEqualTo("/api/login");
  }

  @Test
  void shouldCollapseRepeatedSlashes() {
    assertThat(resolve("//api//login", "")).isEqualTo("/api/login");
  }

  @Test
  void shouldDropATrailingSlashButKeepTheRoot() {
    assertThat(resolve("/api/login/", "")).isEqualTo("/api/login");
    assertThat(resolve("/", "")).isEqualTo("/");
  }

  @Test
  void shouldRemoveTheContextPath() {
    assertThat(resolve("/app/api/users", "/app")).isEqualTo("/api/users");
  }

  @Test
  void shouldFallBackToTheRootWhenThereIsNoUri() {
    assertThat(RequestPathResolver.resolve(null)).isEqualTo("/");
  }

  @Test
  void shouldNormalizeStandaloneValues() {
    assertThat(RequestPathResolver.normalize(null)).isEqualTo("/");
    assertThat(RequestPathResolver.normalize("")).isEqualTo("/");
    assertThat(RequestPathResolver.normalize("api/users")).isEqualTo("/api/users");
  }

  @Test
  void shouldRemoveEncodedParentSegments() {
    // N-1: the container decodes and normalizes before routing, so /api/%2e%2e/login reaches the
    // /login handler. Seeing /api/../login here missed the include pattern /login and matched the
    // exclude pattern /actuator/**, so the rate limiter was bypassed either way.
    assertThat(resolve("/api/%2e%2e/login", "")).isEqualTo("/login");
    assertThat(resolve("/api/..%2flogin", "")).isEqualTo("/login");
    assertThat(resolve("/actuator/%2e%2e/login", "")).isEqualTo("/login");
  }

  @Test
  void shouldRemoveCurrentDirectorySegments() {
    assertThat(resolve("/api/./login", "")).isEqualTo("/api/login");
    assertThat(resolve("/api/%2e/login", "")).isEqualTo("/api/login");
  }

  @Test
  void shouldRemoveParentSegmentsAfterTheContextPathIsStripped() {
    assertThat(resolve("/app/api/%2e%2e/login", "/app")).isEqualTo("/login");
  }

  @Test
  void shouldFoldPathsEscapingTheRootToTheRoot() {
    // Such a path has no normal form, so fail closed: the root is rate limited like any other path.
    assertThat(RequestPathResolver.normalize("/../login")).isEqualTo("/");
    assertThat(RequestPathResolver.normalize("/../../etc/passwd")).isEqualTo("/");
  }

  @Test
  void shouldReportAnUndecodablePathAsUnresolvable() {
    // N-2: falling back to the raw URI prepended the context path, missed every include pattern
    // and switched rate limiting off silently.
    assertThat(resolve("/login%zz", "")).isEqualTo(RequestPathResolver.UNRESOLVABLE_PATH);
  }

  @Test
  void shouldWarnOnceAboutUndecodablePathsAndSuppressRepeatsWithinTheInterval() {
    Logger logger = (Logger) LoggerFactory.getLogger(RequestPathResolver.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      RequestPathResolver.resetUnresolvablePathWarnings();

      for (int i = 0; i < 3; i++) {
        assertThat(resolve("/login%zz", "")).isEqualTo(RequestPathResolver.UNRESOLVABLE_PATH);
      }

      assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.WARN))
          .as("the first undecodable path warns, the repeats within the interval do not")
          .hasSize(1);
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void shouldKeepOrdinaryPathsUntouched() {
    assertThat(resolve("/api/login.json", "")).isEqualTo("/api/login.json");
    assertThat(resolve("/api/v1.2/users", "")).isEqualTo("/api/v1.2/users");
    assertThat(resolve("/api/.well-known/jwks", "")).isEqualTo("/api/.well-known/jwks");
  }

  private static String resolve(String uri, String contextPath) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
    request.setRequestURI(uri);
    request.setContextPath(contextPath);
    return RequestPathResolver.resolve(request);
  }
}
