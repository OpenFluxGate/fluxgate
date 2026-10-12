package org.fluxgate.spring.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.spring.annotation.RateLimit;
import org.fluxgate.spring.filter.IdentitySource;
import org.fluxgate.spring.filter.ProblemDetailRateLimitResponseWriter;
import org.fluxgate.spring.filter.RateLimitDurationRecorder;
import org.fluxgate.spring.filter.RateLimitHeaderWriter;
import org.fluxgate.spring.filter.RequestContextCustomizer;
import org.fluxgate.spring.filter.RequestContextFactory;
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

    // By default the intercepted method is a controller handler, so rejections are written.
    stubWebHandler();
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
  void aFailingDurationRecorderDoesNotReplaceTheMethodResult() throws Throwable {
    // Given
    aspect =
        new RateLimitAspect(
            handler,
            "",
            true,
            false,
            false,
            50,
            new RequestContextFactory(null, false, null, false, null, null),
            new RateLimitHeaderWriter(true, true),
            new ProblemDetailRateLimitResponseWriter(),
            (ruleSetId, endpoint, method, duration) -> {
              throw new IllegalStateException("metrics backend down");
            });
    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(joinPoint.proceed()).thenReturn("success");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isEqualTo("success");
    verify(joinPoint, times(1)).proceed();
  }

  @Test
  void aDurationRecorderThrowingACheckedExceptionDoesNotReplaceTheMethodResult() throws Throwable {
    // Given
    aspect =
        aspectWithDurationRecorder(
            (ruleSetId, endpoint, method, duration) ->
                sneakyThrow(new IOException("metrics backend down")));
    stubAllowedWebCall();
    when(joinPoint.proceed()).thenReturn("success");

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isEqualTo("success");
    verify(joinPoint, times(1)).proceed();
  }

  @Test
  void aFailingDurationRecorderDoesNotReplaceTheMethodException() throws Throwable {
    // Given
    aspect =
        aspectWithDurationRecorder(
            (ruleSetId, endpoint, method, duration) -> {
              throw new IllegalStateException("metrics backend down");
            });
    stubAllowedWebCall();
    IllegalArgumentException original = new IllegalArgumentException("bad argument");
    when(joinPoint.proceed()).thenThrow(original);

    // When / Then
    assertThatThrownBy(() -> aspect.aroundMethod(joinPoint, rateLimit)).isSameAs(original);
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
    verify(response).setContentType("application/problem+json;charset=UTF-8");
  }

  @Test
  void shouldStillRateLimitWithoutAnHttpContext() throws Throwable {
    // Given - a scheduled task or message listener calling a @RateLimit method
    RequestContextHolder.resetRequestAttributes();
    stubSignature();

    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    Object expectedResult = "success";
    when(joinPoint.proceed()).thenReturn(expectedResult);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isEqualTo(expectedResult);
    verify(joinPoint).proceed();
    verify(handler)
        .tryConsume(
            argThat(ctx -> "OrderService.placeOrder".equals(ctx.getEndpoint())), eq(RULE_SET_ID));
  }

  @Test
  void shouldThrowWhenRejectedWithoutAnHttpContext() throws Throwable {
    // Given - there is no response to write a 429 to
    RequestContextHolder.resetRequestAttributes();
    stubSignature();

    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.rejected(2500));

    // When / Then
    assertThatThrownBy(() -> aspect.aroundMethod(joinPoint, rateLimit))
        .isInstanceOf(RateLimitExceededException.class)
        .extracting(e -> ((RateLimitExceededException) e).getRetryAfterMillis())
        .isEqualTo(2500L);
    verify(joinPoint, never()).proceed();
  }

  @Test
  void shouldThrowWhenTheAnnotationAsksForIt() throws Throwable {
    // Given - the application maps the exception with a @ControllerAdvice
    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(rateLimit.throwOnReject()).thenReturn(true);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.rejected(1000));

    // When / Then
    assertThatThrownBy(() -> aspect.aroundMethod(joinPoint, rateLimit))
        .isInstanceOf(RateLimitExceededException.class);
    verify(joinPoint, never()).proceed();
    verify(response, never()).setStatus(429);
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
  void shouldDenyRequestWhenRuleSetIdMissingAndConfigured() throws Throwable {
    // Given
    aspect = new RateLimitAspect(handler, null, "X-Forwarded-For", true, true, "", true);
    when(rateLimit.ruleSetId()).thenReturn("");

    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    when(response.getWriter()).thenReturn(printWriter);

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isNull();
    verify(joinPoint, never()).proceed();
    verify(handler, never()).tryConsume(any(), any());
    // Item 10: unconfigured rate limiting is 503, not 429.
    verify(response).setStatus(503);
  }

  @Test
  void shouldUseDefaultRuleSetIdWhenAnnotationRuleSetIdIsEmpty() throws Throwable {
    // Given
    aspect =
        new RateLimitAspect(handler, null, "X-Forwarded-For", true, true, "default-rules", true);
    when(rateLimit.ruleSetId()).thenReturn("");
    when(rateLimit.waitForRefill()).thenReturn(false);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    Object expectedResult = "success";
    when(joinPoint.proceed()).thenReturn(expectedResult);
    when(handler.tryConsume(any(RequestContext.class), eq("default-rules")))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isEqualTo(expectedResult);
    verify(handler).tryConsume(any(RequestContext.class), eq("default-rules"));
  }

  @Test
  void shouldFailOpenOnHandlerException() throws Throwable {
    // Explicitly trusting X-Forwarded-For and failing open: the legacy constructor no longer does.
    aspect = new RateLimitAspect(handler, null, "X-Forwarded-For", true, true, "");
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

  // ===== H11: the global max-wait-time-ms caps the annotation =====

  @Test
  void shouldCapTheAnnotationWaitAtTheGlobalMaximum() throws Throwable {
    // The annotation allows 5s, the global cap 200ms: a 1s refill must be rejected at once rather
    // than park the thread for a second.
    aspect = aspectWithGlobalMaxWait(200);
    stubWaitingInvocation(5000L);
    when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.rejected(1000));

    long start = System.nanoTime();
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    assertThat(result).isNull();
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(900);
    verify(handler, times(1)).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
    verify(joinPoint, never()).proceed();
    verify(response).setStatus(429);
  }

  @Test
  void shouldKeepAStricterAnnotationWait() throws Throwable {
    aspect = aspectWithGlobalMaxWait(5000);
    stubWaitingInvocation(200L);
    when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.rejected(1000));

    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    assertThat(result).isNull();
    verify(handler, times(1)).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
  }

  @Test
  void shouldWaitWhenTheRefillFitsBothLimits() throws Throwable {
    aspect = aspectWithGlobalMaxWait(500);
    stubWaitingInvocation(5000L);
    when(joinPoint.proceed()).thenReturn("success");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.rejected(50))
        .thenReturn(RateLimitResponse.allowed(10, 0));

    assertThat(aspect.aroundMethod(joinPoint, rateLimit)).isEqualTo("success");
    verify(handler, times(2)).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
  }

  @Test
  void shouldDefaultTheGlobalMaximumToTheWaitForRefillPropertyDefault() {
    assertThat(new RateLimitAspect(handler, null).getMaxWaitTimeMs()).isEqualTo(5000L);
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
    // Explicitly trusting X-Forwarded-For and failing open: the legacy constructor no longer does.
    aspect = new RateLimitAspect(handler, null, "X-Forwarded-For", true, true, "");
    // Given
    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(rateLimit.waitForRefill()).thenReturn(false);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getHeaders("X-Forwarded-For"))
        .thenAnswer(
            invocation ->
                java.util.Collections.enumeration(java.util.List.of("203.0.113.50, 70.41.3.18")));

    Object expectedResult = "success";
    when(joinPoint.proceed()).thenReturn(expectedResult);

    RateLimitResponse allowedResult = RateLimitResponse.allowed(50, 0);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID))).thenReturn(allowedResult);

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isEqualTo(expectedResult);
    // right-most hop is used when trust-client-ip-header=true with no trusted-proxies configured
    verify(handler)
        .tryConsume(argThat(ctx -> "70.41.3.18".equals(ctx.getClientIp())), eq(RULE_SET_ID));
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
  void shouldStillRateLimitWhenTheResponseIsUnavailable() throws Throwable {
    // Given - Set up RequestContextHolder with null response
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    Object expectedResult = "success";
    when(joinPoint.proceed()).thenReturn(expectedResult);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isEqualTo(expectedResult);
    verify(joinPoint).proceed();
    verify(handler).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
  }

  // ===== C2: the target method runs exactly once =====

  @Test
  void shouldNotInvokeTheMethodTwiceWhenItThrows() throws Throwable {
    // Given - a business exception used to be caught as a "rate limiter error" and retried
    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(request.getRequestURI()).thenReturn("/api/orders");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    IllegalStateException business = new IllegalStateException("order rejected");
    when(joinPoint.proceed()).thenThrow(business);

    // When / Then
    assertThatThrownBy(() -> aspect.aroundMethod(joinPoint, rateLimit)).isSameAs(business);
    verify(joinPoint, times(1)).proceed();
  }

  // ===== C1: the wait semaphore outlives the invocation =====

  @Test
  void shouldRejectTheWaiterBeyondTheSemaphorePermits() throws Throwable {
    // Given - one wait permit, so the second concurrent waiter is rejected without sleeping
    int permits = 1;
    long waitMs = 500;
    aspect = aspectWithWaitPermits(permits);

    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(rateLimit.waitForRefill()).thenReturn(true);
    when(rateLimit.maxWaitTimeMs()).thenReturn(5000L);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.rejected(waitMs));

    ServletRequestAttributes attributes = new ServletRequestAttributes(request, response);
    Thread holder =
        new Thread(
            () -> {
              RequestContextHolder.setRequestAttributes(attributes);
              try {
                aspect.aroundMethod(joinPoint, rateLimit);
              } catch (Throwable e) {
                throw new IllegalStateException(e);
              } finally {
                RequestContextHolder.resetRequestAttributes();
              }
            });
    holder.start();
    Thread.sleep(100); // let the holder take the only permit and start sleeping

    // When
    long startedAt = System.currentTimeMillis();
    aspect.aroundMethod(joinPoint, rateLimit);
    long rejectedInMs = System.currentTimeMillis() - startedAt;
    holder.join(10_000);

    // Then - the holder retried once, the rejected waiter did not retry at all
    assertThat(rejectedInMs).isLessThan(waitMs);
    verify(handler, times((permits + 1) + permits))
        .tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
  }

  // ===== H11: the rule's policy decides, the annotation overrides =====

  @Test
  void shouldFollowTheRulesWaitForRefillPolicy() throws Throwable {
    // Given - the annotation does not ask to wait, but the matched rule's policy does
    aspect = aspectWithWaitPermits(10);

    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(rateLimit.waitForRefill()).thenReturn(false);
    when(rateLimit.maxWaitTimeMs()).thenReturn(5000L);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    Object expectedResult = "success";
    when(joinPoint.proceed()).thenReturn(expectedResult);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(
            RateLimitResponse.rejected(
                50, org.fluxgate.core.config.OnLimitExceedPolicy.WAIT_FOR_REFILL))
        .thenReturn(RateLimitResponse.allowed(10, 0));

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isEqualTo(expectedResult);
    verify(handler, times(2)).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
  }

  @Test
  void shouldNotWaitWhenTheRulePolicyIsRejectRequest() throws Throwable {
    // Given
    aspect = aspectWithWaitPermits(10);

    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(rateLimit.waitForRefill()).thenReturn(false);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.rejected(50));

    // When
    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    assertThat(result).isNull();
    verify(handler, times(1)).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
    verify(response).setStatus(429);
  }

  // ===== H17: identity scopes resolve like they do in the filter =====

  @Test
  void shouldPopulateUserIdAndApiKeyLikeTheFilter() throws Throwable {
    // Given - header identity is opt-in since 0.4 (C-4)
    aspect =
        new RateLimitAspect(
            handler,
            "",
            true,
            false,
            false,
            50,
            new RequestContextFactory(
                "X-Forwarded-For",
                false,
                null,
                false,
                null,
                null,
                IdentitySource.HEADERS,
                null,
                null),
            new RateLimitHeaderWriter(true, true),
            new ProblemDetailRateLimitResponseWriter(),
            null);
    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(request.getHeader("X-User-Id")).thenReturn("user-12345");
    when(request.getHeader("X-API-Key")).thenReturn("api-key-abc");
    when(joinPoint.proceed()).thenReturn("success");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    verify(handler)
        .tryConsume(
            argThat(
                ctx ->
                    "user-12345".equals(ctx.getUserId())
                        && "api-key-abc".equals(ctx.getApiKey())
                        && "/api/users".equals(ctx.getEndpoint())),
            eq(RULE_SET_ID));
  }

  @Test
  void shouldIgnoreIdentityHeadersByDefault() throws Throwable {
    // C-4 (0.4): the default identity source is the principal, so headers are never consulted.
    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(joinPoint.proceed()).thenReturn("success");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    aspect.aroundMethod(joinPoint, rateLimit);

    verify(handler)
        .tryConsume(
            argThat(ctx -> ctx.getUserId() == null && ctx.getApiKey() == null), eq(RULE_SET_ID));
    verify(request, never()).getHeader("X-User-Id");
    verify(request, never()).getHeader("X-API-Key");
  }

  // ===== permits =====

  @Test
  void shouldPassAnnotationPermitsToTheHandler() throws Throwable {
    // Given
    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(rateLimit.permits()).thenReturn(7L);
    when(request.getRequestURI()).thenReturn("/api/export");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(joinPoint.proceed()).thenReturn("success");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID), eq(7L)))
        .thenReturn(RateLimitResponse.allowed(50, 0));

    // When
    aspect.aroundMethod(joinPoint, rateLimit);

    // Then
    verify(handler).tryConsume(any(RequestContext.class), eq(RULE_SET_ID), eq(7L));
    verify(handler, never()).tryConsume(any(RequestContext.class), eq(RULE_SET_ID));
  }

  // ===== test fixtures =====

  private RateLimitAspect aspectWithWaitPermits(int permits) {
    return new RateLimitAspect(
        handler,
        "",
        true,
        false,
        true,
        permits,
        new RequestContextFactory("X-Forwarded-For", true, null, false, null, null),
        new RateLimitHeaderWriter(true, true),
        new ProblemDetailRateLimitResponseWriter(),
        null);
  }

  private RateLimitAspect aspectWithGlobalMaxWait(long maxWaitTimeMs) {
    return new RateLimitAspect(
        handler,
        "",
        true,
        false,
        true,
        50,
        maxWaitTimeMs,
        new RequestContextFactory("X-Forwarded-For", true, null, false, null, null),
        new RateLimitHeaderWriter(true, true),
        new ProblemDetailRateLimitResponseWriter(),
        null);
  }

  private void stubWaitingInvocation(long annotationMaxWaitMs) {
    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(rateLimit.waitForRefill()).thenReturn(true);
    when(rateLimit.maxWaitTimeMs()).thenReturn(annotationMaxWaitMs);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
  }

  /** Stubs the signature the aspect uses as the endpoint for non-web invocations. */
  private void stubSignature() {
    org.aspectj.lang.Signature signature = mock(org.aspectj.lang.Signature.class);
    when(signature.getDeclaringType()).thenReturn(OrderService.class);
    when(signature.getName()).thenReturn("placeOrder");
    when(joinPoint.getSignature()).thenReturn(signature);
  }

  /** Stubs the join point as a {@code @GetMapping} method of {@link ApiController}. */
  private void stubWebHandler() {
    try {
      org.aspectj.lang.reflect.MethodSignature signature =
          mock(org.aspectj.lang.reflect.MethodSignature.class);
      when(signature.getMethod()).thenReturn(ApiController.class.getMethod("handle"));
      when(signature.getDeclaringType()).thenReturn(ApiController.class);
      when(signature.getName()).thenReturn("handle");
      when(joinPoint.getSignature()).thenReturn(signature);
      when(joinPoint.getTarget()).thenReturn(new ApiController());
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Stand-in for an application controller. */
  static class ApiController {

    @org.springframework.web.bind.annotation.GetMapping("/api/users")
    public Object handle() {
      return "handled";
    }
  }

  /** Stand-in for a user class whose simple name the endpoint is derived from. */
  private static final class OrderService {}

  private RateLimitAspect aspectWithDurationRecorder(RateLimitDurationRecorder durationRecorder) {
    return new RateLimitAspect(
        handler,
        "",
        true,
        false,
        false,
        50,
        new RequestContextFactory(null, false, null, false, null, null),
        new RateLimitHeaderWriter(true, true),
        new ProblemDetailRateLimitResponseWriter(),
        durationRecorder);
  }

  private void stubAllowedWebCall() {
    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(request.getRequestURI()).thenReturn("/api/users");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(50, 0));
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
    throw (T) t;
  }
}
