package org.fluxgate.spring.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.fluxgate.core.context.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/** Unit tests for {@link RequestContextFactory}. */
class RequestContextFactoryTest {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  // ===== N-9: header collection deny list =====

  @Nested
  class HeaderCollection {

    @Test
    void shouldNeverCollectCredentialCarryingHeaders() {
      // N-9: the context reaches metrics recorders that persist it, so copying one of these writes
      // a reusable secret to a database. Allow listing them explicitly must not change that.
      List<String> credentialHeaders =
          Arrays.asList(
              "Authorization",
              "Authentication",
              "WWW-Authenticate",
              "Proxy-Authenticate",
              "Proxy-Authorization",
              "Cookie",
              "Set-Cookie",
              "X-API-Key",
              "X-Auth-Token",
              "X-CSRF-Token",
              "X-XSRF-Token",
              "X-Amz-Security-Token");

      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
      credentialHeaders.forEach(name -> request.addHeader(name, "secret-" + name));

      RequestContext context = factory(true, credentialHeaders).create(request, "/api/users");

      assertThat(context.getHeaders()).isEmpty();
    }

    @Test
    void shouldCollectAllowListedHeaders() {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
      request.addHeader("X-Tenant-Id", "acme");
      request.addHeader("X-Not-Listed", "nope");

      RequestContext context =
          factory(true, Collections.singletonList("x-tenant-id")).create(request, "/api/users");

      assertThat(context.getHeader("X-Tenant-Id")).isEqualTo("acme");
      assertThat(context.getHeader("X-Not-Listed")).isNull();
    }

    @Test
    void shouldNeverCollectTheRequestedSessionId() {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
      request.setRequestedSessionId("session-12345");

      RequestContext context =
          factory(true, Collections.singletonList("session-id")).create(request, "/api/users");

      assertThat(context.getHeader("Session-Id")).isNull();
      assertThat(context.getHeaders()).isEmpty();
    }

    @Test
    void shouldStillPopulateTheApiKeyFieldFromTheDenyListedHeader() {
      // X-API-Key is never copied into the headers map, but it is what identifies the caller.
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
      request.addHeader("X-API-Key", "key-123");

      RequestContext context =
          factory(true, Collections.singletonList("x-api-key")).create(request, "/api/users");

      assertThat(context.getApiKey()).isEqualTo("key-123");
      assertThat(context.getHeaders()).isEmpty();
    }
  }

  // ===== C-4: identity source =====

  @Nested
  class IdentityResolution {

    @Test
    void shouldReadIdentityFromHeadersInHeadersMode() {
      RequestContext context = createWith(IdentitySource.HEADERS, requestWithHeaderIdentity());

      assertThat(context.getUserId()).isEqualTo("header-user");
      assertThat(context.getApiKey()).isEqualTo("header-key");
    }

    @Test
    void shouldIgnoreIdentityHeadersInPrincipalModeWhenUnauthenticated() {
      // C-4: a header the caller controls must not pick the bucket in this mode. Missing identity
      // is handled by fluxgate.ratelimit.missing-key-behavior instead.
      RequestContext context = createWith(IdentitySource.PRINCIPAL, requestWithHeaderIdentity());

      assertThat(context.getUserId()).isNull();
      assertThat(context.getApiKey()).isNull();
    }

    @Test
    void shouldTakeTheUserIdFromThePrincipalInPrincipalMode() {
      authenticate("alice");

      RequestContext context = createWith(IdentitySource.PRINCIPAL, requestWithHeaderIdentity());

      assertThat(context.getUserId()).isEqualTo("alice");
      assertThat(context.getApiKey()).isNull();
    }

    @Test
    void shouldPreferThePrincipalOverTheHeader() {
      authenticate("alice");

      RequestContext context =
          createWith(IdentitySource.PRINCIPAL_THEN_HEADERS, requestWithHeaderIdentity());

      assertThat(context.getUserId()).isEqualTo("alice");
      assertThat(context.getApiKey()).isEqualTo("header-key");
    }

    @Test
    void shouldFallBackToTheHeaderWhenThereIsNoAuthentication() {
      RequestContext context =
          createWith(IdentitySource.PRINCIPAL_THEN_HEADERS, requestWithHeaderIdentity());

      assertThat(context.getUserId()).isEqualTo("header-user");
    }

