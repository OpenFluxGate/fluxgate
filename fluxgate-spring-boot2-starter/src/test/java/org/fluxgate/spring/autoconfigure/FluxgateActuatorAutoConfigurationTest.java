package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.fluxgate.spring.actuator.FluxgateHealthIndicator;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Tests for {@link FluxgateActuatorAutoConfiguration}. */
class FluxgateActuatorAutoConfigurationTest {

  @Configuration
  @EnableConfigurationProperties(FluxgateProperties.class)
  static class TestConfig {}

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(FluxgateActuatorAutoConfiguration.class))
          .withUserConfiguration(TestConfig.class);

  @Test
  void shouldCreateHealthIndicatorByDefault() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(FluxgateHealthIndicator.class);
        });
  }

  @Test
  void shouldNotCreateHealthIndicatorWhenDisabled() {
    contextRunner
        .withPropertyValues("fluxgate.actuator.health.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(FluxgateHealthIndicator.class);
            });
  }

  @Test
  void shouldCreateHealthIndicatorWithoutOptionalCheckers() {
    contextRunner.run(
        context -> {
          FluxgateHealthIndicator indicator = context.getBean(FluxgateHealthIndicator.class);
          assertThat(indicator).isNotNull();
        });
  }

  // ===== R1: no replacement HttpCodeStatusMapper =====

  @Test
  void shouldNotRegisterAnHttpCodeStatusMapper() {
    contextRunner.run(context -> assertThat(context).doesNotHaveBean(HttpCodeStatusMapper.class));
  }

  @Test
  void shouldRespectUserDefinedHttpCodeStatusMapper() {
    contextRunner
        .withBean("customMapper", HttpCodeStatusMapper.class, () -> status -> 200)
        .run(
            context -> {
              assertThat(context.getBeansOfType(HttpCodeStatusMapper.class)).hasSize(1);
              assertThat(
                      context
                          .getBean(HttpCodeStatusMapper.class)
                          .getStatusCode(
                              new org.springframework.boot.actuate.health.Status("DEGRADED")))
                  .isEqualTo(200);
            });
  }
}
