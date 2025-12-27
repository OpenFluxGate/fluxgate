package org.fluxgate.spring.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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
    verify(response).setContentType("application/json");

    printWriter.flush();
    assertThat(stringWriter.toString()).contains("Rate limit exceeded");
    assertThat(stringWriter.toString()).contains("\"retryAfter\":30");
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
    assertThat(capturedContext.getClientIp()).isEqualTo("203.0.113.50");
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
  void shouldExtractUserIdAndApiKeyFromHeaders() throws Exception {
    // Given
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
  void shouldCollectAllHttpHeaders() throws Exception {
    // Given
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    // Mock header enumeration
    java.util.Vector<String> headerNames = new java.util.Vector<>();
    headerNames.add("User-Agent");
    headerNames.add("Accept");
    headerNames.add("X-Custom-Header");
    when(request.getHeaderNames()).thenReturn(headerNames.elements());
    when(request.getHeader("User-Agent")).thenReturn("TestBrowser/1.0");
    when(request.getHeader("Accept")).thenReturn("application/json");
    when(request.getHeader("X-Custom-Header")).thenReturn("custom-value");

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
    verify(handler).tryConsume(contextCaptor.capture(), eq(RULE_SET_ID));

    RequestContext capturedContext = contextCaptor.getValue();
    assertThat(capturedContext.getHeader("User-Agent")).isEqualTo("TestBrowser/1.0");
    assertThat(capturedContext.getHeader("Accept")).isEqualTo("application/json");
    assertThat(capturedContext.getHeader("X-Custom-Header")).isEqualTo("custom-value");
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
  void shouldAddSessionIdToHeaders() throws Exception {
    // Given
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
    assertThat(capturedContext.getHeader("Session-Id")).isEqualTo("session-12345");
  }

  @Test
  void shouldAddContentLengthToHeaders() throws Exception {
    // Given
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
  void shouldRejectWhenTooManyConcurrentWaits() throws Exception {
    // Given - WAIT_FOR_REFILL enabled with max 1 concurrent wait
    filter =
        new FluxgateRateLimitFilter(
            handler,
            RULE_SET_ID,
            new String[] {"/**"},
            new String[] {},
            true, // waitForRefillEnabled = true
            5000, // maxWaitTimeMs = 5 seconds
            0); // maxConcurrentWaits = 0 (no concurrent waits allowed)

    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    when(response.getWriter()).thenReturn(printWriter);

    RateLimitResponse rejectedResult =
        RateLimitResponse.rejected(
            1000, org.fluxgate.core.config.OnLimitExceedPolicy.WAIT_FOR_REFILL);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(rejectedResult);

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then - Should reject immediately due to no semaphore permits
    verify(handler, times(1)).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
    verify(filterChain, never()).doFilter(request, response);
    verify(response).setStatus(429);
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
}
