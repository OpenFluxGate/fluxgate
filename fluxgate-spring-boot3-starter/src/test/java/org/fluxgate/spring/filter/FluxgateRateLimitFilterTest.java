package org.fluxgate.spring.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.fluxgate.core.constants.FluxgateConstants;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.spring.util.TrustedProxies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.MDC;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link FluxgateRateLimitFilter}.
 *
 * <p>Tests filter behavior including: - Path matching (include/exclude patterns) - Client IP
 * extraction (X-Forwarded-For support) - Rate limit result handling (allowed vs rejected) - HTTP
 * header injection - 429 response generation
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FluxgateRateLimitFilterTest {

  @Mock private FluxgateRateLimitHandler handler;

  @Mock private HttpServletRequest request;

  @Mock private HttpServletResponse response;

  @Mock private FilterChain filterChain;

  private FluxgateRateLimitFilter filter;
  private static final String RULE_SET_ID = "test-rules";

  @BeforeEach
  void setUp() {
    filter =
        new FluxgateRateLimitFilter(handler, RULE_SET_ID, new String[] {"/**"}, new String[] {});
  }

  @Test
  void shouldAllowRequestWhenRateLimitNotExceeded() throws Exception {
    // Given
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain).doFilter(request, response);
    verify(response).setHeader("X-RateLimit-Remaining", "50");
  }

  @Test
  void shouldRejectRequestWhenRateLimitExceeded() throws Exception {
    // Given
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    when(response.getWriter()).thenReturn(printWriter);

    // Wait for 30 seconds (30000 milliseconds)
    long millisToWait = 30_000L;
    RateLimitResponse rejectedResult = RateLimitResponse.rejected(millisToWait);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(rejectedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain, never()).doFilter(request, response);
    verify(response).setStatus(429);
    verify(response).setHeader("Retry-After", "30");
    verify(response).setContentType("application/problem+json;charset=UTF-8");

    printWriter.flush();
    assertThat(stringWriter.toString()).contains("\"title\":\"Too Many Requests\"");
    assertThat(stringWriter.toString()).contains("\"status\":429");
    assertThat(stringWriter.toString()).contains("retry after 30 seconds");
  }

  @Test
  void shouldSkipExcludedPaths() throws Exception {
    // Given
    filter =
        new FluxgateRateLimitFilter(
            handler, RULE_SET_ID, new String[] {"/**"}, new String[] {"/health", "/actuator/**"});

    when(request.getRequestURI()).thenReturn("/health");

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain).doFilter(request, response);
    verify(handler, never()).tryConsume(any(), any());
  }

  @Test
  void shouldSkipExcludedPathsWithWildcard() throws Exception {
    // Given
    filter =
        new FluxgateRateLimitFilter(
            handler, RULE_SET_ID, new String[] {"/**"}, new String[] {"/actuator/**"});

    when(request.getRequestURI()).thenReturn("/actuator/health");

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain).doFilter(request, response);
    verify(handler, never()).tryConsume(any(), any());
  }

  @Test
  void shouldSkipNonIncludedPaths() throws Exception {
    // Given
    filter =
        new FluxgateRateLimitFilter(
            handler, RULE_SET_ID, new String[] {"/api/**"}, new String[] {});

    when(request.getRequestURI()).thenReturn("/public/index.html");

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain).doFilter(request, response);
    verify(handler, never()).tryConsume(any(), any());
  }

  @Test
  void shouldExtractClientIpFromXForwardedFor() throws Exception {
    // Given
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getHeader("X-Forwarded-For"))
        .thenReturn("203.0.113.50, 70.41.3.18, 150.172.238.178");

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(contextCaptor.capture(), eq(RULE_SET_ID));

    RequestContext capturedContext = contextCaptor.getValue();
    // right-most hop is used when trust-client-ip-header=true with no trusted-proxies configured
    assertThat(capturedContext.getClientIp()).isEqualTo("150.172.238.178");
  }

  @Test
  void shouldIgnoreSpoofedForwardedIpWhenHeaderTrustDisabled() throws Exception {
    // Given
    filter =
        new FluxgateRateLimitFilter(
            handler,
            RULE_SET_ID,
            new String[] {"/**"},
            new String[] {},
            false,
            5000,
            100,
            null,
            "X-Forwarded-For",
            false,
            false,
            true);

    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50");
    when(request.getRemoteAddr()).thenReturn("10.0.0.10");

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(contextCaptor.capture(), eq(RULE_SET_ID));

    RequestContext capturedContext = contextCaptor.getValue();
    assertThat(capturedContext.getClientIp()).isEqualTo("10.0.0.10");
  }

  @Test
  void shouldFallbackToRemoteAddrWhenNoForwardedHeader() throws Exception {
    // Given
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("10.0.0.1");

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(contextCaptor.capture(), eq(RULE_SET_ID));

    RequestContext capturedContext = contextCaptor.getValue();
    assertThat(capturedContext.getClientIp()).isEqualTo("10.0.0.1");
  }

  @Test
  void shouldThrowExceptionWhenRuleSetIdIsNull() {
    // Given / When / Then
    assertThatThrownBy(
            () ->
                new FluxgateRateLimitFilter(
                    handler,
                    null, // null ruleSetId should throw
                    new String[] {"/**"},
                    new String[] {}))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("ruleSetId must not be null");
  }

  @Test
  void shouldDenyRequestWhenRuleSetIdMissingAndConfigured() throws Exception {
    // Given
    filter =
        new FluxgateRateLimitFilter(
            handler,
            "",
            new String[] {"/**"},
            new String[] {},
            false,
            5000,
            100,
            null,
            "X-Forwarded-For",
            true,
            true,
            true);

    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");

    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    when(response.getWriter()).thenReturn(printWriter);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain, never()).doFilter(request, response);
    verify(handler, never()).tryConsume(any(), any());
    verify(response).setStatus(429);
  }

  @Test
  void shouldFailOpenOnHandlerException() throws Exception {
    // Given
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenThrow(new RuntimeException("Redis connection failed"));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then - Should fail open (allow request)
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void shouldFailClosedOnHandlerExceptionWhenConfigured() throws Exception {
    // Given
    filter =
        new FluxgateRateLimitFilter(
            handler,
            RULE_SET_ID,
            new String[] {"/**"},
            new String[] {},
            false,
            5000,
            100,
            null,
            "X-Forwarded-For",
            true,
            false);

    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenThrow(new RuntimeException("Redis connection failed"));

    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    when(response.getWriter()).thenReturn(printWriter);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain, never()).doFilter(request, response);
    verify(response).setStatus(429);
  }

  @Test
  void shouldIgnoreIdentityHeadersByDefault() throws Exception {
    // C-4 (0.4): without an authenticated principal, X-User-Id/X-API-Key are client input and are
    // ignored unless header identity is explicitly enabled.
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    lenient().when(request.getHeader("X-User-Id")).thenReturn("user-12345");
    lenient().when(request.getHeader("X-API-Key")).thenReturn("api-key-abc");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    filter.doFilterInternal(request, response, filterChain);

    ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(contextCaptor.capture(), eq(RULE_SET_ID));
    assertThat(contextCaptor.getValue().getUserId()).isNull();
    assertThat(contextCaptor.getValue().getApiKey()).isNull();
  }

  @Test
  void shouldExtractUserIdAndApiKeyFromHeadersWhenOptedIn() throws Exception {
    // Given
    filter = filterBuilder().identitySource(IdentitySource.HEADERS).build();
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(request.getHeader("X-User-Id")).thenReturn("user-12345");
    when(request.getHeader("X-API-Key")).thenReturn("api-key-abc");

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(contextCaptor.capture(), eq(RULE_SET_ID));

    RequestContext capturedContext = contextCaptor.getValue();
    assertThat(capturedContext.getUserId()).isEqualTo("user-12345");
    assertThat(capturedContext.getApiKey()).isEqualTo("api-key-abc");
    assertThat(capturedContext.getEndpoint()).isEqualTo("/api/users");
    assertThat(capturedContext.getMethod()).isEqualTo("GET");
  }

  @Test
  void shouldUseAllowAllHandlerWhenNullHandler() throws Exception {
    // Given - Create filter with ALLOW_ALL handler
    filter =
        new FluxgateRateLimitFilter(
            FluxgateRateLimitHandler.ALLOW_ALL, RULE_SET_ID, new String[] {"/**"}, new String[] {});

    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then - Should pass through
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void shouldCollectOnlyAllowListedHttpHeaders() throws Exception {
    // Given
    filter = filterWithHeaderCollection("User-Agent", "X-Custom-Header", "Authorization");

    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    // Mock header enumeration
    java.util.Vector<String> headerNames = new java.util.Vector<>();
    headerNames.add("User-Agent");
    headerNames.add("Accept");
    headerNames.add("X-Custom-Header");
    headerNames.add("Authorization");
    when(request.getHeaderNames()).thenReturn(headerNames.elements());
    when(request.getHeader("User-Agent")).thenReturn("TestBrowser/1.0");
    when(request.getHeader("Accept")).thenReturn("application/json");
    when(request.getHeader("X-Custom-Header")).thenReturn("custom-value");
    when(request.getHeader("Authorization")).thenReturn("Bearer secret-token");

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(contextCaptor.capture(), eq(RULE_SET_ID));

    RequestContext capturedContext = contextCaptor.getValue();
    assertThat(capturedContext.getHeader("User-Agent")).isEqualTo("TestBrowser/1.0");
    assertThat(capturedContext.getHeader("X-Custom-Header")).isEqualTo("custom-value");
    // Not allow listed
    assertThat(capturedContext.getHeader("Accept")).isNull();
    // Allow listed but a credential: never copied (H-24)
    assertThat(capturedContext.getHeader("Authorization")).isNull();
  }

  @Test
  void shouldNotCollectHeadersByDefault() throws Exception {
    // Given
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    java.util.Vector<String> headerNames = new java.util.Vector<>();
    headerNames.add("X-Custom-Header");
    when(request.getHeaderNames()).thenReturn(headerNames.elements());
    when(request.getHeader("X-Custom-Header")).thenReturn("custom-value");

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(contextCaptor.capture(), eq(RULE_SET_ID));
    assertThat(contextCaptor.getValue().getHeaders()).isEmpty();
  }

  @Test
  void shouldApplyRequestContextCustomizer() throws Exception {
    // Given
    RequestContextCustomizer customizer =
        (builder, req) -> {
          builder.clientIp("overridden-ip");
          builder.attribute("customKey", "customValue");
          return builder;
        };

    filter =
        new FluxgateRateLimitFilter(
            handler,
            RULE_SET_ID,
            new String[] {"/**"},
            new String[] {},
            false,
            5000,
            100,
            customizer);

    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(contextCaptor.capture(), eq(RULE_SET_ID));

    RequestContext capturedContext = contextCaptor.getValue();
    assertThat(capturedContext.getClientIp()).isEqualTo("overridden-ip");
    assertThat(capturedContext.getAttribute("customKey")).isEqualTo("customValue");
  }

  @Test
  void shouldRejectWhenWaitForRefillDisabled() throws Exception {
    // Given - WAIT_FOR_REFILL disabled (default)
    filter =
        new FluxgateRateLimitFilter(
            handler,
            RULE_SET_ID,
            new String[] {"/**"},
            new String[] {},
            false, // waitForRefillEnabled = false
            5000,
            100);

    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    when(response.getWriter()).thenReturn(printWriter);

    // Response indicates WAIT_FOR_REFILL policy but filter has it disabled
    RateLimitResponse rejectedResult =
        RateLimitResponse.rejected(
            1000, org.fluxgate.core.config.OnLimitExceedPolicy.WAIT_FOR_REFILL);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(rejectedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then - Should reject immediately without waiting
    verify(filterChain, never()).doFilter(request, response);
    verify(response).setStatus(429);
  }

  @Test
  void shouldRejectWhenWaitTimeExceedsMax() throws Exception {
    // Given - WAIT_FOR_REFILL enabled with 5 second max wait
    filter =
        new FluxgateRateLimitFilter(
            handler,
            RULE_SET_ID,
            new String[] {"/**"},
            new String[] {},
            true, // waitForRefillEnabled = true
            5000, // maxWaitTimeMs = 5 seconds
            100);

    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    when(response.getWriter()).thenReturn(printWriter);

    // Wait time is 10 seconds, exceeds max of 5 seconds
    RateLimitResponse rejectedResult =
        RateLimitResponse.rejected(
            10000, org.fluxgate.core.config.OnLimitExceedPolicy.WAIT_FOR_REFILL);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(rejectedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then - Should reject immediately because wait time exceeds max
    verify(filterChain, never()).doFilter(request, response);
    verify(response).setStatus(429);
  }

  @Test
  void shouldNeverCollectTheRequestedSessionId() throws Exception {
    // Given - N-9: the session id is an authentication credential and it used to be added to the
    // context unconditionally, bypassing both the allow list and the deny list. Metrics recorders
    // persist the context, so it was written to the database in clear text on every request.
    filter = filterWithHeaderCollection("Session-Id");
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(request.getRequestedSessionId()).thenReturn("session-12345");

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(contextCaptor.capture(), eq(RULE_SET_ID));

    RequestContext capturedContext = contextCaptor.getValue();
    assertThat(capturedContext.getHeader("Session-Id")).isNull();
  }

  @Test
  void shouldAddContentLengthToHeadersOnlyWhenAllowListed() throws Exception {
    // Given - N-9: Content-Length bypassed the allow list too
    filter = filterWithHeaderCollection("Content-Length");
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(request.getContentLengthLong()).thenReturn(1024L);

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(contextCaptor.capture(), eq(RULE_SET_ID));

    RequestContext capturedContext = contextCaptor.getValue();
    assertThat(capturedContext.getHeader("Content-Length")).isEqualTo("1024");
  }

  @Test
  void shouldNotAddContentLengthWhenItIsNotAllowListed() throws Exception {
    // Given
    filter = filterWithHeaderCollection("X-Tenant-Id");
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(request.getContentLengthLong()).thenReturn(1024L);

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(contextCaptor.capture(), eq(RULE_SET_ID));

    assertThat(contextCaptor.getValue().getHeader("Content-Length")).isNull();
  }

  @Test
  void shouldThrowExceptionWhenHandlerIsNull() {
    // Given / When / Then
    assertThatThrownBy(
            () ->
                new FluxgateRateLimitFilter(
                    null, // null handler should throw
                    RULE_SET_ID,
                    new String[] {"/**"},
                    new String[] {}))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("handler must not be null");
  }

  @Test
  void shouldHandleNullIncludePatterns() throws Exception {
    // Given - null includePatterns should default to empty (include all)
    filter = new FluxgateRateLimitFilter(handler, RULE_SET_ID, null, new String[] {});

    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(handler).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void shouldHandleNullExcludePatterns() throws Exception {
    // Given - null excludePatterns should default to empty
    filter = new FluxgateRateLimitFilter(handler, RULE_SET_ID, new String[] {"/**"}, null);

    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(handler).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void shouldWaitForRefillAndSucceed() throws Exception {
    // Given - WAIT_FOR_REFILL enabled
    filter =
        new FluxgateRateLimitFilter(
            handler,
            RULE_SET_ID,
            new String[] {"/**"},
            new String[] {},
            true, // waitForRefillEnabled = true
            5000, // maxWaitTimeMs = 5 seconds
            100);

    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    // First call returns rejected with WAIT_FOR_REFILL
    RateLimitResponse rejectedResult =
        RateLimitResponse.rejected(
            100, // 100ms wait (short for test)
            org.fluxgate.core.config.OnLimitExceedPolicy.WAIT_FOR_REFILL);

    // Second call (after wait) returns allowed
    RateLimitResponse allowedResult = RateLimitResponse.allowed(10, 0);

    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(rejectedResult)
        .thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then - Should wait and then allow
    verify(handler, times(2)).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void shouldRejectAfterWaitingWhenStillRateLimited() throws Exception {
    // Given - WAIT_FOR_REFILL enabled
    filter =
        new FluxgateRateLimitFilter(
            handler,
            RULE_SET_ID,
            new String[] {"/**"},
            new String[] {},
            true, // waitForRefillEnabled = true
            5000, // maxWaitTimeMs = 5 seconds
            100);

    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    when(response.getWriter()).thenReturn(printWriter);

    // Both calls return rejected with WAIT_FOR_REFILL
    RateLimitResponse rejectedResult =
        RateLimitResponse.rejected(
            100, // 100ms wait (short for test)
            org.fluxgate.core.config.OnLimitExceedPolicy.WAIT_FOR_REFILL);

    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(rejectedResult)
        .thenReturn(rejectedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then - Should wait and then reject
    verify(handler, times(2)).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
    verify(filterChain, never()).doFilter(request, response);
    verify(response).setStatus(429);
  }

  @Test
  void shouldRejectTheWaiterBeyondTheSemaphorePermits() throws Exception {
    // Given - one wait permit and a 500ms refill wait: the second concurrent waiter cannot acquire
    // the permit and must be rejected without sleeping or retrying.
    int permits = 1;
    long waitMs = 500;
    filter =
        new FluxgateRateLimitFilter(
            handler, RULE_SET_ID, new String[] {"/**"}, new String[] {}, true, 5000, permits);

    RateLimitResponse rejectedResult =
        RateLimitResponse.rejected(
            waitMs, org.fluxgate.core.config.OnLimitExceedPolicy.WAIT_FOR_REFILL);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(rejectedResult);

    Thread holder = new Thread(() -> runOneRequest(filter));
    holder.start();
    Thread.sleep(100); // let the holder take the only permit and start sleeping

    long startedAt = System.currentTimeMillis();
    runOneRequest(filter);
    long rejectedInMs = System.currentTimeMillis() - startedAt;
    holder.join(10_000);

    // Then - the second waiter returned immediately instead of parking for the refill, and only the
    // permit holder retried: 2 calls for the holder plus 1 for the rejected waiter.
    assertThat(rejectedInMs).isLessThan(waitMs);
    verify(handler, times(waiters(permits))).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
  }

  /** Handler calls expected from {@code permits + 1} waiters: each holder retries once. */
  private static int waiters(int permits) {
    return (permits + 1) + permits;
  }

  /** Runs one request through the filter with its own mocks, so threads do not share state. */
  private static void runOneRequest(FluxgateRateLimitFilter filter) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    try {
      when(req.getRequestURI()).thenReturn("/api/users");
      when(req.getMethod()).thenReturn("POST");
      when(req.getRemoteAddr()).thenReturn("192.168.1.100");
      when(res.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
      filter.doFilterInternal(req, res, chain);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void shouldHandleEmptyIncludePatternsAsIncludeAll() throws Exception {
    // Given - empty includePatterns should include all
    filter = new FluxgateRateLimitFilter(handler, RULE_SET_ID, new String[] {}, new String[] {});

    when(request.getRequestURI()).thenReturn("/any/path");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(handler).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void shouldHandleTraceIdFromHeader() throws Exception {
    // Given
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(request.getHeader("X-Trace-Id")).thenReturn("existing-trace-id-123");

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void shouldHandleBlankTraceId() throws Exception {
    // Given
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(request.getHeader("X-Trace-Id")).thenReturn("   "); // blank trace id

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void shouldHandleNegativeRemainingTokens() throws Exception {
    // Given
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    // Negative remaining tokens should not add header
    RateLimitResponse allowedResult = RateLimitResponse.allowed(-1, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain).doFilter(request, response);
    verify(response, never()).setHeader(eq("X-RateLimit-Remaining"), any());
  }

  @Test
  void shouldHandleZeroContentLength() throws Exception {
    // Given
    filter = filterWithHeaderCollection();
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(request.getContentLengthLong()).thenReturn(0L);

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(contextCaptor.capture(), eq(RULE_SET_ID));

    RequestContext capturedContext = contextCaptor.getValue();
    // Zero content length should not add header
    assertThat(capturedContext.getHeader("Content-Length")).isNull();
  }

  @Test
  void shouldHandleNullSessionId() throws Exception {
    // Given
    filter = filterWithHeaderCollection();
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(request.getRequestedSessionId()).thenReturn(null);

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(contextCaptor.capture(), eq(RULE_SET_ID));

    RequestContext capturedContext = contextCaptor.getValue();
    assertThat(capturedContext.getHeader("Session-Id")).isNull();
  }

  @Test
  void shouldHandleNullHeaderNames() throws Exception {
    // Given
    filter = filterWithHeaderCollection();
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(request.getHeaderNames()).thenReturn(null);

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void shouldHandleOptionalMdcHeaders() throws Exception {
    // Given
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(request.getQueryString()).thenReturn("param=value");
    when(request.getHeader("User-Agent")).thenReturn("TestAgent");
    when(request.getHeader("Referer")).thenReturn("http://example.com");

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain).doFilter(request, response);
  }

  // ===== C2 / C-1: the chain runs exactly once =====

  @Test
  void shouldNotReplayTheChainWhenTheApplicationThrows() throws Exception {
    // Given - the downstream application fails, which used to be caught as a "rate limiter error"
    when(request.getRequestURI()).thenReturn("/api/orders");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    RuntimeException downstream = new RuntimeException("controller blew up");
    doThrow(downstream).when(filterChain).doFilter(request, response);

    // When / Then - the exception propagates unchanged and the chain ran once, not twice
    assertThatThrownBy(() -> filter.doFilterInternal(request, response, filterChain))
        .isSameAs(downstream);
    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void shouldCallTheChainOnceWhenTheLimiterFailsOpen() throws Exception {
    // Given
    when(request.getRequestURI()).thenReturn("/api/orders");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenThrow(new RuntimeException("Redis down"));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void shouldNotCallTheChainWhenTheLimiterFailsClosed() throws Exception {
    // Given
    filter = failClosedFilter();
    when(request.getRequestURI()).thenReturn("/api/orders");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenThrow(new RuntimeException("Redis down"));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain, never()).doFilter(any(), any());
    verify(response).setStatus(429);
  }

  // ===== H-6: path normalization =====

  @Test
  void shouldNotBeBypassedByMatrixParameters() throws Exception {
    // Given - /api/login;jsessionid=1 routes to the /api/login handler, so it must be limited too
    filter = new FluxgateRateLimitFilter(handler, RULE_SET_ID, new String[] {"/api/login"}, null);
    when(request.getRequestURI()).thenReturn("/api/login;jsessionid=ABC123");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(contextCaptor.capture(), eq(RULE_SET_ID));
    assertThat(contextCaptor.getValue().getEndpoint()).isEqualTo("/api/login");
  }

  @Test
  void shouldNotBeBypassedByAnEncodedSlash() throws Exception {
    // Given
    filter = new FluxgateRateLimitFilter(handler, RULE_SET_ID, new String[] {"/api/login"}, null);
    when(request.getRequestURI()).thenReturn("/api%2Flogin");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(handler).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
  }

  @Test
  void shouldNotBeBypassedByATrailingSlashOrDoubleSlash() throws Exception {
    // Given
    filter = new FluxgateRateLimitFilter(handler, RULE_SET_ID, new String[] {"/api/login"}, null);
    when(request.getRequestURI()).thenReturn("//api//login/");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(handler).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
  }

  @Test
  void shouldMatchPatternsIndependentlyOfTheContextPath() throws Exception {
    // Given - the app is deployed under /app, so getRequestURI() carries the context path
    filter = new FluxgateRateLimitFilter(handler, RULE_SET_ID, new String[] {"/api/**"}, null);
    when(request.getRequestURI()).thenReturn("/app/api/users");
    when(request.getContextPath()).thenReturn("/app");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(contextCaptor.capture(), eq(RULE_SET_ID));
    assertThat(contextCaptor.getValue().getEndpoint()).isEqualTo("/api/users");
  }

  @Test
  void shouldMatchCaseInsensitivelyWhenConfigured() throws Exception {
    // Given
    filter =
        filterBuilder()
            .includePatterns(new String[] {"/api/login"})
            .caseSensitivePatterns(false)
            .build();
    when(request.getRequestURI()).thenReturn("/API/Login");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(handler).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
  }

  @Test
  void shouldMatchCaseSensitivelyByDefault() throws Exception {
    // Given
    filter = new FluxgateRateLimitFilter(handler, RULE_SET_ID, new String[] {"/api/login"}, null);
    when(request.getRequestURI()).thenReturn("/API/Login");

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain).doFilter(request, response);
    verify(handler, never()).tryConsume(any(), any());
  }

  // ===== M21 / M22: rate limit headers =====

  @Test
  void shouldWriteLegacyAndStandardHeadersOnAllowedResponses() throws Exception {
    // Given
    long resetAt = System.currentTimeMillis() + 42_000L;
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(7, 0, 100, resetAt));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(response).setHeader("X-RateLimit-Limit", "100");
    verify(response).setHeader("X-RateLimit-Remaining", "7");
    verify(response).setHeader("X-RateLimit-Reset", String.valueOf((resetAt + 999) / 1000));
    verify(response).setHeader("RateLimit-Limit", "100");
    verify(response).setHeader("RateLimit-Remaining", "7");
    verify(response, never()).setHeader(eq("Retry-After"), any());
  }

  @Test
  void shouldNeverAdvertiseRetryAfterZero() throws Exception {
    // Given - a sub-second wait used to truncate to Retry-After: 0 and cause a client busy loop
    filter = failClosedFilter();
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.rejected(1));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(response).setHeader("Retry-After", "1");
  }

  @Test
  void shouldOmitHeadersWhoseValueIsUnknown() throws Exception {
    // Given
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(-1, 0, -1, -1));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(response, never()).setHeader(eq("X-RateLimit-Limit"), any());
    verify(response, never()).setHeader(eq("X-RateLimit-Remaining"), any());
    verify(response, never()).setHeader(eq("X-RateLimit-Reset"), any());
    verify(response, never()).setHeader(eq("RateLimit-Limit"), any());
  }

  // ===== C5: trusted proxies =====

  @Test
  void shouldIgnoreForwardedHeaderFromAnUntrustedRemoteAddress() throws Exception {
    // Given - the request did not come through a configured proxy
    filter = filterBuilder().trustedProxies("10.0.0.0/8").build();
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("203.0.113.9");
    when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(contextCaptor.capture(), eq(RULE_SET_ID));
    assertThat(contextCaptor.getValue().getClientIp()).isEqualTo("203.0.113.9");
  }

  @Test
  void shouldTakeTheRightMostNonProxyHopFromATrustedProxy() throws Exception {
    // Given - a forged left-most value must not win when the chain can be verified
    filter = filterBuilder().trustedProxies("10.0.0.0/8", "192.168.0.0/16").build();
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("10.0.0.5");
    when(request.getHeader("X-Forwarded-For"))
        .thenReturn("9.9.9.9, 203.0.113.50, 192.168.1.7, 10.0.0.5");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(contextCaptor.capture(), eq(RULE_SET_ID));
    assertThat(contextCaptor.getValue().getClientIp()).isEqualTo("203.0.113.50");
  }

  @Test
  void shouldFallBackToRemoteAddrWhenTheForwardedValueIsNotAnIp() throws Exception {
    // Given - an arbitrary string must never end up in a bucket key
    filter = filterBuilder().trustedProxies("10.0.0.0/8").build();
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("10.0.0.5");
    when(request.getHeader("X-Forwarded-For")).thenReturn("not-an-ip");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(contextCaptor.capture(), eq(RULE_SET_ID));
    assertThat(contextCaptor.getValue().getClientIp()).isEqualTo("10.0.0.5");
  }

  // ===== permits / cost header =====

  @Test
  void shouldUseTheSinglePermitPathWhenNoCostHeaderIsConfigured() throws Exception {
    // Given
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(handler).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
    verify(handler, never()).tryConsume(any(RequestContext.class), any(), anyLong());
  }

  @Test
  void shouldPassTheCostHeaderAsPermits() throws Exception {
    // Given
    filter = filterBuilder().costHeader("X-RateLimit-Cost").maxCost(1000).build();
    when(request.getRequestURI()).thenReturn("/api/export");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(request.getHeader("X-RateLimit-Cost")).thenReturn("10");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID), eq(10L)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(handler).tryConsume(any(RequestContext.class), eq(RULE_SET_ID), eq(10L));
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void shouldCapTheCostHeaderAtMaxCost() throws Exception {
    // Given
    filter = filterBuilder().costHeader("X-RateLimit-Cost").maxCost(5).build();
    when(request.getRequestURI()).thenReturn("/api/export");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(request.getHeader("X-RateLimit-Cost")).thenReturn("9999999");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID), eq(5L)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(handler).tryConsume(any(RequestContext.class), eq(RULE_SET_ID), eq(5L));
  }

  @Test
  void shouldFallBackToOnePermitForAnUnparseableOrNonPositiveCost() throws Exception {
    // Given
    filter = filterBuilder().costHeader("X-RateLimit-Cost").build();
    when(request.getRequestURI()).thenReturn("/api/export");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(request.getHeader("X-RateLimit-Cost")).thenReturn("0; drop table");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(handler).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
    verify(handler, never()).tryConsume(any(RequestContext.class), any(), anyLong());
  }

  // ===== N-7: cost header configuration =====

  @Test
  void shouldRefuseToStartWhenMaxCostIsNotPositiveAndACostHeaderIsSet() {
    // N-7: max-cost <= 0 used to mean "unlimited", so a "max-cost: 0" typo let a client empty any
    // bucket with a single request.
    assertThatThrownBy(() -> filterBuilder().costHeader("X-RateLimit-Cost").maxCost(0).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fluxgate.ratelimit.max-cost must be > 0");

    assertThatThrownBy(() -> filterBuilder().costHeader("X-RateLimit-Cost").maxCost(-1).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fluxgate.ratelimit.max-cost must be > 0");
  }

  @Test
  void shouldAllowANonPositiveMaxCostWhenNoCostHeaderIsConfigured() {
    // Without a cost header the value is never read, so it must not fail an ordinary deployment.
    assertThat(filterBuilder().maxCost(0).build()).isNotNull();
  }

  // ===== N-1: dot segment normalization =====

  @Test
  void shouldRateLimitEncodedParentSegmentsThatReachAnIncludedPath() throws Exception {
    // N-1: the container routes /api/%2e%2e/login to the /login handler, so the filter must see
    // /login too. It used to see /api/../login, miss the include pattern and skip the limiter -
    // an unauthenticated rate limit bypass on exactly the endpoints worth protecting.
    for (String uri :
        new String[] {"/api/%2e%2e/login", "/api/..%2flogin", "/actuator/%2e%2e/login"}) {
      reset(handler, filterChain);
      filter = filterBuilder().includePatterns(new String[] {"/login"}).build();
      when(request.getRequestURI()).thenReturn(uri);
      when(request.getMethod()).thenReturn("POST");
      when(request.getRemoteAddr()).thenReturn("192.168.1.100");
      when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
          .thenReturn(RateLimitResponse.allowed(50, 0));

      filter.doFilterInternal(request, response, filterChain);

      assertThat(endpointOf(handler)).as(uri).isEqualTo("/login");
    }
  }

  @Test
  void shouldRateLimitCurrentDirectorySegmentsThatReachAnIncludedPath() throws Exception {
    filter = filterBuilder().includePatterns(new String[] {"/api/login"}).build();
    when(request.getRequestURI()).thenReturn("/api/./login");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    filter.doFilterInternal(request, response, filterChain);

    assertThat(endpointOf(handler)).isEqualTo("/api/login");
  }

  @Test
  void shouldNotLetEncodedParentSegmentsMatchAnExcludePattern() throws Exception {
    // N-1 scenario B, the stronger one: with exclude-patterns: ["/actuator/**"], the filter used to
    // see /actuator/../login, match the exclude pattern and skip the limiter explicitly, while the
    // container routed the request to /login.
    for (String uri : new String[] {"/actuator/%2e%2e/login", "/actuator/..%2flogin"}) {
      reset(handler, filterChain);
      filter =
          filterBuilder()
              .includePatterns(new String[] {"/**"})
              .excludePatterns("/actuator/**")
              .build();
      when(request.getRequestURI()).thenReturn(uri);
      when(request.getMethod()).thenReturn("POST");
      when(request.getRemoteAddr()).thenReturn("192.168.1.100");
      when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
          .thenReturn(RateLimitResponse.allowed(50, 0));

      filter.doFilterInternal(request, response, filterChain);

      assertThat(endpointOf(handler)).as(uri).isEqualTo("/login");
    }
  }

  @Test
  void shouldStillExcludeAGenuineActuatorPath() throws Exception {
    filter = filterBuilder().excludePatterns("/actuator/**").build();
    when(request.getRequestURI()).thenReturn("/actuator/health");

    filter.doFilterInternal(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
    verify(handler, never()).tryConsume(any(), any());
  }

  // ===== N-2: undecodable paths =====

  @Test
  void shouldRateLimitAnUndecodablePathEvenWhenItIsNotIncluded() throws Exception {
    // N-2: the resolver used to fall back to the raw URI, which misses every include pattern, so a
    // malformed escape sequence switched rate limiting off for that request.
    filter = filterBuilder().includePatterns(new String[] {"/api/**"}).build();
    when(request.getRequestURI()).thenReturn("/login%zz");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    filter.doFilterInternal(request, response, filterChain);

    verify(handler).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
  }

  @Test
  void shouldNotLetAnUndecodablePathMatchAnExcludePattern() throws Exception {
    filter = filterBuilder().excludePatterns("/**").build();
    when(request.getRequestURI()).thenReturn("/login%zz");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    filter.doFilterInternal(request, response, filterChain);

    verify(handler).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
  }

  // ===== M28 / M-3: MDC handling =====

  @Test
  void shouldRestoreTheMdcOfAnEarlierFilter() throws Exception {
    // Given - a tracing filter in front of this one put a correlation id into the MDC
    MDC.put("upstreamCorrelationId", "abc-123");
    try {
      when(request.getRequestURI()).thenReturn("/api/users");
      when(request.getMethod()).thenReturn("GET");
      when(request.getRemoteAddr()).thenReturn("192.168.1.100");
      when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
          .thenReturn(RateLimitResponse.allowed(50, 0));

      // When
      filter.doFilterInternal(request, response, filterChain);

      // Then
      assertThat(MDC.get("upstreamCorrelationId")).isEqualTo("abc-123");
      assertThat(MDC.get("traceId")).isNull();
    } finally {
      MDC.clear();
    }
  }

  @Test
  void shouldSanitizeMdcValuesTakenFromHeaders() throws Exception {
    // Given - a header carrying CRLF would otherwise forge log lines
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(request.getHeader("X-Trace-Id")).thenReturn("abc\r\nWARN forged log line");

    java.util.concurrent.atomic.AtomicReference<String> observed =
        new java.util.concurrent.atomic.AtomicReference<>();
    doAnswer(
            invocation -> {
              observed.set(MDC.get("traceId"));
              return null;
            })
        .when(filterChain)
        .doFilter(request, response);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    assertThat(observed.get()).isEqualTo("abc__WARN forged log line");
  }

  @Test
  void shouldKeepTheQueryStringOutOfTheMdcByDefault() throws Exception {
    // Given - query strings routinely carry tokens
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(request.getQueryString()).thenReturn("token=super-secret");

    java.util.concurrent.atomic.AtomicReference<String> observed =
        new java.util.concurrent.atomic.AtomicReference<>();
    doAnswer(
            invocation -> {
              observed.set(MDC.get("queryString"));
              return null;
            })
        .when(filterChain)
        .doFilter(request, response);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    assertThat(observed.get()).isNull();
  }

  @Test
  void shouldPutTheQueryStringIntoTheMdcWhenEnabled() throws Exception {
    // Given
    filter = filterBuilder().logQueryString(true).build();
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(request.getQueryString()).thenReturn("page=2");

    java.util.concurrent.atomic.AtomicReference<String> observed =
        new java.util.concurrent.atomic.AtomicReference<>();
    doAnswer(
            invocation -> {
              observed.set(MDC.get("queryString"));
              return null;
            })
        .when(filterChain)
        .doFilter(request, response);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    assertThat(observed.get()).isEqualTo("page=2");
  }

  // ===== SPI hooks =====

  @Test
  void shouldDelegateTheBodyToACustomResponseWriter() throws Exception {
    // Given
    RateLimitResponseWriter custom =
        (req, res, result) -> {
          res.setStatus(429);
          res.setContentType("application/vnd.acme.error+json");
          res.getWriter().write("{\"code\":\"RATE_LIMITED\"}");
        };
    filter = filterBuilder().responseWriter(custom).build();

    StringWriter body = new StringWriter();
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(response.getWriter()).thenReturn(new PrintWriter(body));
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.rejected(1000));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(response).setContentType("application/vnd.acme.error+json");
    assertThat(body.toString()).isEqualTo("{\"code\":\"RATE_LIMITED\"}");
  }

  @Test
  void shouldRecordTheRequestDuration() throws Exception {
    // Given
    java.util.List<String> recorded = new java.util.ArrayList<>();
    RateLimitDurationRecorder recorder =
        (ruleSetId, endpoint, method, duration) ->
            recorded.add(ruleSetId + " " + endpoint + " " + method);
    filter = filterBuilder().durationRecorder(recorder).build();

    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    assertThat(recorded).containsExactly(RULE_SET_ID + " /api/users GET");
  }

  // ===== test fixtures =====

  private FluxgateRateLimitFilter filterWithHeaderCollection(String... allowlist) {
    return filterBuilder().collectHeaders(true).headerAllowlist(allowlist).build();
  }

  /** Returns the endpoint of the context the handler was last called with. */
  private static String endpointOf(FluxgateRateLimitHandler handler) {
    ArgumentCaptor<RequestContext> captor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(captor.capture(), eq(RULE_SET_ID));
    return captor.getValue().getEndpoint();
  }

  private FluxgateRateLimitFilter failClosedFilter() {
    return filterBuilder().failOpenOnError(false).build();
  }

  // ===== C-4: identity in the MDC follows the identity source =====

  @Test
  void shouldNotLogIdentityHeadersInTheMdcByDefault() throws Exception {
    // A header the caller controls must not appear in logs as the caller's identity when it is
    // not what the limiter used.
    String[] seen = mdcIdentityDuringChain(filter, null);

    assertThat(seen[0]).isNull();
    assertThat(seen[1]).isNull();
  }

  @Test
  void shouldLogThePrincipalAsTheMdcUserIdByDefault() throws Exception {
    String[] seen = mdcIdentityDuringChain(filter, "alice");

    assertThat(seen[0]).isEqualTo("alice");
    assertThat(seen[1]).isNull();
  }

  @Test
  void shouldLogIdentityHeadersInTheMdcWhenOptedIn() throws Exception {
    filter = filterBuilder().identitySource(IdentitySource.HEADERS).build();

    String[] seen = mdcIdentityDuringChain(filter, null);

    assertThat(seen[0]).isEqualTo("user-12345");
    assertThat(seen[1]).isNotNull().doesNotContain("api-key-abcdef");
  }

  /** Runs one allowed request and returns the MDC user id and API key seen by the chain. */
  private String[] mdcIdentityDuringChain(FluxgateRateLimitFilter target, String principal)
      throws Exception {
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    lenient().when(request.getHeader("X-User-Id")).thenReturn("user-12345");
    lenient().when(request.getHeader("X-API-Key")).thenReturn("api-key-abcdef");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));
    String[] seen = new String[2];
    doAnswer(
            invocation -> {
              seen[0] = MDC.get(FluxgateConstants.MdcKeys.USER_ID);
              seen[1] = MDC.get(FluxgateConstants.MdcKeys.API_KEY);
              return null;
            })
        .when(filterChain)
        .doFilter(request, response);
    if (principal != null) {
      SecurityContextHolder.getContext()
          .setAuthentication(
              new TestingAuthenticationToken(
                  principal, "credentials", AuthorityUtils.NO_AUTHORITIES));
    }
    try {
      target.doFilterInternal(request, response, filterChain);
    } finally {
      SecurityContextHolder.clearContext();
    }
    return seen;
  }

  private FilterFixture filterBuilder() {
    return new FilterFixture(handler);
  }

  /** Builds filters against the full constructor without repeating 16 arguments per test. */
  private static final class FilterFixture {

    private final FluxgateRateLimitHandler handler;
    private String[] includePatterns = {"/**"};
    private String[] excludePatterns = {};
    private boolean caseSensitivePatterns = true;
    private boolean failOpenOnError = true;
    private boolean logQueryString;
    private boolean collectHeaders;
    private String[] headerAllowlist = {};
    private String[] trustedProxies = {};
    private String costHeader;
    private long maxCost = 1000;
    private RateLimitResponseWriter responseWriter = new ProblemDetailRateLimitResponseWriter();
    private RateLimitDurationRecorder durationRecorder;
    private IdentitySource identitySource;

    FilterFixture(FluxgateRateLimitHandler handler) {
      this.handler = handler;
    }

    FilterFixture includePatterns(String[] includePatterns) {
      this.includePatterns = includePatterns;
      return this;
    }

    FilterFixture excludePatterns(String... excludePatterns) {
      this.excludePatterns = excludePatterns;
      return this;
    }

    FilterFixture caseSensitivePatterns(boolean caseSensitivePatterns) {
      this.caseSensitivePatterns = caseSensitivePatterns;
      return this;
    }

    FilterFixture failOpenOnError(boolean failOpenOnError) {
      this.failOpenOnError = failOpenOnError;
      return this;
    }

    FilterFixture logQueryString(boolean logQueryString) {
      this.logQueryString = logQueryString;
      return this;
    }

    FilterFixture collectHeaders(boolean collectHeaders) {
      this.collectHeaders = collectHeaders;
      return this;
    }

    FilterFixture headerAllowlist(String... headerAllowlist) {
      this.headerAllowlist = headerAllowlist;
      return this;
    }

    FilterFixture trustedProxies(String... trustedProxies) {
      this.trustedProxies = trustedProxies;
      return this;
    }

    FilterFixture costHeader(String costHeader) {
      this.costHeader = costHeader;
      return this;
    }

    FilterFixture maxCost(long maxCost) {
      this.maxCost = maxCost;
      return this;
    }

    FilterFixture responseWriter(RateLimitResponseWriter responseWriter) {
      this.responseWriter = responseWriter;
      return this;
    }

    FilterFixture durationRecorder(RateLimitDurationRecorder durationRecorder) {
      this.durationRecorder = durationRecorder;
      return this;
    }

    FilterFixture identitySource(IdentitySource identitySource) {
      this.identitySource = identitySource;
      return this;
    }

    FluxgateRateLimitFilter build() {
      return new FluxgateRateLimitFilter(
          handler,
          RULE_SET_ID,
          includePatterns,
          excludePatterns,
          false,
          5000,
          50,
          failOpenOnError,
          false,
          caseSensitivePatterns,
          logQueryString,
          costHeader,
          maxCost,
          new RequestContextFactory(
              "X-Forwarded-For",
              true,
              TrustedProxies.of(trustedProxies),
              collectHeaders,
              java.util.Arrays.asList(headerAllowlist),
              null,
              identitySource,
              null,
              null),
          new RateLimitHeaderWriter(true, true),
          responseWriter,
          durationRecorder);
    }
  }
}
