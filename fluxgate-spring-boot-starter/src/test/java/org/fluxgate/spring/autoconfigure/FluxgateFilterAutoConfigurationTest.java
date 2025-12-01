package org.fluxgate.spring.autoconfigure;

import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.filter.FluxgateRateLimitFilter;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link FluxgateFilterAutoConfiguration}.
 *
 * Tests the conditional behavior of Filter auto-configuration:
 * - Requires fluxgate.ratelimit.filter-enabled=true
 * - Requires RateLimiter bean to be present
 * - Requires servlet web application context
 * - Optional: RateLimitRuleSetProvider bean
 */
class FluxgateFilterAutoConfigurationTest {

    @Configuration
    @EnableConfigurationProperties(FluxgateProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner nonWebContextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FluxgateFilterAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FluxgateFilterAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldNotCreateFilterWhenDisabled() {
        webContextRunner
                .withPropertyValues("fluxgate.ratelimit.filter-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(FluxgateRateLimitFilter.class);
                    assertThat(context).doesNotHaveBean("fluxgateRateLimitFilterRegistration");
                });
    }

    @Test
    void shouldNotCreateFilterByDefault() {
        // Default: fluxgate.ratelimit.filter-enabled is false
        webContextRunner
                .run(context -> {
                    assertThat(context).doesNotHaveBean(FluxgateRateLimitFilter.class);
                });
    }

