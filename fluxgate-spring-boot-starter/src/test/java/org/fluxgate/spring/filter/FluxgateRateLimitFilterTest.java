package org.fluxgate.spring.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FluxgateRateLimitFilter}.
 *
 * Tests filter behavior including:
 * - Path matching (include/exclude patterns)
 * - Client IP extraction (X-Forwarded-For support)
 * - Rate limit result handling (allowed vs rejected)
 * - HTTP header injection
 * - 429 response generation
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FluxgateRateLimitFilterTest {

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private RateLimitRuleSetProvider ruleSetProvider;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @Mock
    private RateLimitRuleSet ruleSet;

    @Mock
    private RateLimitRule matchedRule;

    private FluxgateProperties.RateLimitProperties rateLimitProperties;
    private FluxgateRateLimitFilter filter;
    private RateLimitKey testKey;

    @BeforeEach
    void setUp() {
        rateLimitProperties = new FluxgateProperties.RateLimitProperties();
        rateLimitProperties.setDefaultRuleSetId("test-rules");
        // Use /** to match all paths including multi-segment ones
        rateLimitProperties.setIncludePatterns(new String[]{"/**"});
        rateLimitProperties.setExcludePatterns(new String[]{});
        rateLimitProperties.setIncludeHeaders(true);
        rateLimitProperties.setTrustClientIpHeader(true);
        rateLimitProperties.setClientIpHeader("X-Forwarded-For");

        filter = new FluxgateRateLimitFilter(rateLimiter, ruleSetProvider, rateLimitProperties);
        testKey = RateLimitKey.of("192.168.1.100");
    }

    @Test
    void shouldAllowRequestWhenRateLimitNotExceeded() throws Exception {
        // Given
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");
        when(ruleSetProvider.findById("test-rules")).thenReturn(Optional.of(ruleSet));

        RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100).build();
        when(matchedRule.getBands()).thenReturn(List.of(band));

        RateLimitResult allowedResult = RateLimitResult.allowed(testKey, matchedRule, 50, 0);
        when(rateLimiter.tryConsume(any(RequestContext.class), eq(ruleSet), eq(1L)))
                .thenReturn(allowedResult);

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        verify(response).setHeader("X-RateLimit-Limit", "100");
        verify(response).setHeader("X-RateLimit-Remaining", "50");
        verify(response).setHeader(eq("X-RateLimit-Reset"), any());
    }

    @Test
    void shouldRejectRequestWhenRateLimitExceeded() throws Exception {
        // Given
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");
        when(ruleSetProvider.findById("test-rules")).thenReturn(Optional.of(ruleSet));

        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);

        // Set up matchedRule for headers
        RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100).build();
        when(matchedRule.getBands()).thenReturn(List.of(band));

        // Wait for 30 seconds (30 billion nanoseconds)
        long nanosToWait = 30_000_000_000L;
        RateLimitResult rejectedResult = RateLimitResult.rejected(testKey, matchedRule, nanosToWait);
        when(rateLimiter.tryConsume(any(RequestContext.class), eq(ruleSet), eq(1L)))
                .thenReturn(rejectedResult);

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
        rateLimitProperties.setExcludePatterns(new String[]{"/health", "/actuator/**"});
        filter = new FluxgateRateLimitFilter(rateLimiter, ruleSetProvider, rateLimitProperties);

        when(request.getRequestURI()).thenReturn("/health");

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        verify(rateLimiter, never()).tryConsume(any(), any(), anyLong());
    }

    @Test
    void shouldSkipExcludedPathsWithWildcard() throws Exception {
        // Given
        rateLimitProperties.setExcludePatterns(new String[]{"/actuator/**"});
        filter = new FluxgateRateLimitFilter(rateLimiter, ruleSetProvider, rateLimitProperties);

        when(request.getRequestURI()).thenReturn("/actuator/health");

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        verify(rateLimiter, never()).tryConsume(any(), any(), anyLong());
    }

    @Test
    void shouldSkipNonIncludedPaths() throws Exception {
        // Given
        rateLimitProperties.setIncludePatterns(new String[]{"/api/**"});
        filter = new FluxgateRateLimitFilter(rateLimiter, ruleSetProvider, rateLimitProperties);

        when(request.getRequestURI()).thenReturn("/public/index.html");

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        verify(rateLimiter, never()).tryConsume(any(), any(), anyLong());
    }

    @Test
    void shouldExtractClientIpFromXForwardedFor() throws Exception {
        // Given
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 70.41.3.18, 150.172.238.178");
        when(ruleSetProvider.findById("test-rules")).thenReturn(Optional.of(ruleSet));

        RateLimitKey forwardedKey = RateLimitKey.of("203.0.113.50");
        RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100).build();
        when(matchedRule.getBands()).thenReturn(List.of(band));

        RateLimitResult allowedResult = RateLimitResult.allowed(forwardedKey, matchedRule, 50, 0);
        when(rateLimiter.tryConsume(any(RequestContext.class), eq(ruleSet), eq(1L)))
                .thenReturn(allowedResult);

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
        verify(rateLimiter).tryConsume(contextCaptor.capture(), eq(ruleSet), eq(1L));

        RequestContext capturedContext = contextCaptor.getValue();
        assertThat(capturedContext.getClientIp()).isEqualTo("203.0.113.50");
    }

    @Test
    void shouldFallbackToRemoteAddrWhenNoForwardedHeader() throws Exception {
        // Given
        rateLimitProperties.setTrustClientIpHeader(false);
        filter = new FluxgateRateLimitFilter(rateLimiter, ruleSetProvider, rateLimitProperties);

        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(ruleSetProvider.findById("test-rules")).thenReturn(Optional.of(ruleSet));

        RateLimitKey remoteKey = RateLimitKey.of("10.0.0.1");
        RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100).build();
        when(matchedRule.getBands()).thenReturn(List.of(band));

        RateLimitResult allowedResult = RateLimitResult.allowed(remoteKey, matchedRule, 50, 0);
        when(rateLimiter.tryConsume(any(RequestContext.class), eq(ruleSet), eq(1L)))
                .thenReturn(allowedResult);

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
        verify(rateLimiter).tryConsume(contextCaptor.capture(), eq(ruleSet), eq(1L));

        RequestContext capturedContext = contextCaptor.getValue();
        assertThat(capturedContext.getClientIp()).isEqualTo("10.0.0.1");
    }

    @Test
    void shouldSkipWhenNoRuleSetId() throws Exception {
        // Given
        rateLimitProperties.setDefaultRuleSetId(null);
        filter = new FluxgateRateLimitFilter(rateLimiter, ruleSetProvider, rateLimitProperties);

        when(request.getRequestURI()).thenReturn("/api/users");

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        verify(rateLimiter, never()).tryConsume(any(), any(), anyLong());
    }

    @Test
    void shouldSkipWhenRuleSetNotFound() throws Exception {
        // Given
        when(request.getRequestURI()).thenReturn("/api/users");
        when(ruleSetProvider.findById("test-rules")).thenReturn(Optional.empty());

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        verify(rateLimiter, never()).tryConsume(any(), any(), anyLong());
    }

    @Test
    void shouldFailOpenOnRateLimiterException() throws Exception {
        // Given
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");
        when(ruleSetProvider.findById("test-rules")).thenReturn(Optional.of(ruleSet));
        when(rateLimiter.tryConsume(any(RequestContext.class), eq(ruleSet), eq(1L)))
                .thenThrow(new RuntimeException("Redis connection failed"));

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then - Should fail open (allow request)
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void shouldNotAddHeadersWhenDisabled() throws Exception {
        // Given
        rateLimitProperties.setIncludeHeaders(false);
        filter = new FluxgateRateLimitFilter(rateLimiter, ruleSetProvider, rateLimitProperties);

        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");
        when(ruleSetProvider.findById("test-rules")).thenReturn(Optional.of(ruleSet));

        RateLimitResult allowedResult = RateLimitResult.allowed(testKey, matchedRule, 50, 0);
        when(rateLimiter.tryConsume(any(RequestContext.class), eq(ruleSet), eq(1L)))
                .thenReturn(allowedResult);

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        verify(response, never()).setHeader(eq("X-RateLimit-Limit"), any());
        verify(response, never()).setHeader(eq("X-RateLimit-Remaining"), any());
        verify(response, never()).setHeader(eq("X-RateLimit-Reset"), any());
    }

    @Test
    void shouldUseCustomIpHeader() throws Exception {
        // Given
        rateLimitProperties.setClientIpHeader("X-Real-IP");
        filter = new FluxgateRateLimitFilter(rateLimiter, ruleSetProvider, rateLimitProperties);

        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-Real-IP")).thenReturn("8.8.8.8");
        when(ruleSetProvider.findById("test-rules")).thenReturn(Optional.of(ruleSet));

        RateLimitKey customKey = RateLimitKey.of("8.8.8.8");
        RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100).build();
        when(matchedRule.getBands()).thenReturn(List.of(band));

        RateLimitResult allowedResult = RateLimitResult.allowed(customKey, matchedRule, 50, 0);
        when(rateLimiter.tryConsume(any(RequestContext.class), eq(ruleSet), eq(1L)))
                .thenReturn(allowedResult);

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
        verify(rateLimiter).tryConsume(contextCaptor.capture(), eq(ruleSet), eq(1L));

        RequestContext capturedContext = contextCaptor.getValue();
        assertThat(capturedContext.getClientIp()).isEqualTo("8.8.8.8");
    }

    @Test
    void shouldExtractUserIdAndApiKeyFromHeaders() throws Exception {
        // Given
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getMethod()).thenReturn("GET");
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");
        when(request.getHeader("X-User-Id")).thenReturn("user-12345");
        when(request.getHeader("X-API-Key")).thenReturn("api-key-abc");
        when(ruleSetProvider.findById("test-rules")).thenReturn(Optional.of(ruleSet));

        RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100).build();
        when(matchedRule.getBands()).thenReturn(List.of(band));

        RateLimitResult allowedResult = RateLimitResult.allowed(testKey, matchedRule, 50, 0);
        when(rateLimiter.tryConsume(any(RequestContext.class), eq(ruleSet), eq(1L)))
                .thenReturn(allowedResult);

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        ArgumentCaptor<RequestContext> contextCaptor = ArgumentCaptor.forClass(RequestContext.class);
        verify(rateLimiter).tryConsume(contextCaptor.capture(), eq(ruleSet), eq(1L));

        RequestContext capturedContext = contextCaptor.getValue();
        assertThat(capturedContext.getUserId()).isEqualTo("user-12345");
        assertThat(capturedContext.getApiKey()).isEqualTo("api-key-abc");
        assertThat(capturedContext.getEndpoint()).isEqualTo("/api/users");
        assertThat(capturedContext.getMethod()).isEqualTo("GET");
    }

    @Test
    void shouldWorkWithNullRuleSetProvider() throws Exception {
        // Given - Create filter with null provider
        filter = new FluxgateRateLimitFilter(rateLimiter, null, rateLimitProperties);

        when(request.getRequestURI()).thenReturn("/api/users");

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then - Should pass through since no rules can be loaded
        verify(filterChain).doFilter(request, response);
        verify(rateLimiter, never()).tryConsume(any(), any(), anyLong());
    }
}
