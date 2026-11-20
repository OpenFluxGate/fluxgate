package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.fluxgate.spring.properties.FluxgateProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.autoconfigure.availability.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * R1: the HTTP status of {@code /actuator/health} with FluxGate on the classpath.
 *
 * <p>Runs a real {@code SpringApplication} so the environment post-processors registered in {@code
 * META-INF/spring.factories} take part, exactly as in an application.
 */
class FluxgateHealthStatusMappingTest {

  private static final Status DEGRADED = new Status("DEGRADED");

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(FluxgateProperties.class)
  @ImportAutoConfiguration({
    ApplicationAvailabilityAutoConfiguration.class,
    HealthContributorAutoConfiguration.class,
    HealthEndpointAutoConfiguration.class,
    AvailabilityProbesAutoConfiguration.class,
    FluxgateActuatorAutoConfiguration.class
  })
  static class App {}

  @Configuration(proxyBeanMethods = false)
  static class CustomMapperConfig {

    @Bean
    HttpCodeStatusMapper userMapper() {
      return status -> 299;
    }
  }

  /** A degraded dependency next to a healthy one, as FluxGate reports a broken Redis. */
  @Configuration(proxyBeanMethods = false)
  static class DegradedConfig {

    @Bean
    HealthIndicator degradedDependency() {
      return () -> Health.status(DEGRADED).build();
    }