    @Test
    void shouldNotCreateFilterWithoutRateLimiter() {
        // Filter enabled but no RateLimiter bean
        webContextRunner
                .withPropertyValues("fluxgate.ratelimit.filter-enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(FluxgateRateLimitFilter.class);
                });
    }

    @Test
    void shouldNotCreateFilterInNonWebContext() {
        // Filter enabled with RateLimiter but not a web context
        RateLimiter mockRateLimiter = Mockito.mock(RateLimiter.class);

        nonWebContextRunner
                .withPropertyValues("fluxgate.ratelimit.filter-enabled=true")
                .withBean(RateLimiter.class, () -> mockRateLimiter)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(FluxgateRateLimitFilter.class);
                });
    }

    @Test
    void shouldCreateFilterWhenEnabledWithRateLimiter() {
        RateLimiter mockRateLimiter = Mockito.mock(RateLimiter.class);
        RateLimitRuleSetProvider mockProvider = Mockito.mock(RateLimitRuleSetProvider.class);
        Mockito.when(mockProvider.findById(Mockito.anyString())).thenReturn(Optional.empty());

        webContextRunner
                .withPropertyValues(
                        "fluxgate.ratelimit.filter-enabled=true",
                        "fluxgate.ratelimit.default-rule-set-id=test-rules"
                )
                .withBean(RateLimiter.class, () -> mockRateLimiter)
                .withBean(RateLimitRuleSetProvider.class, () -> mockProvider)
                .run(context -> {
                    assertThat(context).hasSingleBean(FluxgateRateLimitFilter.class);
                    assertThat(context).hasBean("fluxgateRateLimitFilterRegistration");
                });
    }

    @Test
    void shouldCreateFilterWithoutRuleSetProvider() {
        // RuleSetProvider is optional - filter should still be created
        RateLimiter mockRateLimiter = Mockito.mock(RateLimiter.class);

        webContextRunner
                .withPropertyValues("fluxgate.ratelimit.filter-enabled=true")
                .withBean(RateLimiter.class, () -> mockRateLimiter)
                .run(context -> {
                    assertThat(context).hasSingleBean(FluxgateRateLimitFilter.class);
                });
    }

    @Test
    void shouldConfigureFilterOrder() {
        RateLimiter mockRateLimiter = Mockito.mock(RateLimiter.class);

        webContextRunner
                .withPropertyValues(
                        "fluxgate.ratelimit.filter-enabled=true",
                        "fluxgate.ratelimit.filter-order=-100"
                )
                .withBean(RateLimiter.class, () -> mockRateLimiter)
                .run(context -> {
                    assertThat(context).hasBean("fluxgateRateLimitFilterRegistration");

                    @SuppressWarnings("unchecked")
                    FilterRegistrationBean<FluxgateRateLimitFilter> registration =
                            context.getBean("fluxgateRateLimitFilterRegistration", FilterRegistrationBean.class);

                    assertThat(registration.getOrder()).isEqualTo(-100);
                });
    }

    @Test
    void shouldConfigureIncludePatterns() {
        RateLimiter mockRateLimiter = Mockito.mock(RateLimiter.class);

        webContextRunner
                .withPropertyValues(
                        "fluxgate.ratelimit.filter-enabled=true",
                        "fluxgate.ratelimit.include-patterns=/api/*,/v1/*"
                )
                .withBean(RateLimiter.class, () -> mockRateLimiter)
                .run(context -> {
                    assertThat(context).hasSingleBean(FluxgateProperties.class);
                    FluxgateProperties props = context.getBean(FluxgateProperties.class);
                    assertThat(props.getRatelimit().getIncludePatterns())
                            .containsExactly("/api/*", "/v1/*");
                });
    }

    @Test
    void shouldConfigureExcludePatterns() {
        RateLimiter mockRateLimiter = Mockito.mock(RateLimiter.class);

        webContextRunner
                .withPropertyValues(
                        "fluxgate.ratelimit.filter-enabled=true",
                        "fluxgate.ratelimit.exclude-patterns=/health,/actuator/*,/metrics"
                )
                .withBean(RateLimiter.class, () -> mockRateLimiter)
                .run(context -> {
                    assertThat(context).hasSingleBean(FluxgateProperties.class);
                    FluxgateProperties props = context.getBean(FluxgateProperties.class);
                    assertThat(props.getRatelimit().getExcludePatterns())
                            .containsExactly("/health", "/actuator/*", "/metrics");
                });
    }

    @Test
    void shouldConfigureClientIpHeader() {
        RateLimiter mockRateLimiter = Mockito.mock(RateLimiter.class);

        webContextRunner
                .withPropertyValues(
                        "fluxgate.ratelimit.filter-enabled=true",
                        "fluxgate.ratelimit.client-ip-header=X-Real-IP",
                        "fluxgate.ratelimit.trust-client-ip-header=false"
                )
                .withBean(RateLimiter.class, () -> mockRateLimiter)
                .run(context -> {
                    assertThat(context).hasSingleBean(FluxgateProperties.class);
                    FluxgateProperties props = context.getBean(FluxgateProperties.class);
                    assertThat(props.getRatelimit().getClientIpHeader()).isEqualTo("X-Real-IP");
                    assertThat(props.getRatelimit().isTrustClientIpHeader()).isFalse();
                });
    }

    @Test
    void shouldConfigureRateLimitHeaders() {
        RateLimiter mockRateLimiter = Mockito.mock(RateLimiter.class);

        webContextRunner
                .withPropertyValues(
                        "fluxgate.ratelimit.filter-enabled=true",
                        "fluxgate.ratelimit.include-headers=false"
                )
                .withBean(RateLimiter.class, () -> mockRateLimiter)
                .run(context -> {
                    assertThat(context).hasSingleBean(FluxgateProperties.class);
                    FluxgateProperties props = context.getBean(FluxgateProperties.class);
                    assertThat(props.getRatelimit().isIncludeHeaders()).isFalse();
                });
    }

    @Test
    void shouldUseDefaultFilterOrder() {
        RateLimiter mockRateLimiter = Mockito.mock(RateLimiter.class);

        webContextRunner
                .withPropertyValues("fluxgate.ratelimit.filter-enabled=true")
                .withBean(RateLimiter.class, () -> mockRateLimiter)
                .run(context -> {
                    assertThat(context).hasBean("fluxgateRateLimitFilterRegistration");

                    @SuppressWarnings("unchecked")
                    FilterRegistrationBean<FluxgateRateLimitFilter> registration =
                            context.getBean("fluxgateRateLimitFilterRegistration", FilterRegistrationBean.class);

                    // Default order is Integer.MIN_VALUE + 100
                    assertThat(registration.getOrder()).isEqualTo(Integer.MIN_VALUE + 100);
                });
    }
}
