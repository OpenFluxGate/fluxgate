package org.fluxgate.spring.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
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
  void shouldSkipWhenNoRuleSetId() throws Exception {
    // Given
    filter =
        new FluxgateRateLimitFilter(
            handler,
            null, // No rule set ID
            new String[] {"/**"},
            new String[] {});

    when(request.getRequestURI()).thenReturn("/api/users");

    // When
    filter.doFilterInternal(request, response, filterChain);

    // Then
    verify(filterChain).doFilter(request, response);
    verify(handler, never()).tryConsume(any(), any());
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
}