    @Test
    void shouldTreatAnAnonymousAuthenticationAsUnauthenticated() {
      SecurityContextHolder.getContext()
          .setAuthentication(
              new AnonymousAuthenticationToken(
                  "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

      RequestContext context = createWith(IdentitySource.PRINCIPAL, requestWithHeaderIdentity());

      assertThat(context.getUserId()).isNull();
    }

    @Test
    void shouldTreatAnUnauthenticatedTokenAsUnauthenticated() {
      TestingAuthenticationToken token = new TestingAuthenticationToken("alice", "credentials");
      token.setAuthenticated(false);
      SecurityContextHolder.getContext().setAuthentication(token);

      RequestContext context = createWith(IdentitySource.PRINCIPAL, requestWithHeaderIdentity());

      assertThat(context.getUserId()).isNull();
    }

    @Test
    void shouldHonourCustomIdentityHeaderNames() {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
      request.addHeader("X-Tenant-User", "bob");
      request.addHeader("X-Tenant-Key", "tenant-key");

      RequestContextFactory factory =
          new RequestContextFactory(
              "X-Forwarded-For",
              false,
              null,
              false,
              null,
              null,
              IdentitySource.HEADERS,
              "X-Tenant-User",
              "X-Tenant-Key");
      RequestContext context = factory.create(request, "/api/users");

      assertThat(context.getUserId()).isEqualTo("bob");
      assertThat(context.getApiKey()).isEqualTo("tenant-key");
    }

    @Test
    void shouldDefaultToThePrincipalOnly() {
      // C-4 (0.4): header identity is opt-in. An unset source means PRINCIPAL whatever the
      // classpath, so an unauthenticated caller can no longer pick a bucket with X-User-Id.
      assertThat(RequestContextFactory.resolveIdentitySource(null))
          .isEqualTo(IdentitySource.PRINCIPAL);
    }

    @Test
    void shouldIgnoreIdentityHeadersWhenNoSourceIsGiven() {
      RequestContextFactory factory =
          new RequestContextFactory(
              "X-Forwarded-For", false, null, false, null, null, null, null, null);

      RequestContext context = factory.create(requestWithHeaderIdentity(), "/api/users");

      assertThat(context.getUserId()).isNull();
      assertThat(context.getApiKey()).isNull();
    }

    @Test
    void shouldIgnoreIdentityHeadersWithTheLegacyConstructor() {
      RequestContextFactory factory =
          new RequestContextFactory("X-Forwarded-For", false, null, false, null, null);

      RequestContext context = factory.create(requestWithHeaderIdentity(), "/api/users");

      assertThat(context.getUserId()).isNull();
      assertThat(context.getApiKey()).isNull();
    }

    @Test
    void shouldStillTakeThePrincipalByDefault() {
      authenticate("alice");
      RequestContextFactory factory =
          new RequestContextFactory(
              "X-Forwarded-For", false, null, false, null, null, null, null, null);

      RequestContext context = factory.create(requestWithHeaderIdentity(), "/api/users");

      assertThat(context.getUserId()).isEqualTo("alice");
      assertThat(context.getApiKey()).isNull();
    }

    @Test
    void shouldReportWhichSourcesReadIdentityHeaders() {
      assertThat(RequestContextFactory.readsIdentityHeaders(IdentitySource.HEADERS)).isTrue();
      assertThat(RequestContextFactory.readsIdentityHeaders(IdentitySource.PRINCIPAL_THEN_HEADERS))
          .isTrue();
      assertThat(RequestContextFactory.readsIdentityHeaders(IdentitySource.PRINCIPAL)).isFalse();
    }

    @Test
    void shouldKeepAnExplicitlyConfiguredIdentitySource() {
      assertThat(RequestContextFactory.resolveIdentitySource(IdentitySource.HEADERS))
          .isEqualTo(IdentitySource.HEADERS);
    }

    @Test
    void shouldDefaultToHeaderNamesWhenNoneAreConfigured() {
      RequestContextFactory factory =
          new RequestContextFactory(
              "X-Forwarded-For",
              false,
              null,
              false,
              null,
              null,
              IdentitySource.HEADERS,
              "  ",
              null);

      RequestContext context = factory.create(requestWithHeaderIdentity(), "/api/users");

      assertThat(context.getUserId()).isEqualTo("header-user");
      assertThat(context.getApiKey()).isEqualTo("header-key");
    }
  }

  private static void authenticate(String name) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken(name, "credentials", AuthorityUtils.NO_AUTHORITIES));
  }

  private static MockHttpServletRequest requestWithHeaderIdentity() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
    request.addHeader("X-User-Id", "header-user");
    request.addHeader("X-API-Key", "header-key");
    return request;
  }

  private static RequestContext createWith(
      IdentitySource identitySource, MockHttpServletRequest request) {
    RequestContextFactory factory =
        new RequestContextFactory(
            "X-Forwarded-For",
            false,
            null,
            false,
            null,
            null,
            identitySource,
            "X-User-Id",
            "X-API-Key");
    return factory.create(request, "/api/users");
  }

  private static RequestContextFactory factory(boolean collectHeaders, List<String> allowlist) {
    return new RequestContextFactory(
        "X-Forwarded-For",
        false,
        null,
        collectHeaders,
        allowlist,
        null,
        IdentitySource.HEADERS,
        null,
        null);
  }
}
