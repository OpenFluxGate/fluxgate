package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.exception.MissingConfigurationException;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.annotation.EnableFluxgateAspect;
import org.fluxgate.spring.aop.RateLimitAspect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Tests for {@link FluxgateAopAutoConfiguration}.
 *
 * <p>The aspect used to be gated by {@code @ConditionalOnBean(FluxgateRateLimitHandler.class)} on
 * an {@code @Import}ed configuration, which evaluated before the user's handler bean was registered
 * and silently dropped the aspect. These tests pin the fixed behaviour.
 */
@DisplayName("FluxgateAopAutoConfiguration Tests")
class FluxgateAopAutoConfigurationTest {

  private final WebApplicationContextRunner webContextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(FluxgateAopAutoConfiguration.class));

  private final ApplicationContextRunner nonWebContextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(FluxgateAopAutoConfiguration.class));

  @Configuration
  @EnableFluxgateAspect
  static class EnabledConfig {}

  @Component
  static class TestHandler implements FluxgateRateLimitHandler {
    @Override
    public RateLimitResponse tryConsume(RequestContext context, String ruleSetId) {
      return RateLimitResponse.allowed(100, 0);
    }
  }

  @Nested
  @DisplayName("Registration Tests")
  class RegistrationTests {

    @Test
    @DisplayName("should register the aspect even when the handler bean is declared by the user")
    void shouldRegisterAspectWithUserHandler() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(TestHandler.class)
          .run(
              context -> {
                assertThat(context).hasSingleBean(RateLimitAspect.class);
                assertThat(context).hasSingleBean(TestHandler.class);
              });
    }

    @Test
    @DisplayName("should register the aspect when no handler bean exists at all")
    void shouldRegisterAspectWithoutHandler() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .run(context -> assertThat(context).hasSingleBean(RateLimitAspect.class));
    }

    @Test
    @DisplayName("should register the aspect in a non-web application")
    void shouldRegisterAspectInNonWebApplication() {
      nonWebContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(TestHandler.class)
          .run(context -> assertThat(context).hasSingleBean(RateLimitAspect.class));
    }

    @Test
    @DisplayName("should cap @RateLimit waits with wait-for-refill.max-wait-time-ms")
    void shouldWireTheGlobalMaxWaitIntoTheAspect() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(TestHandler.class)
          .withPropertyValues("fluxgate.ratelimit.wait-for-refill.max-wait-time-ms=1500")
          .run(
              context ->
                  assertThat(context.getBean(RateLimitAspect.class).getMaxWaitTimeMs())
                      .isEqualTo(1500L));
    }

    @Test
    @DisplayName("should register no aspect when fluxgate.ratelimit.enabled=false")
    void shouldRegisterNoAspectWhenDisabled() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(TestHandler.class)
          .withPropertyValues("fluxgate.ratelimit.enabled=false")
          .run(context -> assertThat(context).doesNotHaveBean(RateLimitAspect.class));
    }

    @Test
    @DisplayName("should not replace a user supplied aspect")
    void shouldNotReplaceUserAspect() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(
              "rateLimitAspect",
              RateLimitAspect.class,
              () -> new RateLimitAspect(FluxgateRateLimitHandler.ALLOW_ALL, null))
          .run(context -> assertThat(context.getBeansOfType(RateLimitAspect.class)).hasSize(1));
    }
  }

  // ===== N4: resolveHandler diagnostic message tests =====

  /** No-op {@link RateLimiter} used to prove a RateLimiter bean exists. */
  @Component
  static class StubRateLimiter implements RateLimiter {
    @Override
    public RateLimitResult tryConsume(
        RequestContext ctx, org.fluxgate.core.ratelimiter.RateLimitRuleSet ruleSet, long permits) {
      return RateLimitResult.allowedWithoutRule();
    }
  }

  /** No-op {@link RateLimitRuleSetProvider} used to prove a rule-set provider bean exists. */
  @Component
  static class StubRuleSetProvider implements RateLimitRuleSetProvider {
    @Override
    public java.util.Optional<org.fluxgate.core.ratelimiter.RateLimitRuleSet> findById(
        String ruleSetId) {
      return java.util.Optional.empty();
    }
  }

  @Nested
  @DisplayName("N4: resolveHandler emits cause-specific diagnostics")
  class HandlerDiagnosticsTests {

    @Test
    @DisplayName(
        "aspect loads with ALLOW_ALL when neither RateLimiter nor RuleSetProvider present"
            + " (failure-behavior=ALLOW)")
    void neitherPresentAllowBehavior() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withPropertyValues("fluxgate.ratelimit.failure-behavior=ALLOW")
          .run(context -> assertThat(context).hasSingleBean(RateLimitAspect.class));
    }

    @Test
    @DisplayName(
        "aspect loads with fail-closed when neither RateLimiter nor RuleSetProvider present"
            + " (failure-behavior=DENY)")
    void neitherPresentDenyBehavior() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withPropertyValues("fluxgate.ratelimit.failure-behavior=DENY")
          .run(context -> assertThat(context).hasSingleBean(RateLimitAspect.class));
    }

    @Test
    @DisplayName("aspect loads when RateLimiter bean exists but no RateLimitRuleSetProvider")
    void rateLimiterPresentButNoRuleSetProvider() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(StubRateLimiter.class)
          .run(context -> assertThat(context).hasSingleBean(RateLimitAspect.class));
    }

    @Test
    @DisplayName("aspect loads when RateLimitRuleSetProvider bean exists but no RateLimiter")
    void ruleSetProviderPresentButNoRateLimiter() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(StubRuleSetProvider.class)
          .run(context -> assertThat(context).hasSingleBean(RateLimitAspect.class));
    }
  }

  @Nested
  @DisplayName("fail-on-missing-handler")
  @ExtendWith(OutputCaptureExtension.class)
  class FailOnMissingHandlerTests {

    /** The starter's own limiter wiring with no rule source: the default dependency setup. */
    private final ApplicationContextRunner defaultRunner =
        new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    FluxgateResilienceAutoConfiguration.class,
                    FluxgateRedisAutoConfiguration.class,
                    FluxgateRateLimiterAutoConfiguration.class,
                    FluxgateAopAutoConfiguration.class))
            .withUserConfiguration(EnabledConfig.class);

    @Test
    @DisplayName("should refuse to start the aspect with no handler when the property is true")
    void shouldFailStartupWithDefaultConfiguration() {
      defaultRunner
          .withPropertyValues("fluxgate.ratelimit.fail-on-missing-handler=true")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(MissingConfigurationException.class)
                    .hasMessageContaining("fluxgate.ratelimit.fail-on-missing-handler")
                    .hasMessageContaining("no RateLimitRuleSetProvider")
                    .hasMessageContaining(
                        "Enable fluxgate.mongo, declare fluxgate.ratelimit.rule-sets, define a"
                            + " RateLimitRuleSetProvider bean, or supply your own"
                            + " FluxgateRateLimitHandler")
                    .hasMessageNotContaining("delegateRuleSetProvider");
              });
    }

    @Test
    @DisplayName("should refuse to start when neither a limiter nor a rule source exists")
    void shouldFailStartupWithNothingAtAll() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withPropertyValues("fluxgate.ratelimit.fail-on-missing-handler=true")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(MissingConfigurationException.class)
                    .hasMessageContaining("neither a RateLimiter nor a RateLimitRuleSetProvider");
              });
    }

    @Test
    @DisplayName("should start and only warn when the property is false (the default)")
    void shouldWarnAndStartWhenFalse(CapturedOutput output) {
      defaultRunner
          .withPropertyValues("fluxgate.ratelimit.fail-on-missing-handler=false")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(RateLimitAspect.class);
                assertThat(output)
                    .contains(
                        "No FluxgateRateLimitHandler available (a RateLimiter bean exists but no"
                            + " RateLimitRuleSetProvider)");
              });
    }

    @Test
    @DisplayName("should start when the property is true and a handler exists")
    void shouldStartWhenAHandlerExists() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(TestHandler.class)
          .withPropertyValues("fluxgate.ratelimit.fail-on-missing-handler=true")
          .run(context -> assertThat(context).hasSingleBean(RateLimitAspect.class));
    }
  }
}
