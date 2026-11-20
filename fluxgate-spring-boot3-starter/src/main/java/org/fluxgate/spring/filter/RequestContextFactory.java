package org.fluxgate.spring.filter;

import static org.fluxgate.core.constants.FluxgateConstants.Headers;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.spring.util.ClientIpExtractor;
import org.fluxgate.spring.util.RequestPathResolver;
import org.fluxgate.spring.util.TrustedProxies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * Builds the {@link RequestContext} handed to the rate limiter.
 *
 * <p>Filter and aspect share this factory so a rule set behaves identically in both modes. Before
 * it existed the aspect populated only the client IP, which silently demoted every {@code
 * PER_USER}, {@code PER_API_KEY} and {@code CUSTOM} scope to per-IP limiting.
 *
 * <p>Where the identity comes from is configurable through {@link IdentitySource}: the
 * authenticated principal, request headers, or the principal with a header fallback. Headers are
 * client input, so principal based resolution is what makes {@code PER_USER} limiting trustworthy.
 * Since 0.4 the default is {@link IdentitySource#PRINCIPAL}: identity headers are only read when
 * the operator opts in, and doing so is logged as a warning when the factory is created.
 *
 * <p>Header collection is opt-in ({@code fluxgate.ratelimit.collect-headers}) and restricted to an
 * allow list, because the context is passed on to metrics recorders that may persist it. Credential
 * carrying headers are never copied, even when explicitly allow listed.
 */
public class RequestContextFactory {

  private static final Logger log = LoggerFactory.getLogger(RequestContextFactory.class);

  /**
   * Headers that are never copied into the context, whatever the allow list says.
   *
   * <p>N-9: every entry is a credential or a credential challenge. The context reaches metrics
   * recorders that persist it, so copying one of these writes a reusable secret to a database.
   */
  private static final Set<String> NEVER_COLLECTED =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  "authorization",
                  "authentication",
                  "www-authenticate",
                  "proxy-authenticate",
                  "proxy-authorization",
                  "cookie",
                  "set-cookie",
                  "x-api-key",
                  "x-auth-token",
                  "x-csrf-token",
                  "x-xsrf-token",
                  "x-amz-security-token")));

  private static final String CONTENT_LENGTH_HEADER = "Content-Length";

  private final String clientIpHeader;
  private final boolean trustClientIpHeader;
  private final TrustedProxies trustedProxies;
  private final boolean collectHeaders;
  private final Set<String> headerAllowlist;
  private final RequestContextCustomizer contextCustomizer;
  private final IdentitySource identitySource;
  private final String userIdHeader;
  private final String apiKeyHeader;

  /**
   * Creates a request context factory that takes the identity from the authenticated principal.
   *
   * <p>Since 0.4 this constructor no longer reads {@code X-User-Id} or {@code X-API-Key}; use the
   * full constructor with {@link IdentitySource#HEADERS} to opt in.
   *
   * @param clientIpHeader forwarding header to inspect when trusted
   * @param trustClientIpHeader whether forwarding headers are trusted
   * @param trustedProxies proxies allowed to set the forwarding header (nullable)
   * @param collectHeaders whether allow-listed request headers are copied into the context
   * @param headerAllowlist header names that may be copied (case-insensitive, nullable)
   * @param contextCustomizer customizer applied last so users can override anything (nullable)
   */
  public RequestContextFactory(
      String clientIpHeader,
      boolean trustClientIpHeader,
      TrustedProxies trustedProxies,
      boolean collectHeaders,
      Collection<String> headerAllowlist,
      RequestContextCustomizer contextCustomizer) {
    this(
        clientIpHeader,
        trustClientIpHeader,
        trustedProxies,
        collectHeaders,
        headerAllowlist,
        contextCustomizer,
        IdentitySource.PRINCIPAL,
        Headers.USER_ID,
        Headers.API_KEY);
  }

  /**
   * Creates a request context factory.
   *
   * @param clientIpHeader forwarding header to inspect when trusted
   * @param trustClientIpHeader whether forwarding headers are trusted
   * @param trustedProxies proxies allowed to set the forwarding header (nullable)
   * @param collectHeaders whether allow-listed request headers are copied into the context
   * @param headerAllowlist header names that may be copied (case-insensitive, nullable)
   * @param contextCustomizer customizer applied last so users can override anything (nullable)
   * @param identitySource where the user id and API key are taken from (nullable, defaults to
   *     {@link IdentitySource#PRINCIPAL})
   * @param userIdHeader header carrying the user id (nullable, defaults to {@code X-User-Id})
   * @param apiKeyHeader header carrying the API key (nullable, defaults to {@code X-API-Key})
   */
  public RequestContextFactory(
      String clientIpHeader,
      boolean trustClientIpHeader,
      TrustedProxies trustedProxies,
      boolean collectHeaders,
      Collection<String> headerAllowlist,
      RequestContextCustomizer contextCustomizer,
      IdentitySource identitySource,
      String userIdHeader,
      String apiKeyHeader) {
    this.clientIpHeader = clientIpHeader;
    this.trustClientIpHeader = trustClientIpHeader;
    this.trustedProxies = trustedProxies != null ? trustedProxies : TrustedProxies.none();
    this.collectHeaders = collectHeaders;
    this.headerAllowlist = toLowerCaseSet(headerAllowlist);
    this.contextCustomizer =
        contextCustomizer != null ? contextCustomizer : RequestContextCustomizer.identity();
    this.identitySource = resolveIdentitySource(identitySource);
    this.userIdHeader = StringUtils.hasText(userIdHeader) ? userIdHeader : Headers.USER_ID;
    this.apiKeyHeader = StringUtils.hasText(apiKeyHeader) ? apiKeyHeader : Headers.API_KEY;
    warnAboutIdentitySource();
  }

  /**
   * C-4: header identity is client input, so enabling it is called out once at startup. Principal
   * identity without Spring Security can never resolve anything, which is called out too.
   */
  private void warnAboutIdentitySource() {
    if (readsIdentityHeaders(identitySource)) {
      log.warn(
          "fluxgate.ratelimit.identity.source={} reads caller identity from request headers ({},"
              + " {}){}. Headers are client input: any caller can pick its PER_USER/PER_API_KEY"
              + " bucket unless a trusted proxy strips or sets these headers.",
          identitySource,
          userIdHeader,
          apiKeyHeader,
          identitySource == IdentitySource.PRINCIPAL_THEN_HEADERS
              ? " when there is no authenticated principal"
              : "");
    } else if (!PrincipalIdentityResolver.isAvailable()) {
      log.warn(
          "fluxgate.ratelimit.identity.source=PRINCIPAL but Spring Security is not on the"
              + " classpath: no user id or API key is ever resolved, so PER_USER/PER_API_KEY"
              + " rules follow fluxgate.ratelimit.missing-key-behavior. Set"
              + " fluxgate.ratelimit.identity.source=HEADERS behind a trusted proxy, or register"
              + " a RequestContextCustomizer, to supply an identity.");
    }
  }

  /**
   * Resolves the identity source to use.
   *
   * <p>C-4 (0.4): unset means {@link IdentitySource#PRINCIPAL}, whatever the classpath. The 0.3
   * default fell back to {@code X-User-Id}/{@code X-API-Key} for unauthenticated requests, which
   * let any anonymous caller choose its bucket by rotating headers. Header identity is now an
   * explicit opt-in.
   *
   * @param configured the configured value, or null when the operator did not set one
   * @return the effective identity source, never null
   */
  public static IdentitySource resolveIdentitySource(IdentitySource configured) {
    return configured != null ? configured : IdentitySource.PRINCIPAL;
  }

  /**
   * Whether an identity source lets request headers decide the caller's identity.
   *
   * @param source the effective identity source
   * @return true for {@link IdentitySource#HEADERS} and {@link
   *     IdentitySource#PRINCIPAL_THEN_HEADERS}
   */
  public static boolean readsIdentityHeaders(IdentitySource source) {
    return source == IdentitySource.HEADERS || source == IdentitySource.PRINCIPAL_THEN_HEADERS;
  }

  /**
   * Whether this factory lets request headers decide the caller's identity.
   *
   * @return true when the effective source is {@code HEADERS} or {@code PRINCIPAL_THEN_HEADERS}
   */
  public boolean usesIdentityHeaders() {
    return readsIdentityHeaders(identitySource);
  }

  /**
   * Returns the header carrying the user id when identity headers are in use.
   *
   * @return the user id header name, never null
   */
  public String getUserIdHeader() {
    return userIdHeader;
  }

  /**
   * Returns the header carrying the API key when identity headers are in use.
   *
   * @return the API key header name, never null
   */
  public String getApiKeyHeader() {
    return apiKeyHeader;
  }

  /**
   * Builds a context from an HTTP request, deriving the endpoint from the normalized request path.
   *
   * @param request the HTTP request
   * @return the request context
   */
  public RequestContext create(HttpServletRequest request) {
    return create(request, RequestPathResolver.resolve(request));
  }

  /**
   * Builds a context from an HTTP request using a pre-resolved endpoint.
   *
   * @param request the HTTP request
   * @param endpoint the normalized endpoint path
   * @return the request context
   */
  public RequestContext create(HttpServletRequest request, String endpoint) {
    RequestContext.Builder builder =
        RequestContext.builder()
            .clientIp(extractClientIp(request))
            .userId(resolveUserId(request))
            .apiKey(resolveApiKey(request))
            .endpoint(endpoint)
            .method(request.getMethod());

    if (collectHeaders) {
      collectHeaders(builder, request);
    }

    return contextCustomizer.customize(builder, request).build();
  }

  /**
   * Builds a minimal context for an invocation that has no HTTP request, such as a scheduled task
   * or a message listener calling a {@code @RateLimit} method.
   *
   * <p>Identity scopes have nothing to resolve here, so the key resolver falls back according to
   * {@code fluxgate.ratelimit.missing-key-behavior}.
   *
   * @param endpoint a stable identifier for the invocation, typically {@code Type.method}
   * @param method a stable identifier for the invocation kind, such as {@code INTERNAL}
   * @return the request context
   */
  public RequestContext createForInvocation(String endpoint, String method) {
    return RequestContext.builder().endpoint(endpoint).method(method).build();
  }

  /**
   * Extracts the client IP using the configured trust settings.
   *
   * @param request the HTTP request
   * @return the client IP address
   */
  public String extractClientIp(HttpServletRequest request) {
    return ClientIpExtractor.extract(request, clientIpHeader, trustClientIpHeader, trustedProxies);
  }

  /**
   * Resolves the user id according to the configured {@link IdentitySource}.
   *
   * <p>{@code PRINCIPAL} deliberately returns null for an unauthenticated request rather than
   * falling back to the header: a fallback would hand the choice of bucket straight back to the
   * caller, which is the bypass the setting exists to close.
   */
  private String resolveUserId(HttpServletRequest request) {
    if (identitySource == IdentitySource.HEADERS) {
      return request.getHeader(userIdHeader);
    }
    String fromPrincipal = PrincipalIdentityResolver.currentUserId();
    if (fromPrincipal != null || identitySource == IdentitySource.PRINCIPAL) {
      return fromPrincipal;
    }
    return request.getHeader(userIdHeader);
  }

  /** Resolves the API key, which only ever comes from a header. */
  private String resolveApiKey(HttpServletRequest request) {
    return identitySource == IdentitySource.PRINCIPAL ? null : request.getHeader(apiKeyHeader);
  }

  private void collectHeaders(RequestContext.Builder builder, HttpServletRequest request) {
    Enumeration<String> headerNames = request.getHeaderNames();
    if (headerNames != null) {
      while (headerNames.hasMoreElements()) {
        String headerName = headerNames.nextElement();
        if (headerName == null) {
          continue;
        }
        String lower = headerName.toLowerCase(Locale.ROOT);
        if (NEVER_COLLECTED.contains(lower) || !headerAllowlist.contains(lower)) {
          continue;
        }
        builder.header(headerName, request.getHeader(headerName));
      }
    }

    // Some containers do not list Content-Length among the header names, so read it from the
    // request itself - but only when it is allow listed like any other header.
    if (headerAllowlist.contains("content-length")) {
      long contentLength = request.getContentLengthLong();
      if (contentLength > 0 && builder.getHeader(CONTENT_LENGTH_HEADER) == null) {
        builder.header(CONTENT_LENGTH_HEADER, String.valueOf(contentLength));
      }
    }

    // N-9: the requested session id is an authentication credential. It used to be added here
    // unconditionally, bypassing both the allow list and the deny list, and metrics recorders
    // persist the context - so it was written to the database in clear text on every request.
  }

  private static Set<String> toLowerCaseSet(Collection<String> values) {
    if (values == null || values.isEmpty()) {
      return Collections.emptySet();
    }
    Set<String> lowered = new HashSet<>(values.size());
    for (String value : values) {
      if (value != null && !value.trim().isEmpty()) {
        lowered.add(value.trim().toLowerCase(Locale.ROOT));
      }
    }
    return Collections.unmodifiableSet(lowered);
  }
}
