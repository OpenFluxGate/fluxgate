package org.fluxgate.envoy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EnvoyAuthzControllerTest {
  private final AuthzDecisionService service = mock(AuthzDecisionService.class);
  private final EnvoyProperties properties =
      new EnvoyProperties(
          "internal-secret",
          false,
          List.of(),
          "X-Forwarded-For",
          List.of(),
          List.of(),
          List.of(new EnvoyProperties.Route("all", "/", List.of(), List.of(), "policy", 2)),
          List.of());
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(
              new EnvoyAuthzController(
                  service, new EnvoyRequestAdapter(properties, List.of()), properties))
          .build();

  @Test
  void quotaReturnsRetryAfterForServerConfiguredPolicy() throws Exception {
    when(service.decide(eq("policy"), any(), eq(2L))).thenReturn(new AuthzDecision(429, 2));
    mvc.perform(
            request(HttpMethod.DELETE, "/authz/api/items")
                .header("Host", "example.com")
                .header(EnvoyRequestAdapter.SECRET_HEADER, "internal-secret"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "2"));
  }

  @Test
  void unauthenticatedPeerCannotReachEngine() throws Exception {
    mvc.perform(request(HttpMethod.GET, "/authz/api/items").header("Host", "example.com"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void optionsUsesSameAuthorizationContract() throws Exception {
    when(service.decide(eq("policy"), any(), eq(2L))).thenReturn(AuthzDecision.of(403));
    mvc.perform(
            request(HttpMethod.OPTIONS, "/authz/api/items")
                .header("Host", "example.com")
                .header(EnvoyRequestAdapter.SECRET_HEADER, "internal-secret"))
        .andExpect(status().isForbidden());
  }

  @Test
  void readinessRequiresAllConfiguredPoliciesAndHealthRemainsAvailable() throws Exception {
    mvc.perform(request(HttpMethod.GET, "/readyz")).andExpect(status().isServiceUnavailable());
    when(service.isReady("policy")).thenReturn(true);
    mvc.perform(request(HttpMethod.GET, "/readyz")).andExpect(status().isOk());
    mvc.perform(request(HttpMethod.GET, "/healthz")).andExpect(status().isOk());
  }
}
