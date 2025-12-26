package org.fluxgate.spring.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.spring.annotation.RateLimit;
import org.fluxgate.spring.filter.RequestContextCustomizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Unit tests for {@link RateLimitAspect}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitAspectTest {

  @Mock private FluxgateRateLimitHandler handler;

  @Mock private ProceedingJoinPoint joinPoint;

  @Mock private RateLimit rateLimit;

  @Mock private HttpServletRequest request;

  @Mock private HttpServletResponse response;

  private RateLimitAspect aspect;

  private static final String RULE_SET_ID = "test-rules";

  @BeforeEach
  void setUp() {
    aspect = new RateLimitAspect(handler, null);

    // Set up RequestContextHolder with real ServletRequestAttributes
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void shouldAllowRequestWhenRateLimitNotExceeded() throws Throwable {
    // Given
    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(rateLimit.waitForRefill()).thenReturn(false);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    Object expectedResult = "success";
    when(joinPoint.proceed()).thenReturn(expectedResult);

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isEqualTo(expectedResult);
    verify(joinPoint).proceed();
    verify(response).setHeader("X-RateLimit-Remaining", "50");
  }

  @Test
  void shouldRejectRequestWhenRateLimitExceeded() throws Throwable {
    // Given
    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(rateLimit.waitForRefill()).thenReturn(false);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    when(response.getWriter()).thenReturn(printWriter);

    RateLimitResponse rejectedResult = RateLimitResponse.rejected(30000);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(rejectedResult);

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isNull();
    verify(joinPoint, never()).proceed();
    verify(response).setStatus(429);
    verify(response).setHeader("Retry-After", "30");
    verify(response).setContentType("application/json");
  }

  @Test
  void shouldSkipRateLimitingWhenNoHttpContext() throws Throwable {
    // Given - Clear request context
    RequestContextHolder.resetRequestAttributes();

    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    Object expectedResult = "success";
    when(joinPoint.proceed()).thenReturn(expectedResult);

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isEqualTo(expectedResult);
    verify(joinPoint).proceed();
    verify(handler, never()).tryConsume(any(), any());
  }

  @Test
  void shouldSkipRateLimitingWhenNoRuleSetId() throws Throwable {
    // Given
    when(rateLimit.ruleSetId()).thenReturn("");
    Object expectedResult = "success";
    when(joinPoint.proceed()).thenReturn(expectedResult);

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isEqualTo(expectedResult);
    verify(joinPoint).proceed();
    verify(handler, never()).tryConsume(any(), any());
  }

  @Test
  void shouldSkipRateLimitingWhenNullRuleSetId() throws Throwable {
    // Given
    when(rateLimit.ruleSetId()).thenReturn(null);
    Object expectedResult = "success";
    when(joinPoint.proceed()).thenReturn(expectedResult);

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isEqualTo(expectedResult);
    verify(joinPoint).proceed();
    verify(handler, never()).tryConsume(any(), any());
  }

  @Test
  void shouldFailOpenOnHandlerException() throws Throwable {
    // Given
    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    Object expectedResult = "success";
    when(joinPoint.proceed()).thenReturn(expectedResult);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenThrow(new RuntimeException("Connection failed"));

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then - Should fail open
    assertThat(result).isEqualTo(expectedResult);
    verify(joinPoint).proceed();
  }

  @Test
  void shouldUseClassLevelAnnotation() throws Throwable {
    // Given
    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(rateLimit.waitForRefill()).thenReturn(false);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    Object expectedResult = "success";
    when(joinPoint.proceed()).thenReturn(expectedResult);

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    Object result = aspect.aroundClass(joinPoint, rateLimit);

    // Then
    assertThat(result).isEqualTo(expectedResult);
    verify(joinPoint).proceed();
  }

  @Test
  void shouldApplyRequestContextCustomizer() throws Throwable {
    // Given
    RequestContextCustomizer customizer =
        (builder, req) -> {
          builder.clientIp("overridden-ip");
          return builder;
        };

    aspect = new RateLimitAspect(handler, customizer);

    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(rateLimit.waitForRefill()).thenReturn(false);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    Object expectedResult = "success";
    when(joinPoint.proceed()).thenReturn(expectedResult);

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isEqualTo(expectedResult);
    verify(handler)
        .tryConsume(argThat(ctx -> "overridden-ip".equals(ctx.getClientIp())), eq(RULE_SET_ID));
  }

  @Test
  void shouldWaitForRefillAndSucceed() throws Throwable {
    // Given
    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(rateLimit.waitForRefill()).thenReturn(true);
    when(rateLimit.maxWaitTimeMs()).thenReturn(5000L);
    when(rateLimit.maxConcurrentWaits()).thenReturn(100);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    Object expectedResult = "success";
    when(joinPoint.proceed()).thenReturn(expectedResult);

    // First call rejected, second call allowed
    RateLimitResponse rejectedResult = RateLimitResponse.rejected(100);
    RateLimitResponse allowedResult = RateLimitResponse.allowed(10, 0);

    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(rejectedResult)
        .thenReturn(allowedResult);

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isEqualTo(expectedResult);
    verify(handler, times(2)).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
    verify(joinPoint).proceed();
  }

  @Test
  void shouldRejectWhenWaitTimeExceedsMax() throws Throwable {
    // Given
    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(rateLimit.waitForRefill()).thenReturn(true);
    when(rateLimit.maxWaitTimeMs()).thenReturn(5000L);
    when(rateLimit.maxConcurrentWaits()).thenReturn(100);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    when(response.getWriter()).thenReturn(printWriter);

    // Wait time exceeds max
    RateLimitResponse rejectedResult = RateLimitResponse.rejected(10000);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(rejectedResult);

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isNull();
    verify(joinPoint, never()).proceed();
    verify(response).setStatus(429);
  }

  @Test
  void shouldRejectWhenStillRateLimitedAfterWait() throws Throwable {
    // Given
    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(rateLimit.waitForRefill()).thenReturn(true);
    when(rateLimit.maxWaitTimeMs()).thenReturn(5000L);
    when(rateLimit.maxConcurrentWaits()).thenReturn(100);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    when(response.getWriter()).thenReturn(printWriter);

    // Both calls rejected
    RateLimitResponse rejectedResult = RateLimitResponse.rejected(100);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(rejectedResult)
        .thenReturn(rejectedResult);

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isNull();
    verify(handler, times(2)).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
    verify(joinPoint, never()).proceed();
    verify(response).setStatus(429);
  }

  @Test
  void shouldExtractClientIpFromXForwardedFor() throws Throwable {
    // Given
    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(rateLimit.waitForRefill()).thenReturn(false);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 70.41.3.18");

    Object expectedResult = "success";
    when(joinPoint.proceed()).thenReturn(expectedResult);

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isEqualTo(expectedResult);
    verify(handler)
        .tryConsume(argThat(ctx -> "203.0.113.50".equals(ctx.getClientIp())), eq(RULE_SET_ID));
  }

  @Test
  void shouldNotAddRemainingHeaderWhenNegative() throws Throwable {
    // Given
    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(rateLimit.waitForRefill()).thenReturn(false);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    Object expectedResult = "success";
    when(joinPoint.proceed()).thenReturn(expectedResult);

    // Negative remaining tokens
    RateLimitResponse allowedResult = RateLimitResponse.allowed(-1, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isEqualTo(expectedResult);
    verify(response, never()).setHeader(eq("X-RateLimit-Remaining"), any());
  }

  @Test
  void shouldHandleNullResponse() throws Throwable {
    // Given - Set up RequestContextHolder with null response
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    Object expectedResult = "success";
    when(joinPoint.proceed()).thenReturn(expectedResult);

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then - Should skip rate limiting
    assertThat(result).isEqualTo(expectedResult);
    verify(joinPoint).proceed();
    verify(handler, never()).tryConsume(any(), any());
  }
}
