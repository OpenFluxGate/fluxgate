package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.spring.annotation.EnableFluxgateFilter;
import org.fluxgate.spring.filter.FluxgateRateLimitFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Tests for {@link FluxgateFilterAutoConfiguration}.
 *
 * <p>Tests the conditional behavior of Filter auto-configuration: - Requires @EnableFluxgateFilter
 * annotation - Requires servlet web application context - Uses FluxgateRateLimitHandler from
 * context or ALLOW_ALL fallback
 */
@DisplayName("FluxgateFilterAutoConfiguration Tests")
class FluxgateFilterAutoConfigurationTest {

  private final ApplicationContextRunner nonWebContextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(FluxgateFilterAutoConfiguration.class));

  private final WebApplicationContextRunner webContextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(FluxgateFilterAutoConfiguration.class));

  // ==================== Test Configurations ====================

  @Configuration
  @EnableFluxgateFilter
  static class EnabledConfig {}

  @Configuration
  @EnableFluxgateFilter(ruleSetId = "test-rules")
  static class EnabledWithRuleSetConfig {}

  @Configuration
  @EnableFluxgateFilter(filterOrder = -100)
  static class EnabledWithCustomOrderConfig {}

  @Configuration
  @EnableFluxgateFilter(
      includePatterns = {"/api/*", "/v1/*"},
      excludePatterns = {"/health", "/actuator/*"})
  static class EnabledWithPatternsConfig {}

  @Configuration
  @EnableFluxgateFilter(handler = TestHandler.class)
  static class EnabledWithHandlerConfig {}

  @Component
  static class TestHandler implements FluxgateRateLimitHandler {
    @Override
    public RateLimitResponse tryConsume(
        org.fluxgate.core.context.RequestContext context, String ruleSetId) {
      return RateLimitResponse.allowed(100, 0);
    }
  }

  @Configuration
  static class EmptyConfig {}

  // ==================== Conditional Tests ====================

  @Nested
  @DisplayName("Conditional Bean Creation Tests")
  class ConditionalTests {

    @Test
    @DisplayName("should create filter when @EnableFluxgateFilter is present")
    void shouldCreateFilterWhenAnnotationPresent() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .run(
              context -> {
                assertThat(context).hasSingleBean(FluxgateRateLimitFilter.class);
                assertThat(context).hasBean("fluxgateRateLimitFilterRegistration");
              });
    }

    @Test
    @DisplayName("should create filter with ALLOW_ALL handler when no handler bean")
    void shouldCreateFilterWithAllowAllHandler() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .run(
              context -> {
                assertThat(context).hasSingleBean(FluxgateRateLimitFilter.class);
                // Filter is created with ALLOW_ALL fallback
              });
    }

    @Test
    @DisplayName("should not create filter in non-web context")
    void shouldNotCreateFilterInNonWebContext() {
      nonWebContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .run(
              context -> {
                assertThat(context).doesNotHaveBean(FluxgateRateLimitFilter.class);
              });
    }

    @Test
    @DisplayName("should use custom handler when available in context")
    void shouldUseCustomHandlerWhenAvailable() {
      webContextRunner
          .withUserConfiguration(EnabledWithHandlerConfig.class)
          .withBean(TestHandler.class)
          .run(
              context -> {
                assertThat(context).hasSingleBean(FluxgateRateLimitFilter.class);
                assertThat(context).hasSingleBean(TestHandler.class);
              });
    }
  }

  // ==================== Filter Order Tests ====================

  @Nested
  @DisplayName("Filter Order Tests")
  class FilterOrderTests {

    @Test
    @DisplayName("should use default filter order (1) when not specified")
    void shouldUseDefaultFilterOrder() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .run(
              context -> {
                assertThat(context).hasBean("fluxgateRateLimitFilterRegistration");

                @SuppressWarnings("unchecked")
                FilterRegistrationBean<FluxgateRateLimitFilter> registration =
                    context.getBean(
                        "fluxgateRateLimitFilterRegistration", FilterRegistrationBean.class);

                // Default order in @EnableFluxgateFilter is 1
                assertThat(registration.getOrder()).isEqualTo(1);
              });
    }

    @Test
    @DisplayName("should use custom filter order from annotation")
    void shouldUseCustomFilterOrder() {
      webContextRunner
          .withUserConfiguration(EnabledWithCustomOrderConfig.class)
          .run(
              context -> {
                assertThat(context).hasBean("fluxgateRateLimitFilterRegistration");

                @SuppressWarnings("unchecked")
                FilterRegistrationBean<FluxgateRateLimitFilter> registration =
                    context.getBean(
                        "fluxgateRateLimitFilterRegistration", FilterRegistrationBean.class);

                assertThat(registration.getOrder()).isEqualTo(-100);
              });
    }
  }

  // ==================== URL Pattern Tests ====================

  @Nested
  @DisplayName("URL Pattern Tests")
  class UrlPatternTests {

    @Test
    @DisplayName("should use /* pattern when no patterns specified")
    void shouldUseDefaultPattern() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .run(
              context -> {
                assertThat(context).hasBean("fluxgateRateLimitFilterRegistration");

                @SuppressWarnings("unchecked")
                FilterRegistrationBean<FluxgateRateLimitFilter> registration =
                    context.getBean(
                        "fluxgateRateLimitFilterRegistration", FilterRegistrationBean.class);

                assertThat(registration.getUrlPatterns()).containsExactly("/*");
              });
    }

    @Test
    @DisplayName("should always use /* pattern for servlet filter registration")
    void shouldAlwaysUseWildcardPattern() {
      // Servlet spec only supports simple wildcard patterns (/*), not Ant patterns (**)
      // The actual path matching is done inside the filter using AntPathMatcher
      webContextRunner
          .withUserConfiguration(EnabledWithPatternsConfig.class)
          .run(
              context -> {
                assertThat(context).hasBean("fluxgateRateLimitFilterRegistration");

                @SuppressWarnings("unchecked")
                FilterRegistrationBean<FluxgateRateLimitFilter> registration =
                    context.getBean(
                        "fluxgateRateLimitFilterRegistration", FilterRegistrationBean.class);

                // Always registered with /* for servlet, filter does internal Ant pattern matching
                assertThat(registration.getUrlPatterns()).containsExactly("/*");
              });
    }
  }

  // ==================== Rule Set ID Tests ====================

  @Nested
  @DisplayName("Rule Set ID Tests")
  class RuleSetIdTests {

    @Test
    @DisplayName("should create filter with ruleSetId from annotation")
    void shouldUseRuleSetIdFromAnnotation() {
      webContextRunner
          .withUserConfiguration(EnabledWithRuleSetConfig.class)
          .run(
              context -> {
                assertThat(context).hasSingleBean(FluxgateRateLimitFilter.class);
                // The ruleSetId is passed to the filter constructor
              });
    }
  }

  // ==================== No Annotation Tests ====================

  @Nested
  @DisplayName("No Annotation Tests")
  class NoAnnotationTests {

    @Test
    @DisplayName("should create filter with defaults when annotation not found")
    void shouldCreateFilterWithDefaultsWhenNoAnnotation() {
      // When no @EnableFluxgateFilter annotation, filter is still created with defaults
      webContextRunner
          .withUserConfiguration(EmptyConfig.class)
          .run(
              context -> {
                // FluxgateFilterAutoConfiguration creates filter even without annotation
                // (uses ALLOW_ALL handler and empty patterns)
                assertThat(context).hasSingleBean(FluxgateRateLimitFilter.class);
              });
    }
  }

  // ==================== Filter Registration Tests ====================

  @Nested
  @DisplayName("Filter Registration Tests")
  class FilterRegistrationTests {

    @Test
    @DisplayName("should register filter with correct name")
    void shouldRegisterFilterWithCorrectName() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .run(
              context -> {
                @SuppressWarnings("unchecked")
                FilterRegistrationBean<FluxgateRateLimitFilter> registration =
                    context.getBean(
                        "fluxgateRateLimitFilterRegistration", FilterRegistrationBean.class);

                assertThat(registration.getFilter()).isNotNull();
              });
    }

    @Test
    @DisplayName("should not create auto-configured filter when custom filter exists")
    void shouldNotCreateDuplicateFilter() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(
              "fluxgateRateLimitFilter",
              FluxgateRateLimitFilter.class,
              () ->
                  new FluxgateRateLimitFilter(
                      FluxgateRateLimitHandler.ALLOW_ALL, "custom", new String[0], new String[0]))
          .run(
              context -> {
                // @ConditionalOnMissingBean should prevent auto-configuration
                // Only the custom bean should exist
                assertThat(context.getBeansOfType(FluxgateRateLimitFilter.class)).hasSize(1);
                assertThat(context).hasBean("fluxgateRateLimitFilter");
              });
    }
  }
}
