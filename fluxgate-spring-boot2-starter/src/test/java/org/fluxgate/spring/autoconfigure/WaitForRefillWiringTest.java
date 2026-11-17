package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.Semaphore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.spring.annotation.EnableFluxgateAspect;
import org.fluxgate.spring.annotation.EnableFluxgateFilter;
import org.fluxgate.spring.annotation.RateLimit;
import org.fluxgate.spring.aop.RateLimitAspect;
import org.fluxgate.spring.aop.RateLimitExceededException;
import org.fluxgate.spring.filter.FluxgateRateLimitFilter;
import org.fluxgate.spring.filter.FluxgateWaitPermits;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Item 11: the filter and the aspect share one wait semaphore, and {@code
 * fluxgate.ratelimit.wait-for-refill.enabled=false} also stops {@code @RateLimit(waitForRefill)}.
 */
class WaitForRefillWiringTest {

  private static final FluxgateRateLimitHandler HANDLER = mock(FluxgateRateLimitHandler.class);

  @Configuration(proxyBeanMethods = false)
  @EnableFluxgateFilter(ruleSetId = "rules")
  @EnableFluxgateAspect
  static class App {

    @Bean
    FluxgateRateLimitHandler handler() {
      return HANDLER;
    }
  }

  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(FluxgateRateLimiterAutoConfiguration.class))
          .withUserConfiguration(App.class)
          .withPropertyValues("fluxgate.ratelimit.mode=IN_MEMORY");

  @Test
  void filterAndAspectShareOneSemaphore() {
    runner
        .withPropertyValues("fluxgate.ratelimit.wait-for-refill.max-concurrent-waits=7")
        .run(
            context -> {
              Object filterSemaphore =
                  ReflectionTestUtils.getField(
                      context.getBean(FluxgateRateLimitFilter.class), "waitSemaphore");
              Object aspectSemaphore =
                  ReflectionTestUtils.getField(
                      context.getBean(RateLimitAspect.class), "waitSemaphore");

              assertThat(filterSemaphore).isSameAs(aspectSemaphore);
              assertThat(((Semaphore) filterSemaphore).availablePermits()).isEqualTo(7);
            });
  }

  /** An application component that needs a semaphore of its own, injected by type. */
  static class Downloads {

    final Semaphore slots;

    Downloads(Semaphore slots) {
      this.slots = slots;
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ApplicationSemaphore {

    @Bean
    Semaphore downloadSlots() {
      return new Semaphore(3);
    }

    @Bean
    Downloads downloads(Semaphore slots) {
      return new Downloads(slots);
    }
  }

  @Test
  void theSharedPermitsAreNotASemaphoreBean() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(FluxgateWaitPermits.class);
          assertThat(context.getBeansOfType(Semaphore.class)).isEmpty();
        });
  }

  @Test
  void anApplicationSemaphoreInjectedByTypeIsUnaffected() {
    runner
        .withUserConfiguration(ApplicationSemaphore.class)
        .withPropertyValues("fluxgate.ratelimit.wait-for-refill.max-concurrent-waits=7")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              Semaphore applicationSemaphore = context.getBean(Semaphore.class);
              assertThat(context.getBean(Downloads.class).slots).isSameAs(applicationSemaphore);
              assertThat(applicationSemaphore.availablePermits()).isEqualTo(3);

              Object filterSemaphore =
                  ReflectionTestUtils.getField(
                      context.getBean(FluxgateRateLimitFilter.class), "waitSemaphore");
              assertThat(filterSemaphore)
                  .isNotSameAs(applicationSemaphore)
                  .isSameAs(context.getBean(FluxgateWaitPermits.class).semaphore());
              assertThat(((Semaphore) filterSemaphore).availablePermits()).isEqualTo(7);
            });
  }

  private static ProceedingJoinPoint joinPoint() throws Throwable {
    ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
    Signature signature = mock(Signature.class);
    when(signature.getDeclaringType()).thenReturn(WaitForRefillWiringTest.class);
    when(signature.getName()).thenReturn("call");
    when(joinPoint.getSignature()).thenReturn(signature);
    when(joinPoint.proceed()).thenReturn("done");
    return joinPoint;
  }

  private static RateLimit waitingAnnotation() {
    RateLimit rateLimit = mock(RateLimit.class);
    when(rateLimit.ruleSetId()).thenReturn("rules");
    when(rateLimit.permits()).thenReturn(1L);
    when(rateLimit.waitForRefill()).thenReturn(true);
    when(rateLimit.maxWaitTimeMs()).thenReturn(5000L);
    return rateLimit;
  }

  private static void rejectOnceThenAllow() {
    reset(HANDLER);
    when(HANDLER.tryConsume(any(RequestContext.class), anyString()))
        .thenReturn(RateLimitResponse.rejected(5))
        .thenReturn(RateLimitResponse.allowed(1, 0));
  }

  @Test
  void globalKillSwitchStopsAnnotationWaits() {
    runner
        .withPropertyValues("fluxgate.ratelimit.wait-for-refill.enabled=false")
        .run(
            context -> {
              rejectOnceThenAllow();
              RateLimitAspect aspect = context.getBean(RateLimitAspect.class);
              ProceedingJoinPoint joinPoint = joinPoint();

              assertThatThrownBy(() -> aspect.aroundMethod(joinPoint, waitingAnnotation()))
                  .isInstanceOf(RateLimitExceededException.class);
              verify(HANDLER, times(1)).tryConsume(any(RequestContext.class), anyString());
            });
  }

  @Test
  void annotationWaitsWhenTheSwitchIsNotSet() {
    runner.run(
        context -> {
          rejectOnceThenAllow();
          RateLimitAspect aspect = context.getBean(RateLimitAspect.class);

          assertThat(aspect.aroundMethod(joinPoint(), waitingAnnotation())).isEqualTo("done");
          verify(HANDLER, times(2)).tryConsume(any(RequestContext.class), anyString());
        });
  }

  @Test
  void annotationWaitsWhenTheSwitchIsOn() {
    runner
        .withPropertyValues("fluxgate.ratelimit.wait-for-refill.enabled=true")
        .run(
            context -> {
              rejectOnceThenAllow();
              RateLimitAspect aspect = context.getBean(RateLimitAspect.class);

              assertThat(aspect.aroundMethod(joinPoint(), waitingAnnotation())).isEqualTo("done");
            });
  }
}
