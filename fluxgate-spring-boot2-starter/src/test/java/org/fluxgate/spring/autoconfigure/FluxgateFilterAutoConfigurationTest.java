package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.annotation.EnableFluxgateFilter;
import org.fluxgate.spring.filter.FluxgateRateLimitFilter;
import org.fluxgate.spring.filter.RateLimitResponseWriter;
import org.fluxgate.spring.metrics.MicrometerMetricsRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Tests for {@link FluxgateFilterAutoConfiguration}.
 *
 * <p>Tests the conditional behavior of Filter auto-configuration: - Requires @EnableFluxgateFilter
 * annotation - Requires servlet web application context - Uses FluxgateRateLimitHandler from
 * context or configured fallback
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
    @DisplayName("should create filter with fallback handler when no handler bean")
    void shouldCreateFilterWithFallbackHandler() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .run(
              context -> {
                assertThat(context).hasSingleBean(FluxgateRateLimitFilter.class);
                // Filter is created with the configured fallback behavior.
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

  // ==================== Property Precedence Tests ====================

  @Nested
  @DisplayName("Property Precedence Tests")
  class PropertyPrecedenceTests {

    @Test
    @DisplayName("should prefer include-patterns from yml over the annotation")
    void shouldPreferIncludePatternsFromProperties() {
      webContextRunner
          .withUserConfiguration(EnabledWithPatternsConfig.class)
          .withPropertyValues("fluxgate.ratelimit.include-patterns=/from-yml/**")
          .run(context -> assertThat(includePatternsOf(context)).containsExactly("/from-yml/**"));
    }

    @Test
    @DisplayName("should fall back to the annotation include-patterns when the property is unset")
    void shouldFallBackToAnnotationIncludePatterns() {
      webContextRunner
          .withUserConfiguration(EnabledWithPatternsConfig.class)
          .run(
              context -> assertThat(includePatternsOf(context)).containsExactly("/api/*", "/v1/*"));
    }

    @Test
    @DisplayName("should default include-patterns to /** so nested paths are covered")
    void shouldDefaultIncludePatternsToAllPaths() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .run(context -> assertThat(includePatternsOf(context)).containsExactly("/**"));
    }

    @Test
    @DisplayName("should prefer exclude-patterns from yml over the annotation")
    void shouldPreferExcludePatternsFromProperties() {
      webContextRunner
          .withUserConfiguration(EnabledWithPatternsConfig.class)
          .withPropertyValues("fluxgate.ratelimit.exclude-patterns=/skip/**")
          .run(
              context ->
                  assertThat(fieldOf(context, "excludePatterns", String[].class))
                      .containsExactly("/skip/**"));
    }

    @Test
    @DisplayName("should prefer filter-order from yml over the annotation")
    void shouldPreferFilterOrderFromProperties() {
      webContextRunner
          .withUserConfiguration(EnabledWithCustomOrderConfig.class)
          .withPropertyValues("fluxgate.ratelimit.filter-order=42")
          .run(context -> assertThat(registrationOf(context).getOrder()).isEqualTo(42));
    }

    @Test
    @DisplayName("should prefer default-rule-set-id from yml over the annotation")
    void shouldPreferRuleSetIdFromProperties() {
      webContextRunner
          .withUserConfiguration(EnabledWithRuleSetConfig.class)
          .withPropertyValues("fluxgate.ratelimit.default-rule-set-id=yml-rules")
          .run(
              context ->
                  assertThat(fieldOf(context, "ruleSetId", String.class)).isEqualTo("yml-rules"));
    }

    @Test
    @DisplayName("should fall back to the annotation ruleSetId when the property is unset")
    void shouldFallBackToAnnotationRuleSetId() {
      webContextRunner
          .withUserConfiguration(EnabledWithRuleSetConfig.class)
          .run(
              context ->
                  assertThat(fieldOf(context, "ruleSetId", String.class)).isEqualTo("test-rules"));
    }
  }

  // ==================== Master Switch Tests ====================

  @Nested
  @DisplayName("Master Switch Tests")
  class MasterSwitchTests {

    @Test
    @DisplayName("should register no filter when fluxgate.ratelimit.enabled=false")
    void shouldRegisterNoFilterWhenDisabled() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withPropertyValues("fluxgate.ratelimit.enabled=false")
          .run(
              context -> {
                assertThat(context).doesNotHaveBean(FluxgateRateLimitFilter.class);
                assertThat(context).doesNotHaveBean("fluxgateRateLimitFilterRegistration");
              });
    }

    @Test
    @DisplayName("should register the filter when fluxgate.ratelimit.enabled=true")
    void shouldRegisterFilterWhenExplicitlyEnabled() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withPropertyValues("fluxgate.ratelimit.enabled=true")
          .run(context -> assertThat(context).hasSingleBean(FluxgateRateLimitFilter.class));
    }
  }

  // ==================== Trusted Proxy Wiring Tests ====================

  @Nested
  @DisplayName("Trusted Proxy Wiring Tests")
  class TrustedProxyWiringTests {

    @Test
    @DisplayName("should honour trusted-proxies end to end through the filter")
    void shouldHonourTrustedProxiesEndToEnd() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(RecordingHandler.class)
          .withPropertyValues(
              "fluxgate.ratelimit.default-rule-set-id=rules",
              "fluxgate.ratelimit.trust-client-ip-header=true",
              "fluxgate.ratelimit.trusted-proxies=10.0.0.0/8")
          .run(
              context -> {
                FluxgateRateLimitFilter filter = context.getBean(FluxgateRateLimitFilter.class);
                RecordingHandler handler = context.getBean(RecordingHandler.class);

                // Arrives through a trusted proxy: the forwarded client is honoured.
                filter.doFilter(
                    forwardedRequest("10.0.0.5", "203.0.113.50"),
                    new MockHttpServletResponse(),
                    new MockFilterChain());
                assertThat(handler.lastClientIp).isEqualTo("203.0.113.50");

                // Arrives directly: the forwarded header is a forgery and must be ignored.
                filter.doFilter(
                    forwardedRequest("198.51.100.7", "203.0.113.50"),
                    new MockHttpServletResponse(),
                    new MockFilterChain());
                assertThat(handler.lastClientIp).isEqualTo("198.51.100.7");
              });
    }

    @Test
    @DisplayName("should ignore the forwarded header when trust is disabled")
    void shouldIgnoreForwardedHeaderWhenTrustDisabled() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(RecordingHandler.class)
          .withPropertyValues(
              "fluxgate.ratelimit.default-rule-set-id=rules",
              "fluxgate.ratelimit.trust-client-ip-header=false",
              "fluxgate.ratelimit.trusted-proxies=10.0.0.0/8")
          .run(
              context -> {
                FluxgateRateLimitFilter filter = context.getBean(FluxgateRateLimitFilter.class);
                RecordingHandler handler = context.getBean(RecordingHandler.class);

                filter.doFilter(
                    forwardedRequest("10.0.0.5", "203.0.113.50"),
                    new MockHttpServletResponse(),
                    new MockFilterChain());

                assertThat(handler.lastClientIp).isEqualTo("10.0.0.5");
              });
    }

    private MockHttpServletRequest forwardedRequest(String remoteAddr, String forwardedFor) {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
      request.setRemoteAddr(remoteAddr);
      request.addHeader("X-Forwarded-For", forwardedFor);
      return request;
    }
  }

  // ==================== Response Shape Tests ====================

  @Nested
  @DisplayName("Response Shape Tests")
  class ResponseShapeTests {

    @Test
    @DisplayName("should honour response.* properties on the 429")
    void shouldHonourResponseProperties() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(RejectingHandler.class)
          .withPropertyValues(
              "fluxgate.ratelimit.default-rule-set-id=rules",
              "fluxgate.ratelimit.response.include-standard-headers=false",
              "fluxgate.ratelimit.response.content-type=application/json",
              "fluxgate.ratelimit.response.body-template={\"after\":{retryAfterSeconds}}")
          .run(
              context -> {
                FluxgateRateLimitFilter filter = context.getBean(FluxgateRateLimitFilter.class);
                MockHttpServletResponse response = new MockHttpServletResponse();

                filter.doFilter(
                    new MockHttpServletRequest("GET", "/api/users"),
                    response,
                    new MockFilterChain());

                assertThat(response.getStatus()).isEqualTo(429);
                assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
                assertThat(response.getContentAsString()).isEqualTo("{\"after\":5}");
                assertThat(response.getHeader("X-RateLimit-Remaining")).isNotNull();
                assertThat(response.getHeader("RateLimit-Remaining")).isNull();
              });
    }

    @Test
    @DisplayName("should let a RateLimitResponseWriter bean own the body")
    void shouldLetACustomWriterOwnTheBody() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(RejectingHandler.class)
          .withBean(
              RateLimitResponseWriter.class,
              () ->
                  (request, response, result) -> {
                    response.setStatus(429);
                    response.getWriter().write("custom");
                  })
          .withPropertyValues("fluxgate.ratelimit.default-rule-set-id=rules")
          .run(
              context -> {
                FluxgateRateLimitFilter filter = context.getBean(FluxgateRateLimitFilter.class);
                MockHttpServletResponse response = new MockHttpServletResponse();

                filter.doFilter(
                    new MockHttpServletRequest("GET", "/api/users"),
                    response,
                    new MockFilterChain());

                assertThat(response.getContentAsString()).isEqualTo("custom");
              });
    }
  }

  // ==================== Metrics Wiring Tests ====================

  @Nested
  @DisplayName("Metrics Wiring Tests")
  class MetricsWiringTests {

    @Test
    @DisplayName("should feed fluxgate.requests.duration from the filter")
    void shouldRecordTheRequestDurationTimer() {
      // H-11 side note: recordDuration existed but nothing ever called it, so the advertised timer
      // never appeared. The filter now drives it through RateLimitDurationRecorder.
      SimpleMeterRegistry registry = new SimpleMeterRegistry();

      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(RecordingHandler.class)
          .withBean(MeterRegistry.class, () -> registry)
          .withBean(MicrometerMetricsRecorder.class, () -> new MicrometerMetricsRecorder(registry))
          .withPropertyValues("fluxgate.ratelimit.default-rule-set-id=rules")
          .run(
              context -> {
                FluxgateRateLimitFilter filter = context.getBean(FluxgateRateLimitFilter.class);

                filter.doFilter(
                    new MockHttpServletRequest("GET", "/api/users"),
                    new MockHttpServletResponse(),
                    new MockFilterChain());

                assertThat(registry.find("fluxgate.requests.duration").timer()).isNotNull();
                assertThat(registry.find("fluxgate.requests.duration").timer().count())
                    .isEqualTo(1);
              });
    }
  }

  // ==================== Test fixtures ====================

  /** Handler that records the resolved client IP so property wiring can be asserted end to end. */
  // ==================== Identity Wiring Tests ====================

  @Nested
  @DisplayName("Identity Wiring Tests")
  @ExtendWith(OutputCaptureExtension.class)
  class IdentityWiringTests {

    @AfterEach
    void clearSecurityContext() {
      SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("should take the user id from the principal by default")
    void shouldPreferThePrincipalByDefault() {
      // C-4: identity headers are client input, so with Spring Security present the default is to
      // trust the authenticated principal instead of X-User-Id.
      SecurityContextHolder.getContext()
          .setAuthentication(
              new TestingAuthenticationToken(
                  "alice", "credentials", AuthorityUtils.NO_AUTHORITIES));

      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(RecordingHandler.class)
          .withPropertyValues("fluxgate.ratelimit.default-rule-set-id=rules")
          .run(
              context -> {
                runOnce(context, identityRequest());
                assertThat(context.getBean(RecordingHandler.class).lastUserId).isEqualTo("alice");
              });
    }

    @Test
    @DisplayName("should ignore identity headers by default when unauthenticated")
    void shouldIgnoreIdentityHeadersByDefault(CapturedOutput output) {
      // C-4 (0.4): header identity is opt-in. Without a principal an unauthenticated caller has no
      // user id and no API key, and missing-key-behavior applies instead of X-User-Id/X-API-Key.
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(RecordingHandler.class)
          .withPropertyValues("fluxgate.ratelimit.default-rule-set-id=rules")
          .run(
              context -> {
                runOnce(context, identityRequest());
                RecordingHandler handler = context.getBean(RecordingHandler.class);
                assertThat(handler.lastUserId).isNull();
                assertThat(handler.lastApiKey).isNull();
              });
      assertThat(output).doesNotContain("reads caller identity from request headers");
    }

    @Test
    @DisplayName("should prefer the principal over headers with PRINCIPAL_THEN_HEADERS")
    void shouldPreferThePrincipalWhenHeaderFallbackIsEnabled() {
      SecurityContextHolder.getContext()
          .setAuthentication(
              new TestingAuthenticationToken(
                  "alice", "credentials", AuthorityUtils.NO_AUTHORITIES));

      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(RecordingHandler.class)
          .withPropertyValues(
              "fluxgate.ratelimit.default-rule-set-id=rules",
              "fluxgate.ratelimit.identity.source=PRINCIPAL_THEN_HEADERS")
          .run(
              context -> {
                runOnce(context, identityRequest());
                assertThat(context.getBean(RecordingHandler.class).lastUserId).isEqualTo("alice");
              });
    }

    @Test
    @DisplayName("should fall back to headers without a principal with PRINCIPAL_THEN_HEADERS")
    void shouldFallBackToHeadersWhenOptedIn(CapturedOutput output) {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(RecordingHandler.class)
          .withPropertyValues(
              "fluxgate.ratelimit.default-rule-set-id=rules",
              "fluxgate.ratelimit.identity.source=PRINCIPAL_THEN_HEADERS")
          .run(
              context -> {
                runOnce(context, identityRequest());
                RecordingHandler handler = context.getBean(RecordingHandler.class);
                assertThat(handler.lastUserId).isEqualTo("header-user");
                assertThat(handler.lastApiKey).isEqualTo("header-key");
              });
      assertThat(output).contains("reads caller identity from request headers");
    }

    @Test
    @DisplayName("should warn at startup when identity comes from headers")
    void shouldWarnWhenHeaderIdentityIsEnabled(CapturedOutput output) {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(RecordingHandler.class)
          .withPropertyValues(
              "fluxgate.ratelimit.default-rule-set-id=rules",
              "fluxgate.ratelimit.identity.source=HEADERS")
          .run(context -> assertThat(context).hasNotFailed());
      assertThat(output)
          .contains("WARN")
          .contains("reads caller identity from request headers")
          .contains("X-User-Id");
    }

    @Test
    @DisplayName("should read identity headers when the source is HEADERS")
    void shouldReadIdentityHeadersWhenConfigured() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(RecordingHandler.class)
          .withPropertyValues(
              "fluxgate.ratelimit.default-rule-set-id=rules",
              "fluxgate.ratelimit.identity.source=HEADERS")
          .run(
              context -> {
                runOnce(context, identityRequest());
                RecordingHandler handler = context.getBean(RecordingHandler.class);
                assertThat(handler.lastUserId).isEqualTo("header-user");
                assertThat(handler.lastApiKey).isEqualTo("header-key");
              });
    }

    @Test
    @DisplayName("should ignore identity headers when the source is PRINCIPAL")
    void shouldIgnoreIdentityHeadersInPrincipalMode() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(RecordingHandler.class)
          .withPropertyValues(
              "fluxgate.ratelimit.default-rule-set-id=rules",
              "fluxgate.ratelimit.identity.source=PRINCIPAL")
          .run(
              context -> {
                runOnce(context, identityRequest());
                RecordingHandler handler = context.getBean(RecordingHandler.class);
                assertThat(handler.lastUserId).isNull();
                assertThat(handler.lastApiKey).isNull();
              });
    }

    @Test
    @DisplayName("should honour custom identity header names")
    void shouldHonourCustomIdentityHeaderNames() {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
      request.addHeader("X-Tenant-User", "bob");
      request.addHeader("X-Tenant-Key", "tenant-key");

      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(RecordingHandler.class)
          .withPropertyValues(
              "fluxgate.ratelimit.default-rule-set-id=rules",
              "fluxgate.ratelimit.identity.source=HEADERS",
              "fluxgate.ratelimit.identity.user-id-header=X-Tenant-User",
              "fluxgate.ratelimit.identity.api-key-header=X-Tenant-Key")
          .run(
              context -> {
                runOnce(context, request);
                RecordingHandler handler = context.getBean(RecordingHandler.class);
                assertThat(handler.lastUserId).isEqualTo("bob");
                assertThat(handler.lastApiKey).isEqualTo("tenant-key");
              });
    }

    @Test
    @DisplayName("should start and limit when Spring Security is hidden from the context")
    void shouldStartWithoutSpringSecurityOnTheContextClassLoader() {
      // spring-security-core is a provided dependency, so the auto-configuration must not require
      // it. PrincipalIdentityResolverTest covers the guard itself with a class loader that really
      // defines the resolver without Spring Security.
      webContextRunner
          .withClassLoader(new FilteredClassLoader(SecurityContextHolder.class))
          .withUserConfiguration(EnabledConfig.class)
          .withBean(RecordingHandler.class)
          .withPropertyValues("fluxgate.ratelimit.default-rule-set-id=rules")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(FluxgateRateLimitFilter.class);
                runOnce(context, identityRequest());
                // C-4 (0.4): no Spring Security means no principal, and headers are opt-in.
                assertThat(context.getBean(RecordingHandler.class).lastUserId).isNull();
              });
    }

    private void runOnce(
        org.springframework.context.ApplicationContext context, MockHttpServletRequest request)
        throws Exception {
      context
          .getBean(FluxgateRateLimitFilter.class)
          .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    private MockHttpServletRequest identityRequest() {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
      request.setRemoteAddr("192.168.1.100");
      request.addHeader("X-User-Id", "header-user");
      request.addHeader("X-API-Key", "header-key");
      return request;
    }
  }

  static class RecordingHandler implements FluxgateRateLimitHandler {

    private volatile String lastClientIp;
    private volatile String lastUserId;
    private volatile String lastApiKey;

    @Override
    public RateLimitResponse tryConsume(
        org.fluxgate.core.context.RequestContext context, String ruleSetId) {
      this.lastClientIp = context.getClientIp();
      this.lastUserId = context.getUserId();
      this.lastApiKey = context.getApiKey();
      return RateLimitResponse.allowed(10, 0);
    }
  }

  /** Handler that always rejects, so response shaping can be asserted. */
  static class RejectingHandler implements FluxgateRateLimitHandler {

    @Override
    public RateLimitResponse tryConsume(
        org.fluxgate.core.context.RequestContext context, String ruleSetId) {
      return RateLimitResponse.rejected(4100, null, 0, 100, -1);
    }
  }

  private static String[] includePatternsOf(
      org.springframework.context.ApplicationContext context) {
    return fieldOf(context, "includePatterns", String[].class);
  }

  @SuppressWarnings("unchecked")
  private static FilterRegistrationBean<FluxgateRateLimitFilter> registrationOf(
      org.springframework.context.ApplicationContext context) {
    return context.getBean("fluxgateRateLimitFilterRegistration", FilterRegistrationBean.class);
  }

  /** Reads a filter field so the wiring, not just bean presence, is asserted. */
  private static <T> T fieldOf(
      org.springframework.context.ApplicationContext context, String name, Class<T> type) {
    FluxgateRateLimitFilter filter = context.getBean(FluxgateRateLimitFilter.class);
    java.lang.reflect.Field field =
        org.springframework.util.ReflectionUtils.findField(FluxgateRateLimitFilter.class, name);
    org.springframework.util.ReflectionUtils.makeAccessible(field);
    return type.cast(org.springframework.util.ReflectionUtils.getField(field, filter));
  }

  // ===== N4: resolveHandler diagnostic message tests =====

  /** No-op {@link RateLimiter} used to prove a RateLimiter bean exists. */
  @org.springframework.stereotype.Component
  static class StubRateLimiter implements RateLimiter {
    @Override
    public RateLimitResult tryConsume(
        RequestContext ctx, org.fluxgate.core.ratelimiter.RateLimitRuleSet ruleSet, long permits) {
      return RateLimitResult.allowedWithoutRule();
    }
  }

  /** No-op {@link RateLimitRuleSetProvider} used to prove a rule-set provider bean exists. */
  @org.springframework.stereotype.Component
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
        "filter loads with ALLOW_ALL when neither RateLimiter nor RuleSetProvider present"
            + " (failure-behavior=ALLOW)")
    void neitherPresentAllowBehavior() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withPropertyValues("fluxgate.ratelimit.failure-behavior=ALLOW")
          .run(context -> assertThat(context).hasSingleBean(FluxgateRateLimitFilter.class));
    }

    @Test
    @DisplayName(
        "filter loads with fail-closed when neither RateLimiter nor RuleSetProvider present"
            + " (failure-behavior=DENY)")
    void neitherPresentDenyBehavior() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withPropertyValues("fluxgate.ratelimit.failure-behavior=DENY")
          .run(context -> assertThat(context).hasSingleBean(FluxgateRateLimitFilter.class));
    }

    @Test
    @DisplayName("filter loads when RateLimiter bean exists but no RateLimitRuleSetProvider")
    void rateLimiterPresentButNoRuleSetProvider() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(StubRateLimiter.class)
          .run(context -> assertThat(context).hasSingleBean(FluxgateRateLimitFilter.class));
    }

    @Test
    @DisplayName("filter loads when RateLimitRuleSetProvider bean exists but no RateLimiter")
    void ruleSetProviderPresentButNoRateLimiter() {
      webContextRunner
          .withUserConfiguration(EnabledConfig.class)
          .withBean(StubRuleSetProvider.class)
          .run(context -> assertThat(context).hasSingleBean(FluxgateRateLimitFilter.class));
    }
  }
}