    @Bean
    HealthIndicator healthyDependency() {
      return () -> Health.up().build();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class DownConfig {

    @Bean
    HealthIndicator downDependency() {
      return () -> Health.down().build();
    }
  }

  private static ConfigurableApplicationContext run(Class<?>[] sources, String... properties) {
    return new SpringApplicationBuilder(sources)
        .web(WebApplicationType.NONE)
        .properties(properties)
        .properties("spring.main.banner-mode=off")
        .run();
  }

  private static ConfigurableApplicationContext run(String... properties) {
    return run(new Class<?>[] {App.class}, properties);
  }

  private static HttpCodeStatusMapper primary(ConfigurableApplicationContext context) {
    return context.getBean(HealthEndpointGroups.class).getPrimary().getHttpCodeStatusMapper();
  }

  private static HttpCodeStatusMapper group(ConfigurableApplicationContext context, String name) {
    HealthEndpointGroup group = context.getBean(HealthEndpointGroups.class).get(name);
    assertThat(group).as("health group %s", name).isNotNull();
    return group.getHttpCodeStatusMapper();
  }

  @Test
  void downMapsTo503() {
    try (ConfigurableApplicationContext context = run()) {
      assertThat(primary(context).getStatusCode(Status.DOWN)).isEqualTo(503);
    }
  }

  @Test
  void outOfServiceMapsTo503() {
    try (ConfigurableApplicationContext context = run()) {
      assertThat(primary(context).getStatusCode(Status.OUT_OF_SERVICE)).isEqualTo(503);
    }
  }

  @Test
  void upAndUnknownStay200() {
    try (ConfigurableApplicationContext context = run()) {
      assertThat(primary(context).getStatusCode(Status.UP)).isEqualTo(200);
      assertThat(primary(context).getStatusCode(Status.UNKNOWN)).isEqualTo(200);
    }
  }

  @Test
  void degradedMapsTo503ByDefault() {
    try (ConfigurableApplicationContext context = run()) {
      assertThat(primary(context).getStatusCode(DEGRADED)).isEqualTo(503);
    }
  }

  @Test
  void degradedMapsToConfiguredStatus() {
    try (ConfigurableApplicationContext context =
        run("fluxgate.actuator.health.degraded-http-status=429")) {
      assertThat(primary(context).getStatusCode(DEGRADED)).isEqualTo(429);
      assertThat(primary(context).getStatusCode(Status.DOWN)).isEqualTo(503);
    }
  }

  @Test
  void zeroDegradedStatusKeepsBootDefaults() {
    try (ConfigurableApplicationContext context =
        run("fluxgate.actuator.health.degraded-http-status=0")) {
      assertThat(primary(context).getStatusCode(DEGRADED)).isEqualTo(200);
      assertThat(primary(context).getStatusCode(Status.DOWN)).isEqualTo(503);
      assertThat(primary(context).getStatusCode(Status.OUT_OF_SERVICE)).isEqualTo(503);
    }
  }

  @Test
  void disabledHealthIndicatorContributesNoMapping() {
    try (ConfigurableApplicationContext context = run("fluxgate.actuator.health.enabled=false")) {
      assertThat(primary(context).getStatusCode(DEGRADED)).isEqualTo(200);
      assertThat(primary(context).getStatusCode(Status.DOWN)).isEqualTo(503);
    }
  }

  @Test
  void userMappingForDegradedWins() {
    try (ConfigurableApplicationContext context =
        run("management.endpoint.health.status.http-mapping.DEGRADED=200")) {
      assertThat(primary(context).getStatusCode(DEGRADED)).isEqualTo(200);
      assertThat(primary(context).getStatusCode(Status.DOWN)).isEqualTo(503);
    }
  }

  @Test
  void userMappingForDownWins() {
    try (ConfigurableApplicationContext context =
        run(
            "management.endpoint.health.status.http-mapping.down=500",
            "management.endpoint.health.status.http-mapping.out_of_service=502")) {
      assertThat(primary(context).getStatusCode(Status.DOWN)).isEqualTo(500);
      assertThat(primary(context).getStatusCode(Status.OUT_OF_SERVICE)).isEqualTo(502);
      assertThat(primary(context).getStatusCode(DEGRADED)).isEqualTo(503);
    }
  }

  @Test
  void userDefinedMapperBeanWins() {
    try (ConfigurableApplicationContext context =
        run(new Class<?>[] {App.class, CustomMapperConfig.class})) {
      assertThat(context.getBeansOfType(HttpCodeStatusMapper.class)).hasSize(1);
      assertThat(primary(context).getStatusCode(Status.DOWN)).isEqualTo(299);
      assertThat(primary(context).getStatusCode(DEGRADED)).isEqualTo(299);
    }
  }

  @Test
  void noReplacementMapperBeanIsRegistered() {
    try (ConfigurableApplicationContext context = run()) {
      // Only Boot's own mapper, built from the management.endpoint.health.status properties.
      assertThat(context.getBeansOfType(HttpCodeStatusMapper.class))
          .containsOnlyKeys("healthHttpCodeStatusMapper");
    }
  }

  @Test
  void livenessAndReadinessProbesMapDownTo503() {
    try (ConfigurableApplicationContext context =
        run("management.endpoint.health.probes.enabled=true")) {
      for (String name : new String[] {"liveness", "readiness"}) {
        assertThat(group(context, name).getStatusCode(Status.DOWN)).isEqualTo(503);
        assertThat(group(context, name).getStatusCode(Status.OUT_OF_SERVICE)).isEqualTo(503);
        assertThat(group(context, name).getStatusCode(Status.UP)).isEqualTo(200);
      }
    }
  }

  @Test
  void readinessGroupIncludingFluxgateMapsDegradedTo503() {
    try (ConfigurableApplicationContext context =
        run(
            "management.endpoint.health.probes.enabled=true",
            "management.endpoint.health.group.readiness.include=readinessState,fluxgate")) {
      assertThat(group(context, "readiness").getStatusCode(Status.DOWN)).isEqualTo(503);
      assertThat(group(context, "readiness").getStatusCode(DEGRADED)).isEqualTo(503);
    }
  }

  @Test
  void groupWithOwnMappingKeepsIt() {
    try (ConfigurableApplicationContext context =
        run(
            "management.endpoint.health.group.custom.include=ping",
            "management.endpoint.health.group.custom.status.http-mapping.down=418")) {
      assertThat(group(context, "custom").getStatusCode(Status.DOWN)).isEqualTo(418);
    }
  }

  @Test
  void customGroupWithoutOwnMappingInheritsDefaults() {
    try (ConfigurableApplicationContext context =
        run("management.endpoint.health.group.custom.include=ping")) {
      assertThat(group(context, "custom").getStatusCode(Status.DOWN)).isEqualTo(503);
      assertThat(group(context, "custom").getStatusCode(DEGRADED)).isEqualTo(503);
    }
  }

  // ===== aggregation: the status order must know DEGRADED =====

  private static ConfigurableApplicationContext runDegraded(String... properties) {
    return run(new Class<?>[] {App.class, DegradedConfig.class}, properties);
  }

  /** The aggregated status of a health path, and the HTTP code the group maps it to. */
  private static Status aggregated(ConfigurableApplicationContext context, String... path) {
    return context.getBean(HealthEndpoint.class).healthForPath(path).getStatus();
  }

  private static Status rootStatus(ConfigurableApplicationContext context) {
    return context.getBean(HealthEndpoint.class).health().getStatus();
  }

  @Test
  void rootHealthAggregatesToDegradedAndAnswers503() {
    try (ConfigurableApplicationContext context = runDegraded()) {
      Status status = rootStatus(context);

      assertThat(status).isEqualTo(DEGRADED);
      assertThat(primary(context).getStatusCode(status)).isEqualTo(503);
    }
  }

  @Test
  void downOutranksDegradedInTheAggregate() {
    try (ConfigurableApplicationContext context =
        run(new Class<?>[] {App.class, DegradedConfig.class, DownConfig.class})) {
      assertThat(rootStatus(context)).isEqualTo(Status.DOWN);
    }
  }

  @Test
  void readinessIncludingADegradedDependencyAnswers503() {
    try (ConfigurableApplicationContext context =
        runDegraded(
            "management.endpoint.health.probes.enabled=true",
            "management.endpoint.health.group.readiness.include=readinessState,degradedDependency")) {
      Status status = aggregated(context, "readiness");

      assertThat(status).isEqualTo(DEGRADED);
      assertThat(group(context, "readiness").getStatusCode(status)).isEqualTo(503);
    }
  }

  @Test
  void degradedStatusZeroStillAggregatesDegradedButMapsIt200() {
    try (ConfigurableApplicationContext context =
        runDegraded("fluxgate.actuator.health.degraded-http-status=0")) {
      Status status = rootStatus(context);

      assertThat(status).isEqualTo(DEGRADED);
      assertThat(primary(context).getStatusCode(status)).isEqualTo(200);
    }
  }

  @Test
  void disabledHealthIndicatorContributesNoOrder() {
    try (ConfigurableApplicationContext context =
        runDegraded("fluxgate.actuator.health.enabled=false")) {
      // Boot's default order does not know DEGRADED, so it is left out of the aggregate.
      assertThat(rootStatus(context)).isEqualTo(Status.UP);
    }
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void userOrderWinsAndAMissingDegradedIsReported(CapturedOutput output) {
    try (ConfigurableApplicationContext context =
        runDegraded("management.endpoint.health.status.order=down,up")) {
      assertThat(rootStatus(context)).isEqualTo(Status.UP);
    }
    assertThat(output)
        .contains("management.endpoint.health.status.order")
        .contains("does not list DEGRADED");
  }

  @Test
  void userOrderListingDegradedIsUsedAsIs() {
    try (ConfigurableApplicationContext context =
        runDegraded("management.endpoint.health.status.order=degraded,down,up")) {
      assertThat(rootStatus(context)).isEqualTo(DEGRADED);
    }
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void groupOrderWithoutDegradedIsReported(CapturedOutput output) {
    try (ConfigurableApplicationContext context =
        runDegraded(
            "management.endpoint.health.group.custom.include=degradedDependency,healthyDependency",
            "management.endpoint.health.group.custom.status.order=down,up")) {
      assertThat(aggregated(context, "custom")).isEqualTo(Status.UP);
      assertThat(rootStatus(context)).isEqualTo(DEGRADED);
    }
    assertThat(output)
        .contains("management.endpoint.health.group.custom.status.order")
        .contains("does not list DEGRADED");
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void groupOrderInAnyRelaxedSpellingIsReported(CapturedOutput output) {
    try (ConfigurableApplicationContext context =
        runDegraded(
            "management.endpoint.health.group.custom.include=degradedDependency",
            "management.endpoint.health.group.custom.status.order[0]=down",
            "management.endpoint.health.group.custom.status.order[1]=up")) {
      assertThat(aggregated(context, "custom")).isEqualTo(Status.UNKNOWN);
    }
    assertThat(output).contains("management.endpoint.health.group.custom.status.order=down,up");
  }

  @Test
  void groupWithoutOwnOrderInheritsTheDefaultOrder() {
    try (ConfigurableApplicationContext context =
        runDegraded(
            "management.endpoint.health.group.custom.include=degradedDependency,healthyDependency")) {
      assertThat(aggregated(context, "custom")).isEqualTo(DEGRADED);
      assertThat(group(context, "custom").getStatusCode(DEGRADED)).isEqualTo(503);
    }
  }
}
