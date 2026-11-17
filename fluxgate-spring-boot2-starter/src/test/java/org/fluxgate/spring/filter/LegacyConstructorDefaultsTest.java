package org.fluxgate.spring.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.spring.annotation.RateLimit;
import org.fluxgate.spring.aop.RateLimitAspect;
import org.fluxgate.spring.aop.RateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Item 8: the public constructors kept for source compatibility must not quietly enable the two
 * security-sensitive behaviours the properties default to off: trusting {@code X-Forwarded-For} and
 * letting requests through when the limiter fails.
 */
class LegacyConstructorDefaultsTest {

  private static MockHttpServletRequest forgedRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
    request.setRemoteAddr("10.1.2.3");
    request.addHeader("X-Forwarded-For", "6.6.6.6");
    return request;
  }

  @Test
  void filterLegacyConstructorsIgnoreTheForwardedHeader() throws Exception {
    AtomicReference<String> clientIp = new AtomicReference<>();
    FluxgateRateLimitHandler handler =
        (context, ruleSetId) -> {
          clientIp.set(context.getClientIp());
          return RateLimitResponse.allowed(1, 0);
        };

    new FluxgateRateLimitFilter(handler, "rules", null, null)
        .doFilter(forgedRequest(), new MockHttpServletResponse(), new MockFilterChain());

    assertThat(clientIp).hasValue("10.1.2.3");
  }

  @Test
  void filterLegacyConstructorsFailClosed() throws Exception {
    FluxgateRateLimitHandler handler = mock(FluxgateRateLimitHandler.class);
    when(handler.tryConsume(any(RequestContext.class), anyString()))
        .thenThrow(new IllegalStateException("redis down"));
    MockFilterChain chain = new MockFilterChain();

    new FluxgateRateLimitFilter(handler, "rules", null, null, false, 0, 1, null)
        .doFilter(forgedRequest(), new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).as("the application must not be reached").isNull();
  }

  @Test
  void aspectLegacyConstructorIgnoresTheForwardedHeaderAndFailsClosed() throws Throwable {
    AtomicReference<String> clientIp = new AtomicReference<>();
    FluxgateRateLimitHandler handler =
        (context, ruleSetId) -> {
          clientIp.set(context.getClientIp());
          throw new IllegalStateException("redis down");
        };
    RateLimit rateLimit = mock(RateLimit.class);
    when(rateLimit.ruleSetId()).thenReturn("rules");
    when(rateLimit.permits()).thenReturn(1L);
    ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
    Signature signature = mock(Signature.class);
    when(signature.getDeclaringType()).thenReturn(LegacyConstructorDefaultsTest.class);
    when(signature.getName()).thenReturn("call");
    when(joinPoint.getSignature()).thenReturn(signature);

    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
        new org.springframework.web.context.request.ServletRequestAttributes(
            forgedRequest(), new MockHttpServletResponse()));
    try {
      RateLimitAspect aspect = new RateLimitAspect(handler, null);
      assertThatThrownBy(() -> aspect.aroundMethod(joinPoint, rateLimit))
          .isInstanceOf(RateLimitExceededException.class);
    } finally {
      org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }
    assertThat(clientIp).hasValue("10.1.2.3");
    verify(joinPoint, never()).proceed();
  }
}
