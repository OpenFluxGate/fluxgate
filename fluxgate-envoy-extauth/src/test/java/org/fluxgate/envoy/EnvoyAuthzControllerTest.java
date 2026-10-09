package org.fluxgate.envoy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.fluxgate.core.context.RequestContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EnvoyAuthzControllerTest {
  @Test
  void preservesMethodAndOriginalPathButIgnoresSpoofedIdentityHeaders() throws Exception {
    AuthzDecisionService service = mock(AuthzDecisionService.class);
    when(service.decide(any())).thenReturn(AuthzDecision.of(200));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new EnvoyAuthzController(service)).build();

    mvc.perform(
            request(HttpMethod.POST, "/authz/api/items")
                .with(
                    r -> {
                      r.setRemoteAddr("10.1.2.3");
                      return r;
                    })
                .header("X-Forwarded-For", "8.8.8.8")
                .header("X-User-Id", "admin")
                .header("X-FluxGate-Rule-Set", "unlimited"))
        .andExpect(status().isOk());

    ArgumentCaptor<RequestContext> captured = ArgumentCaptor.forClass(RequestContext.class);
    verify(service).decide(captured.capture());
    assertThat(captured.getValue().getEndpoint()).isEqualTo("/api/items");
    assertThat(captured.getValue().getMethod()).isEqualTo("POST");
    assertThat(captured.getValue().getClientIp()).isEqualTo("10.1.2.3");
    assertThat(captured.getValue().getUserId()).isNull();
    assertThat(captured.getValue().getHeaders()).isEmpty();
  }

  @Test
  void quotaReturnsRetryAfterHeader() throws Exception {
    AuthzDecisionService service = mock(AuthzDecisionService.class);
    when(service.decide(any())).thenReturn(new AuthzDecision(429, 2));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new EnvoyAuthzController(service)).build();
    mvc.perform(request(HttpMethod.DELETE, "/authz/api/items"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "2"));
  }
}
