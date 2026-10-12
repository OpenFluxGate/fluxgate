package org.fluxgate.spring.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.engine.RateLimitEngine.OnMissingRuleSetStrategy;
import org.fluxgate.core.exception.RedisConnectionException;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.resilience.CircuitBreakerConfig;
import org.fluxgate.core.resilience.ResilientExecutor;
import org.fluxgate.spring.annotation.RateLimit;
import org.fluxgate.spring.aop.RateLimitAspect;
import org.fluxgate.spring.aop.RateLimitExceededException;
import org.fluxgate.spring.handler.EngineBackedRateLimitHandler;
import org.fluxgate.spring.handler.MissingRuleSetProviderRateLimitHandler;
import org.fluxgate.spring.handler.RateLimiterUnavailableException;
import org.fluxgate.spring.handler.ResilientRateLimiter;
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
 * Item 10: a rejection caused by the rate limiter failing or by missing configuration is a 503
 * (with {@code Retry-After} when the wait is known), not a 429. An exceeded limit stays 429.
 */
class RateLimiterUnavailableResponseTest {

  private static final String RULE_SET_ID = "rules";

  private static FluxgateRateLimitFilter filter(
      FluxgateRateLimitHandler handler, String ruleSetId, boolean failOpen) {
    return new FluxgateRateLimitFilter(
        handler,
        ruleSetId,
        new String[] {"/**"},
        new String[0],
        false,
        0,
        1,
        failOpen,
        true,
        true,
        false,
        null,
        0L,
        new RequestContextFactory("X-Forwarded-For", false, null, false, null, null),
        new RateLimitHeaderWriter(true, true),
        new ProblemDetailRateLimitResponseWriter(),
        null);
  }

