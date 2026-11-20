package org.fluxgate.spring.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.engine.RateLimitEngine.OnMissingRuleSetStrategy;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.fluxgate.core.resilience.CircuitBreaker;
import org.fluxgate.core.resilience.CircuitBreakerConfig;
import org.fluxgate.core.resilience.ResilientExecutor;
import org.fluxgate.core.resilience.RetryConfig;
import org.fluxgate.spring.annotation.RateLimit;
import org.fluxgate.spring.aop.RateLimitAspect;
import org.fluxgate.spring.handler.EngineBackedRateLimitHandler;
import org.fluxgate.spring.handler.ResilientRateLimiter;
import org.fluxgate.spring.rule.SpringAntPathMatcherAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * A request cost above the capacity of a matching band is the client's error: HTTP 429 with a
 * problem document naming the cost, never a limiter failure that counts against the circuit breaker
 * or triggers {@code failure-behavior}.
 */
class OversizedCostTest {

  private static final String RULE_SET_ID = "orders";
  private static final String COST_HEADER = "X-RateLimit-Cost";

  private static RateLimitRuleSet ruleSet() {
    return RateLimitRuleSet.builder(RULE_SET_ID)
        .rules(
            List.of(
                RateLimitRule.builder("orders-rule")
                    .name("orders")
                    .enabled(true)
                    .ruleSetId(RULE_SET_ID)
                    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).build())
                    .build()))
        .keyResolver((ctx, rule) -> RateLimitKey.of("ip:" + ctx.getClientIp()))
        .build();
  }

  private static EngineBackedRateLimitHandler handlerOver(RateLimiter limiter) {
    RateLimitEngine engine =
        RateLimitEngine.builder()
            .ruleSetProvider(
                id -> RULE_SET_ID.equals(id) ? Optional.of(ruleSet()) : Optional.empty())
            .rateLimiter(limiter)
            .onMissingRuleSetStrategy(OnMissingRuleSetStrategy.DENY)
            .pathMatcher(new SpringAntPathMatcherAdapter(true))
            .build();
    return new EngineBackedRateLimitHandler(engine);
  }

  private static ResilientExecutor breaker() {
    return new ResilientExecutor(
        RetryConfig.disabled(),
        CircuitBreakerConfig.builder()
            .enabled(true)
            .failureThreshold(3)
            .minimumNumberOfCalls(3)
            .slidingWindowSize(5)
            .waitDurationInOpenState(Duration.ofMinutes(5))
            .build(),
        "oversized-cost");
  }

  private static FluxgateRateLimitFilter filter(EngineBackedRateLimitHandler handler) {
    return new FluxgateRateLimitFilter(
        handler,
        RULE_SET_ID,
        new String[] {"/**"},
        new String[0],
        false,
        0,
        1,
        false,
        true,
        true,
        false,
        COST_HEADER,
        1000L,
        new RequestContextFactory("X-Forwarded-For", false, null, false, null, null),
        new RateLimitHeaderWriter(true, true),
        new ProblemDetailRateLimitResponseWriter(),
        null);
  }

  private static MockHttpServletResponse send(FluxgateRateLimitFilter filter, String cost)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
    request.setRemoteAddr("10.0.0.1");
    if (cost != null) {
      request.addHeader(COST_HEADER, cost);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    if (response.getStatus() >= 400) {
      assertThat(chain.getRequest()).as("the application must not be reached").isNull();
    }
    return response;
  }

  @Test
  void manyOversizedCostsAre429AndKeepTheBreakerClosed() throws Exception {
    AtomicInteger fallbackCalls = new AtomicInteger();
    RateLimiter fallback =
        (ctx, rules, permits) -> {
          fallbackCalls.incrementAndGet();
          throw new AssertionError("an oversized cost must never reach the fallback");
        };
    ResilientExecutor executor = breaker();
    ResilientRateLimiter limiter =
        new ResilientRateLimiter(
            new Bucket4jRateLimiter(100L, Duration.ofMinutes(1)), executor, fallback, false, null);
    FluxgateRateLimitFilter filter = filter(handlerOver(limiter));

    for (int i = 0; i < 100; i++) {
      MockHttpServletResponse response = send(filter, "500");

      assertThat(response.getStatus()).isEqualTo(429);
      assertThat(response.getHeader("Retry-After")).isNull();
      assertThat(response.getContentType()).startsWith("application/problem+json");
      assertThat(response.getContentAsString())
          .contains("\"status\":429")
          .contains("Request cost of 500 permits exceeds the rate limit capacity of 10");
    }

    assertThat(executor.getCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(fallbackCalls).hasValue(0);
    // ordinary traffic is still served and limited by the primary limiter
    assertThat(send(filter, null).getStatus()).isEqualTo(200);
    assertThat(send(filter, "9").getStatus()).isEqualTo(200);
    assertThat(send(filter, "5").getStatus()).isEqualTo(429);
  }

  @Test
  void oversizedCostWithoutTheResilientWrapperIsAlso429() throws Exception {
    FluxgateRateLimitFilter filter =
        filter(handlerOver(new Bucket4jRateLimiter(100L, Duration.ofMinutes(1))));

    MockHttpServletResponse response = send(filter, "11");

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getContentAsString())
        .contains("Request cost of 11 permits exceeds the rate limit capacity of 10");
  }

  @Test
  void costAboveMaxCostIsClampedBeforeTheCapacityCheck() throws Exception {
    FluxgateRateLimitFilter filter =
        new FluxgateRateLimitFilter(
            handlerOver(new Bucket4jRateLimiter(100L, Duration.ofMinutes(1))),
            RULE_SET_ID,
            new String[] {"/**"},
            new String[0],
            false,
            0,
            1,
            false,
            true,
            true,
            false,
            COST_HEADER,
            4L,
            new RequestContextFactory("X-Forwarded-For", false, null, false, null, null),
            new RateLimitHeaderWriter(true, true),
            new ProblemDetailRateLimitResponseWriter(),
            null);

    // clamped to max-cost 4, which fits the capacity of 10: allowed twice, then limited
    assertThat(send(filter, "1000000").getStatus()).isEqualTo(200);
    assertThat(send(filter, "1000000").getStatus()).isEqualTo(200);
    assertThat(send(filter, "1000000").getStatus()).isEqualTo(429);
  }

  @Test
  void customWriterWithoutCostSupportStillAnswers429() throws Exception {
    RateLimitResponseWriter custom = (request, response, result) -> response.setStatus(418);
    FluxgateRateLimitFilter filter =
        new FluxgateRateLimitFilter(
            handlerOver(new Bucket4jRateLimiter(100L, Duration.ofMinutes(1))),
            RULE_SET_ID,
            new String[] {"/**"},
            new String[0],
            false,
            0,
            1,
            false,
            true,
            true,
            false,
            COST_HEADER,
            1000L,
            new RequestContextFactory("X-Forwarded-For", false, null, false, null, null),
            new RateLimitHeaderWriter(true, true),
            custom,
            null);

    assertThat(send(filter, "500").getStatus()).isEqualTo(429);
  }

  @Test
  void bodyTemplateRendersTheCapacityAsLimit() throws Exception {
    FluxgateRateLimitFilter filter =
        new FluxgateRateLimitFilter(
            handlerOver(new Bucket4jRateLimiter(100L, Duration.ofMinutes(1))),
            RULE_SET_ID,
            new String[] {"/**"},
            new String[0],
            false,
            0,
            1,
            false,
            true,
            true,
            false,
            COST_HEADER,
            1000L,
            new RequestContextFactory("X-Forwarded-For", false, null, false, null, null),
            new RateLimitHeaderWriter(true, true),
            new ProblemDetailRateLimitResponseWriter(
                "application/json", "{\"code\":{status},\"limit\":{limit}}"),
            null);

    MockHttpServletResponse response = send(filter, "500");

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getContentAsString()).isEqualTo("{\"code\":429,\"limit\":10}");
  }

  @Nested
  class Aspect {

    private final RateLimit rateLimit = mock(RateLimit.class);

    @AfterEach
    void reset() {
      RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void annotationCostAboveTheCapacityIsAConfigurationError503EvenWhenFailingOpen()
        throws Throwable {
      when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
      when(rateLimit.permits()).thenReturn(50L);
      when(rateLimit.maxWaitTimeMs()).thenReturn(0L);
      RateLimitAspect aspect =
          new RateLimitAspect(
              handlerOver(new Bucket4jRateLimiter(100L, Duration.ofMinutes(1))),
              RULE_SET_ID,
              true,
              true,
              false,
              1,
              new RequestContextFactory("X-Forwarded-For", false, null, false, null, null),
              new RateLimitHeaderWriter(true, true),
              new ProblemDetailRateLimitResponseWriter(),
              null);
      MockHttpServletResponse response = new MockHttpServletResponse();
      RequestContextHolder.setRequestAttributes(
          new ServletRequestAttributes(new MockHttpServletRequest("GET", "/x"), response));

      Method method = Endpoints.class.getMethod("handle");
      MethodSignature signature = mock(MethodSignature.class);
      when(signature.getMethod()).thenReturn(method);
      when(signature.getName()).thenReturn("handle");
      when(signature.getDeclaringType()).thenReturn(Endpoints.class);
      ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
      when(joinPoint.getSignature()).thenReturn(signature);
      when(joinPoint.getTarget()).thenReturn(new Endpoints());

      assertThat(aspect.aroundMethod(joinPoint, rateLimit)).isNull();
      assertThat(response.getStatus()).isEqualTo(503);
    }
  }

  /** A controller-like class. */
  static class Endpoints {

    @GetMapping("/x")
    public String handle() {
      return "x";
    }
  }
}
