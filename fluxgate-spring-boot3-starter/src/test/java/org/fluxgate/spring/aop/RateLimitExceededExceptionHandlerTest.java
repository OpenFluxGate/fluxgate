package org.fluxgate.spring.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.spring.annotation.EnableFluxgateAspect;
import org.fluxgate.spring.annotation.RateLimit;
import org.fluxgate.spring.autoconfigure.FluxgateAopAutoConfiguration;
import org.fluxgate.spring.autoconfigure.FluxgateAopExceptionHandlerAutoConfiguration;
import org.fluxgate.spring.handler.RateLimiterUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Item 4: a {@code @RateLimit} rejection thrown outside an MVC handler method reaches Spring MVC as
 * {@link RateLimitExceededException}. It must answer 429 (or 503) with {@code Retry-After}, not the
 * 500 of an unhandled exception, unless the application maps it itself.
 */
class RateLimitExceededExceptionHandlerTest {

  /** The rate limited service, called by a controller - so the aspect cannot write the response. */
  public static class OrderService {

    @RateLimit(ruleSetId = "orders")
    public String place() {
      return "placed";
    }
  }

  @RestController
  public static class OrderController {

    private final OrderService service;

    OrderController(OrderService service) {
      this.service = service;
    }

    @GetMapping("/orders")
    public String place() {
      return service.place();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @EnableFluxgateAspect
  static class App {

    @Bean
    OrderService orderService() {
      return new OrderService();
    }

    @Bean
    OrderController orderController(OrderService service) {
      return new OrderController(service);
    }
  }

  /** The application's own mapping of the exception, ordered before the default one. */
  @RestControllerAdvice
  @Order(RateLimitExceededExceptionHandler.ORDER - 1)
  static class ApplicationAdvice {

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<String> handle(RateLimitExceededException e) {
      return ResponseEntity.status(418).body("own:" + e.isServiceUnavailable());
    }
  }

  /** The ubiquitous application catch-all, unordered like most of them. */
  @RestControllerAdvice
  static class CatchAllAdvice {

    @ExceptionHandler(Exception.class)
    ResponseEntity<String> handle(Exception e) {
      return ResponseEntity.status(500).body("catch-all");
    }
  }

  /** Declares its own handler in a configuration parsed after {@code @EnableFluxgateAspect}. */
  @Configuration(proxyBeanMethods = false)
  static class LaterHandlerConfig {

    @Bean
    RateLimitExceededExceptionHandler ownHandler() {
      return new RateLimitExceededExceptionHandler(
          new org.fluxgate.spring.filter.RateLimitHeaderWriter(false, false),
          (request, response, result) -> response.setStatus(420));
    }
  }

  private static FluxgateRateLimitHandler answering(RateLimitResponse response) {
    return (RequestContext context, String ruleSetId) -> response;
  }

  private static FluxgateRateLimitHandler throwing(RuntimeException e) {
    return (RequestContext context, String ruleSetId) -> {
      throw e;
    };
  }

  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  FluxgateAopAutoConfiguration.class,
                  FluxgateAopExceptionHandlerAutoConfiguration.class))
          .withUserConfiguration(App.class);

  private void run(FluxgateRateLimitHandler handler, MockMvcAssertion assertion) {
    runner
        .withBean(FluxgateRateLimitHandler.class, () -> handler)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertion.verify(context, MockMvcBuilders.webAppContextSetup(context).build());
            });
  }

  @FunctionalInterface
  interface MockMvcAssertion {
    void verify(AssertableWebApplicationContext context, MockMvc mvc) throws Exception;
  }

  @Test
  void exceededLimitInAServiceIs429WithRetryAfter() {
    run(
        answering(RateLimitResponse.rejected(2500L)),
        (context, mvc) -> {
          assertThat(context).hasSingleBean(RateLimitExceededExceptionHandler.class);
          mvc.perform(get("/orders"))
              .andExpect(status().isTooManyRequests())
              .andExpect(header().string("Retry-After", "3"))
              .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
              .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":429")));
        });
  }

  @Test
  void unavailableRateLimitingInAServiceIs503WithRetryAfter() {
    run(
        throwing(new RateLimiterUnavailableException("circuit open", 4200L, null)),
        (context, mvc) ->
            mvc.perform(get("/orders"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(
                    content().string(org.hamcrest.Matchers.containsString("\"status\":503"))));
  }

  @Test
  void unavailableWithoutAKnownDelayHasNoRetryAfter() {
    run(
        throwing(new RateLimiterUnavailableException("rule set missing")),
        (context, mvc) ->
            mvc.perform(get("/orders"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("Retry-After")));
  }

  @Test
  void allowedRequestsAreUntouched() {
    run(
        answering(RateLimitResponse.allowed(5L, 0L)),
        (context, mvc) ->
            mvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andExpect(content().string("placed")));
  }

  @Test
  void anApplicationCatchAllAdviceDoesNotTurnRejectionsInto500() {
    runner
        .withBean(
            FluxgateRateLimitHandler.class, () -> answering(RateLimitResponse.rejected(1000L)))
        .withBean(CatchAllAdvice.class)
        .run(
            context ->
                MockMvcBuilders.webAppContextSetup(context)
                    .build()
                    .perform(get("/orders"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "1")));
  }

  @Test
  void anApplicationAdviceOrderedBeforeTheDefaultWins() {
    runner
        .withBean(
            FluxgateRateLimitHandler.class, () -> answering(RateLimitResponse.rejected(1000L)))
        .withBean(ApplicationAdvice.class)
        .run(
            context ->
                MockMvcBuilders.webAppContextSetup(context)
                    .build()
                    .perform(get("/orders"))
                    .andExpect(status().isIAmATeapot())
                    .andExpect(content().string("own:false")));
  }

  @Test
  void aBeanOfTheHandlerTypeReplacesTheDefault() {
    RateLimitExceededExceptionHandler own =
        new RateLimitExceededExceptionHandler(
            new org.fluxgate.spring.filter.RateLimitHeaderWriter(false, false),
            (request, response, result) -> response.setStatus(420));
    runner
        .withBean(
            FluxgateRateLimitHandler.class, () -> answering(RateLimitResponse.rejected(1000L)))
        .withBean("ownHandler", RateLimitExceededExceptionHandler.class, () -> own)
        .run(
            context -> {
              assertThat(context).hasSingleBean(RateLimitExceededExceptionHandler.class);
              assertThat(context.getBean(RateLimitExceededExceptionHandler.class)).isSameAs(own);
            });
  }

  @Test
  void aHandlerBeanDeclaredAfterTheAspectIsEnabledReplacesTheDefault() {
    runner
        .withBean(
            FluxgateRateLimitHandler.class, () -> answering(RateLimitResponse.rejected(1000L)))
        .withUserConfiguration(LaterHandlerConfig.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(RateLimitExceededExceptionHandler.class);
              assertThat(context).hasBean("ownHandler");
            });
  }

  @Test
  void notRegisteredWithoutTheAspect() {
    new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(FluxgateAopExceptionHandlerAutoConfiguration.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(RateLimitExceededExceptionHandler.class);
            });
  }

  @Test
  void notRegisteredOutsideAServletApplication() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                FluxgateAopAutoConfiguration.class,
                FluxgateAopExceptionHandlerAutoConfiguration.class))
        .withUserConfiguration(NonWebApp.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(RateLimitExceededExceptionHandler.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableFluxgateAspect
  static class NonWebApp {}
}