  private static MockHttpServletResponse run(FluxgateRateLimitFilter filter) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
    request.setRemoteAddr("10.0.0.1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    if (response.getStatus() >= 400) {
      assertThat(chain.getRequest()).as("the application must not be reached").isNull();
    }
    return response;
  }

  private static FluxgateRateLimitHandler throwing(RuntimeException e) {
    FluxgateRateLimitHandler handler = mock(FluxgateRateLimitHandler.class);
    when(handler.tryConsume(any(RequestContext.class), anyString())).thenThrow(e);
    return handler;
  }

  @Nested
  class Filter {

    @Test
    void limiterFailureUnderDenyIs503WithoutRetryAfter() throws Exception {
      MockHttpServletResponse response =
          run(filter(throwing(new IllegalStateException("redis down")), RULE_SET_ID, false));

      assertThat(response.getStatus()).isEqualTo(503);
      assertThat(response.getHeader("Retry-After")).isNull();
      assertThat(response.getContentAsString()).contains("\"status\":503");
    }

    @Test
    void unavailableRejectionIs503WithKnownRetryAfterEvenWhenFailingOpen() throws Exception {
      // The component that threw already applied failure-behavior; the filter does not
      // second-guess.
      MockHttpServletResponse response =
          run(
              filter(
                  throwing(new RateLimiterUnavailableException("circuit open", 4200, null)),
                  RULE_SET_ID,
                  true));

      assertThat(response.getStatus()).isEqualTo(503);
      assertThat(response.getHeader("Retry-After")).isEqualTo("5");
    }

    @Test
    void missingRuleSetIdUnderDenyIs503() throws Exception {
      MockHttpServletResponse response =
          run(filter(mock(FluxgateRateLimitHandler.class), "", false));

      assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void exceededLimitStays429() throws Exception {
      FluxgateRateLimitHandler handler = mock(FluxgateRateLimitHandler.class);
      when(handler.tryConsume(any(RequestContext.class), anyString()))
          .thenReturn(RateLimitResponse.rejected(3000));

      MockHttpServletResponse response = run(filter(handler, RULE_SET_ID, false));

      assertThat(response.getStatus()).isEqualTo(429);
      assertThat(response.getHeader("Retry-After")).isEqualTo("3");
    }

    @Test
    void customWriterWithoutUnavailableSupportStillAnswers503() throws Exception {
      RateLimitResponseWriter custom = (request, response, result) -> response.setStatus(418);
      FluxgateRateLimitFilter filter =
          new FluxgateRateLimitFilter(
              throwing(new IllegalStateException("redis down")),
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
              null,
              0L,
              new RequestContextFactory("X-Forwarded-For", false, null, false, null, null),
              new RateLimitHeaderWriter(true, true),
              custom,
              null);

      assertThat(run(filter).getStatus()).isEqualTo(503);
    }
  }

  @Nested
  class Aspect {

    @AfterEach
    void reset() {
      RequestContextHolder.resetRequestAttributes();
    }

    private final RateLimit rateLimit = mock(RateLimit.class);

    private RateLimitAspect aspect(FluxgateRateLimitHandler handler) {
      when(rateLimit.ruleSetId()).thenReturn(RULE_SET_ID);
      when(rateLimit.permits()).thenReturn(1L);
      return new RateLimitAspect(
          handler,
          RULE_SET_ID,
          false,
          true,
          false,
          1,
          new RequestContextFactory("X-Forwarded-For", false, null, false, null, null),
          new RateLimitHeaderWriter(true, true),
          new ProblemDetailRateLimitResponseWriter(),
          null);
    }

    private ProceedingJoinPoint joinPoint(String method) throws Exception {
      Method m = Endpoints.class.getMethod(method);
      MethodSignature signature = mock(MethodSignature.class);
      when(signature.getMethod()).thenReturn(m);
      when(signature.getName()).thenReturn(method);
      when(signature.getDeclaringType()).thenReturn(Endpoints.class);
      ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
      when(joinPoint.getSignature()).thenReturn(signature);
      when(joinPoint.getTarget()).thenReturn(new Endpoints());
      return joinPoint;
    }

    @Test
    void handlerGets503() throws Throwable {
      MockHttpServletResponse response = new MockHttpServletResponse();
      RequestContextHolder.setRequestAttributes(
          new ServletRequestAttributes(new MockHttpServletRequest("GET", "/x"), response));

      aspect(throwing(new RateLimiterUnavailableException("down", 2000, null)))
          .aroundMethod(joinPoint("handle"), rateLimit);

      assertThat(response.getStatus()).isEqualTo(503);
      assertThat(response.getHeader("Retry-After")).isEqualTo("2");
    }

    @Test
    void serviceGetsAnExceptionMarkedUnavailable() throws Exception {
      RateLimitAspect aspect = aspect(throwing(new IllegalStateException("redis down")));
      ProceedingJoinPoint joinPoint = joinPoint("service");

      assertThatThrownBy(() -> aspect.aroundMethod(joinPoint, rateLimit))
          .isInstanceOfSatisfying(
              RateLimitExceededException.class, e -> assertThat(e.isServiceUnavailable()).isTrue());
    }

    @Test
    void exceededLimitIsNotMarkedUnavailable() throws Exception {
      FluxgateRateLimitHandler handler = mock(FluxgateRateLimitHandler.class);
      when(handler.tryConsume(any(RequestContext.class), anyString()))
          .thenReturn(RateLimitResponse.rejected(1000));
      RateLimitAspect aspect = aspect(handler);
      ProceedingJoinPoint joinPoint = joinPoint("service");

      assertThatThrownBy(() -> aspect.aroundMethod(joinPoint, rateLimit))
          .isInstanceOfSatisfying(
              RateLimitExceededException.class,
              e -> assertThat(e.isServiceUnavailable()).isFalse());
    }
  }

  @Nested
  class Sources {

    private final RequestContext context =
        RequestContext.builder().clientIp("10.0.0.1").endpoint("/api/orders").build();

    private final RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder(RULE_SET_ID)
            .rules(
                List.of(
                    RateLimitRule.builder("r")
                        .name("r")
                        .enabled(true)
                        .ruleSetId(RULE_SET_ID)
                        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 5).build())
                        .build()))
            .keyResolver((ctx, rule) -> RateLimitKey.of("ip:" + ctx.getClientIp()))
            .build();

    private final RateLimiter failing =
        (ctx, rules, permits) -> {
          throw new RedisConnectionException("redis is down");
        };

    @Test
    void resilientLimiterUnderDenySignalsUnavailable() {
      ResilientRateLimiter limiter =
          new ResilientRateLimiter(failing, ResilientExecutor.disabled(), null, false, null);

      assertThatThrownBy(() -> limiter.tryConsume(context, ruleSet, 1L))
          .isInstanceOfSatisfying(
              RateLimiterUnavailableException.class,
              e -> assertThat(e.getRetryAfterMillis()).isEqualTo(-1L));
    }

    @Test
    void openCircuitAdvertisesItsWait() {
      ResilientExecutor executor =
          ResilientExecutor.withCircuitBreakerOnly(
              CircuitBreakerConfig.builder()
                  .enabled(true)
                  .failureThreshold(1)
                  .minimumNumberOfCalls(1)
                  .slidingWindowSize(1)
                  .waitDurationInOpenState(Duration.ofSeconds(30))
                  .build(),
              "test");
      ResilientRateLimiter limiter = new ResilientRateLimiter(failing, executor, null, false, null);

      // first call fails and opens the circuit; the second is short-circuited
      assertThatThrownBy(() -> limiter.tryConsume(context, ruleSet, 1L))
          .isInstanceOf(RateLimiterUnavailableException.class);
      assertThatThrownBy(() -> limiter.tryConsume(context, ruleSet, 1L))
          .isInstanceOfSatisfying(
              RateLimiterUnavailableException.class,
              e -> assertThat(e.getRetryAfterMillis()).isEqualTo(30_000L));
    }

    @Test
    void missingRuleSetProviderUnderDenySignalsUnavailable() {
      MissingRuleSetProviderRateLimitHandler handler =
          new MissingRuleSetProviderRateLimitHandler(false);

      assertThatThrownBy(() -> handler.tryConsume(context, RULE_SET_ID))
          .isInstanceOf(RateLimiterUnavailableException.class);
    }

    @Test
    void unknownRuleSetUnderDenySignalsUnavailable() {
      RateLimitEngine engine =
          RateLimitEngine.builder()
              .ruleSetProvider(id -> java.util.Optional.empty())
              .rateLimiter(failing)
              .onMissingRuleSetStrategy(OnMissingRuleSetStrategy.DENY)
              .build();

      assertThatThrownBy(() -> new EngineBackedRateLimitHandler(engine).tryConsume(context, "nope"))
          .isInstanceOf(RateLimiterUnavailableException.class);
    }
  }

  /** A controller-like class with a handler and a plain method. */
  static class Endpoints {

    @GetMapping("/x")
    public String handle() {
      return "x";
    }

    public String service() {
      return "s";
    }
  }
}
