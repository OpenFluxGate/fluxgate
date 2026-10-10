package org.fluxgate.spring.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.spring.annotation.RateLimit;
import org.fluxgate.spring.filter.ProblemDetailRateLimitResponseWriter;
import org.fluxgate.spring.filter.RateLimitHeaderWriter;
import org.fluxgate.spring.filter.RequestContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * R5: a rejected {@code @RateLimit} invocation writes an HTTP response only for a web handler;
 * every other caller gets {@link RateLimitExceededException} and never a {@code null} it did not
 * ask for.
 */
class RateLimitAspectRejectionTest {

  private static final String RULE_SET_ID = "rules";

  /** A controller: its handler methods may have the response written for them. */
  static class OrderController {

    @GetMapping("/orders")
    public String list() {
      return "orders";
    }

    @PostMapping("/orders")
    public void create() {}

    @RequestMapping("/count")
    public int count() {
      return 1;
    }

    /** Not a handler: called from Java, for example by another controller method. */
    public String helper() {
      return "helper";
    }
  }

  /** A service called from a controller while a request is being handled. */
  static class OrderService {

    public String place() {
      return "placed";
    }

    public long total() {
      return 42L;
    }

    public void audit() {}
  }

  interface ApiContract {

    @GetMapping("/contract")
    String fetch();
  }

  static class ContractController implements ApiContract {

    @Override
    public String fetch() {
      return "contract";
    }
  }

  private final FluxgateRateLimitHandler handler = mock(FluxgateRateLimitHandler.class);
  private final RateLimit rateLimit = mock(RateLimit.class);
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;
  private RateLimitAspect aspect;

  @BeforeEach
  void setUp() {
    request = new MockHttpServletRequest("GET", "/orders");
    response = new MockHttpServletResponse();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
    when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
    when(rateLimit.permits()).thenReturn(1L);
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.rejected(2000));
    aspect =
        new RateLimitAspect(
            handler,
            RULE_SET_ID,
            false,
            true,
            false,
            10,
            new RequestContextFactory("X-Forwarded-For", false, null, false, null, null),
            new RateLimitHeaderWriter(true, true),
            new ProblemDetailRateLimitResponseWriter(),
            null);
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  private static ProceedingJoinPoint joinPoint(Object target, String methodName) throws Exception {
    Method method = target.getClass().getMethod(methodName);
    MethodSignature signature = mock(MethodSignature.class);
    when(signature.getMethod()).thenReturn(method);
    when(signature.getName()).thenReturn(methodName);
    when(signature.getDeclaringType()).thenReturn(target.getClass());
    ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
    when(joinPoint.getSignature()).thenReturn(signature);
    when(joinPoint.getTarget()).thenReturn(target);
    return joinPoint;
  }

  @Test
  void writesTheResponseForAControllerHandler() throws Throwable {
    ProceedingJoinPoint joinPoint = joinPoint(new OrderController(), "list");

    Object result = aspect.aroundMethod(joinPoint, rateLimit);

    assertThat(result).isNull();
    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader("Retry-After")).isEqualTo("2");
    verify(joinPoint, never()).proceed();
  }

  @Test
  void writesTheResponseForAVoidControllerHandler() throws Throwable {
    ProceedingJoinPoint joinPoint = joinPoint(new OrderController(), "create");

    assertThat(aspect.aroundMethod(joinPoint, rateLimit)).isNull();
    assertThat(response.getStatus()).isEqualTo(429);
  }

  @Test
  void findsTheMappingOnAnInterface() throws Throwable {
    ProceedingJoinPoint joinPoint = joinPoint(new ContractController(), "fetch");

    assertThat(aspect.aroundMethod(joinPoint, rateLimit)).isNull();
    assertThat(response.getStatus()).isEqualTo(429);
  }

  @Test
  void throwsForAServiceMethodCalledDuringARequest() throws Exception {
    ProceedingJoinPoint joinPoint = joinPoint(new OrderService(), "place");

    assertThatThrownBy(() -> aspect.aroundMethod(joinPoint, rateLimit))
        .isInstanceOf(RateLimitExceededException.class);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.isCommitted()).isFalse();
    assertThat(response.getContentAsString()).isEmpty();
  }

  @Test
  void throwsForAServiceMethodReturningAPrimitive() throws Exception {
    // Returning null here would surface as an AopInvocationException in the caller.
    ProceedingJoinPoint joinPoint = joinPoint(new OrderService(), "total");

    assertThatThrownBy(() -> aspect.aroundMethod(joinPoint, rateLimit))
        .isInstanceOf(RateLimitExceededException.class);
  }

  @Test
  void throwsForAVoidServiceMethod() throws Exception {
    ProceedingJoinPoint joinPoint = joinPoint(new OrderService(), "audit");

    assertThatThrownBy(() -> aspect.aroundMethod(joinPoint, rateLimit))
        .isInstanceOf(RateLimitExceededException.class);
  }

  @Test
  void throwsForANonHandlerMethodOfAController() throws Exception {
    ProceedingJoinPoint joinPoint = joinPoint(new OrderController(), "helper");

    assertThatThrownBy(() -> aspect.aroundMethod(joinPoint, rateLimit))
        .isInstanceOf(RateLimitExceededException.class);
  }

  @Test
  void throwsForAHandlerReturningAPrimitive() throws Exception {
    ProceedingJoinPoint joinPoint = joinPoint(new OrderController(), "count");

    assertThatThrownBy(() -> aspect.aroundMethod(joinPoint, rateLimit))
        .isInstanceOf(RateLimitExceededException.class);
  }

  @Test
  void throwsOutsideARequest() throws Exception {
    RequestContextHolder.resetRequestAttributes();
    ProceedingJoinPoint joinPoint = joinPoint(new OrderController(), "list");

    assertThatThrownBy(() -> aspect.aroundMethod(joinPoint, rateLimit))
        .isInstanceOf(RateLimitExceededException.class);
  }

  @Test
  void allowedServiceCallsProceed() throws Throwable {
    when(handler.tryConsume(any(RequestContext.class), eq(RULE_SET_ID)))
        .thenReturn(RateLimitResponse.allowed(5, 0));
    ProceedingJoinPoint joinPoint = joinPoint(new OrderService(), "place");
    when(joinPoint.proceed()).thenReturn("placed");

    assertThat(aspect.aroundMethod(joinPoint, rateLimit)).isEqualTo("placed");
  }
}
