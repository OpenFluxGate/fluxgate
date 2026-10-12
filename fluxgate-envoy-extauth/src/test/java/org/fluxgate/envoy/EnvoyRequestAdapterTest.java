package org.fluxgate.envoy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class EnvoyRequestAdapterTest {
  private EnvoyProperties properties(List<String> proxies, List<EnvoyProperties.ApiKey> keys) {
    return new EnvoyProperties(
        "internal-secret",
        false,
        List.of(),
        "X-Forwarded-For",
        proxies,
        List.of("Host", "X-Tier", "Authorization", "X-FluxGate-Authz-Secret"),
        List.of(
            new EnvoyProperties.Route(
                "catalog", "/api", List.of("POST"), List.of("shop.example"), "catalog-policy", 3)),
        keys);
  }

  private MockHttpServletRequest request() {
    var request = new MockHttpServletRequest("POST", "/authz/api/items");
    request.addHeader("Host", "shop.example:8080");
    request.addHeader("X-FluxGate-Authz-Secret", "internal-secret");
    request.setRemoteAddr("10.1.2.3");
    return request;
  }

  @Test
  void rejectsInvalidConfigurationAndUnboundRequests() {
    assertThatThrownBy(() -> new EnvoyProperties(null, false, null, null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    var adapter = new EnvoyRequestAdapter(properties(List.of(), List.of()), List.of());
    var request = request();
    request.setRequestURI("/authz/api-other");
    assertThat(adapter.adapt(request)).isEmpty();
  }

  @Test
  void preservesOriginalRequestAndIgnoresSpoofedControlAndIdentity() {
    var adapter = new EnvoyRequestAdapter(properties(List.of(), List.of()), List.of());
    var request = request();
    request.addHeader("X-Forwarded-For", "8.8.8.8");
    request.addHeader("X-User-Id", "admin");
    request.addHeader("X-FluxGate-Rule-Set", "unlimited");
    request.addHeader("X-FluxGate-Permits", "0");
    request.addHeader("X-Tier", "premium");
    request.addHeader("Authorization", "Bearer secret");
    var adapted = adapter.adapt(request).orElseThrow();
    assertThat(adapted.route().ruleSetId()).isEqualTo("catalog-policy");
    assertThat(adapted.route().permits()).isEqualTo(3);
    assertThat(adapted.context().getEndpoint()).isEqualTo("/api/items");
    assertThat(adapted.context().getMethod()).isEqualTo("POST");
    assertThat(adapted.context().getClientIp()).isEqualTo("10.1.2.3");
    assertThat(adapted.context().getUserId()).isNull();
    assertThat(adapted.context().getHeader("x-tier")).isEqualTo("premium");
    assertThat(adapted.context().getHeaders())
        .doesNotContainKeys("Authorization", "X-FluxGate-Authz-Secret");
  }

  @Test
  void forwardingChainRequiresExplicitTrustedPeer() {
    var adapter = new EnvoyRequestAdapter(properties(List.of("10.0.0.0/8"), List.of()), List.of());
    var request = request();
    request.addHeader("X-Forwarded-For", "8.8.8.8, 203.0.113.4, 10.2.3.4");
    assertThat(adapter.adapt(request).orElseThrow().context().getClientIp())
        .isEqualTo("203.0.113.4");
    request.setRemoteAddr("198.51.100.1");
    assertThat(adapter.adapt(request).orElseThrow().context().getClientIp())
        .isEqualTo("198.51.100.1");
  }

  @Test
  void apiKeyMustMatchConfiguredDigestBeforeIdentityIsAccepted() throws Exception {
    String digest =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest("valid-key".getBytes(StandardCharsets.UTF_8)));
    var key =
        new EnvoyProperties.ApiKey(digest, "verified-user", "key-id-1", Map.of("tenant", "acme"));
    var properties = properties(List.of(), List.of(key));
    var adapter =
        new EnvoyRequestAdapter(
            properties, List.of(new ConfiguredApiKeyIdentityResolver(properties)));
    var request = request();
    request.addHeader("X-API-Key", "invalid");
    assertThatThrownBy(() -> adapter.adapt(request)).isInstanceOf(SecurityException.class);
    request.removeHeader("X-API-Key");
    request.addHeader("X-API-Key", "valid-key");
    var context = adapter.adapt(request).orElseThrow().context();
    assertThat(context.getUserId()).isEqualTo("verified-user");
    assertThat(context.getApiKey()).isEqualTo("key-id-1");
    assertThat(context.getAttributes()).containsEntry("tenant", "acme");
    assertThat(context.getHeaders()).doesNotContainKey("X-API-Key");
  }

  @Test
  void internalSecretCannotBeOmittedOrDuplicated() {
    var adapter = new EnvoyRequestAdapter(properties(List.of(), List.of()), List.of());
    var request = request();
    request.removeHeader("X-FluxGate-Authz-Secret");
    assertThat(adapter.isAuthorizedPeer(request)).isFalse();
    request.addHeader("X-FluxGate-Authz-Secret", "wrong");
    assertThat(adapter.isAuthorizedPeer(request)).isFalse();
    request.addHeader("X-FluxGate-Authz-Secret", "internal-secret");
    assertThat(adapter.isAuthorizedPeer(request)).isFalse();
  }

  @Test
  void authenticatedGatewayCertificateRequiresTlsAndAllowedSubject() {
    var props =
        new EnvoyProperties(
            null,
            false,
            List.of("CN=envoy-gateway"),
            "X-Forwarded-For",
            List.of(),
            List.of(),
            List.of(new EnvoyProperties.Route("all", "/", List.of(), List.of(), "policy", 1)),
            List.of());
    var adapter = new EnvoyRequestAdapter(props, List.of());
    var request = request();
    var certificate = org.mockito.Mockito.mock(java.security.cert.X509Certificate.class);
    org.mockito.Mockito.when(certificate.getSubjectX500Principal())
        .thenReturn(new javax.security.auth.x500.X500Principal("CN=envoy-gateway"));
    request.setAttribute(
        "jakarta.servlet.request.X509Certificate",
        new java.security.cert.X509Certificate[] {certificate});
    assertThat(adapter.isAuthorizedPeer(request)).isFalse();
    request.setSecure(true);
    assertThat(adapter.isAuthorizedPeer(request)).isTrue();
    org.mockito.Mockito.when(certificate.getSubjectX500Principal())
        .thenReturn(new javax.security.auth.x500.X500Principal("CN=other-service"));
    assertThat(adapter.isAuthorizedPeer(request)).isFalse();
  }

  @Test
  void rejectsAmbiguousPathAndWrongHostOrMethod() {
    var adapter = new EnvoyRequestAdapter(properties(List.of(), List.of()), List.of());
    var request = request();
    request.setRequestURI("/authz/api/%2e%2e/items");
    assertThatThrownBy(() -> adapter.adapt(request)).isInstanceOf(IllegalArgumentException.class);
    request.setRequestURI("/authz/api/items");
    request.setMethod("GET");
    assertThat(adapter.adapt(request)).isEmpty();
    request.setMethod("POST");
    request.removeHeader("Host");
    request.addHeader("Host", "other.example");
    assertThat(adapter.adapt(request)).isEmpty();
  }
}
