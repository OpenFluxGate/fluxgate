package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.remoting.httpinvoker.HttpInvokerServiceExporter;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Deployment-shape evidence, NOT a CVE fix or a general proof of application safety. Only the
 * tested eager servlet context is inspected; customers must audit their own contexts, lazy/dynamic
 * registrations and custom deserializers. The dependency security gate stays enabled.
 */
@SuppressWarnings("deprecation")
class HttpInvokerExposureAuditTest {
  private WebApplicationContextRunner runner() throws Exception {
    List<String> names =
        Files.readAllLines(
            Paths.get(
                "src/main/resources/META-INF/spring/"
                    + "org.springframework.boot.autoconfigure.AutoConfiguration.imports"));
    Class<?>[] configurations =
        names.stream()
            .map(String::trim)
            .filter(s -> !s.isEmpty() && !s.startsWith("#"))
            .map(HttpInvokerExposureAuditTest::load)
            .toArray(Class<?>[]::new);
    return new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(configurations))
        .withUserConfiguration(MvcFixture.class)
        .withPropertyValues(
            "fluxgate.mongo.enabled=false",
            "fluxgate.redis.enabled=false",
            "fluxgate.reload.enabled=false");
  }

  private static Class<?> load(String name) {
    try {
      return Class.forName(name);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void auditRunsAgainstBoot2Spring5AndDoesNotHideTheFlaggedClass() {
    assertThat(org.springframework.boot.SpringBootVersion.getVersion()).startsWith("2.7.");
    assertThat(org.springframework.core.SpringVersion.getVersion()).startsWith("5.3.");
    assertThat(HttpInvokerServiceExporter.class.getProtectionDomain().getCodeSource()).isNotNull();
    assertThat(
            HttpInvokerServiceExporter.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toString())
        .contains("spring-web-5.3.");
  }

  @Test
  void defaultStarterContextDoesNotRegisterOrMapAnHttpInvoker() throws Exception {
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansOfType(HttpInvokerServiceExporter.class)).isEmpty();
              assertThat(mappedInvokers(context.getSourceApplicationContext(), "/audit-invoker"))
                  .isEmpty();
            });
  }

  @Test
  void auditDetectsAnApplicationSuppliedInvokerAndItsMappedEndpoint() throws Exception {
    runner()
        .withUserConfiguration(InvokerFixture.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansOfType(HttpInvokerServiceExporter.class))
                  .containsKey("/audit-invoker");
              assertThat(mappedInvokers(context.getSourceApplicationContext(), "/audit-invoker"))
                  .isNotEmpty();
            });
  }

  private static List<Object> mappedInvokers(WebApplicationContext context, String path) {
    return context.getBeansOfType(HandlerMapping.class).values().stream()
        .map(
            mapping -> {
              try {
                HandlerExecutionChain chain =
                    mapping.getHandler(new MockHttpServletRequest("POST", path));
                return chain == null ? null : chain.getHandler();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            })
        .filter(HttpInvokerServiceExporter.class::isInstance)
        .collect(Collectors.toList());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  static class MvcFixture {}

  public interface Echo {
    String echo(String value);
  }

  @Configuration(proxyBeanMethods = false)
  static class InvokerFixture {
    @Bean(name = "/audit-invoker")
    HttpInvokerServiceExporter exporter() {
      HttpInvokerServiceExporter exporter = new HttpInvokerServiceExporter();
      exporter.setServiceInterface(Echo.class);
      exporter.setService((Echo) value -> value);
      return exporter;
    }
  }
}
