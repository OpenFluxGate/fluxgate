package org.fluxgate.envoy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayHealthServerTest {
  @Test
  void plaintextListenerChecksPolicyReadinessAndNeverAuthorizes() throws Exception {
    var properties =
        new EnvoyProperties(
            "secret",
            false,
            List.of(),
            "X-Forwarded-For",
            List.of(),
            List.of(),
            List.of(new EnvoyProperties.Route("all", "/", List.of(), List.of(), "policy", 1)),
            List.of());
    var service = mock(AuthzDecisionService.class);
    var server = new GatewayHealthServer(properties, service);
    try {
      int port = server.startServer(0);
      var http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
      assertThat(status(http, port, "/healthz")).isEqualTo(200);
      assertThat(status(http, port, "/readyz")).isEqualTo(503);
      when(service.isReady("policy")).thenReturn(true);
      assertThat(status(http, port, "/readyz")).isEqualTo(200);
      assertThat(status(http, port, "/authz/api/items")).isEqualTo(404);
      assertThat(status(http, port, "/healthz-extra")).isEqualTo(404);
    } finally {
      server.destroy();
    }
  }

  private int status(HttpClient http, int port, String path) throws Exception {
    return http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(3))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }
}
