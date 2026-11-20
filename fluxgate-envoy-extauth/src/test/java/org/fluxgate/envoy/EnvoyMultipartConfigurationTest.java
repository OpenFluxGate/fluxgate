package org.fluxgate.envoy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Part;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartResolver;

class EnvoyMultipartConfigurationTest {
  private final AuthzDecisionService decisions = mock(AuthzDecisionService.class);
  private final EnvoyProperties properties =
      new EnvoyProperties(
          "peer-secret",
          false,
          List.of(),
          "X-Forwarded-For",
          List.of(),
          List.of(),
          List.of(new EnvoyProperties.Route("api", "/api", List.of(), List.of(), "policy", 1)),
          List.of());
  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withConfiguration(
              AutoConfigurations.of(
                  MultipartAutoConfiguration.class, WebMvcAutoConfiguration.class))
          .withBean(
              EnvoyAuthzController.class,
              () ->
                  new EnvoyAuthzController(
                      decisions, new EnvoyRequestAdapter(properties, List.of()), properties));

  @Test
  void applicationDefaultsDoNotCreateMultipartResolverOrContainerConfig() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed().doesNotHaveBean(MultipartResolver.class);
          assertThat(context).doesNotHaveBean(jakarta.servlet.MultipartConfigElement.class);
          assertThat(context.getEnvironment().getProperty("spring.servlet.multipart.enabled"))
              .isEqualTo("false");
        });
  }

  @Test
  void malformedMultipartCannotParsePartsBeforePeerRejection() {
    AtomicBoolean partsRead = new AtomicBoolean();
    runner.run(
        context -> {
          var mvc = MockMvcBuilders.webAppContextSetup(context).build();
          var malformed =
              new MockHttpServletRequest("POST", "/authz/api/items") {
                @Override
                public Collection<Part> getParts() throws ServletException {
                  partsRead.set(true);
                  throw new ServletException("Malformed multipart boundary");
                }
              };
          malformed.setServletPath("/authz/api/items");
          malformed.setContentType("multipart/form-data; boundary=missing");
          malformed.setContent("malformed body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
          mvc.perform(MockMvcRequestBuilders.post("/authz/api/items").with(request -> malformed))
              .andExpect(status().isForbidden());
          assertThat(partsRead).isFalse();
          verifyNoInteractions(decisions);
        });
  }
}
