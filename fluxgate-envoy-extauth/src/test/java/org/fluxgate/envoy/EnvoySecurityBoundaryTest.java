package org.fluxgate.envoy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.security.auth.x500.X500Principal;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.key.MissingKeyBehavior;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.redis.RedisRateLimiter;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class EnvoySecurityBoundaryTest {
  private final AuthzDecisionService decisions = mock(AuthzDecisionService.class);

  private EnvoyProperties properties(List<String> hosts, List<EnvoyProperties.ApiKey> keys) {
    return new EnvoyProperties(
        "secret",
        false,
        List.of("CN=gateway"),
        "X-Forwarded-For",
        List.of(),
        List.of("Host", "X-User-Id", "X-FluxGate-Rule-Set", "X-FluxGate-Permits"),
        List.of(new EnvoyProperties.Route("api", "/api", List.of("POST"), hosts, "policy", 2)),
        keys);
  }

  private EnvoyAuthzController controller(
      EnvoyProperties properties, List<EnvoyIdentityResolver> resolvers) {
    return new EnvoyAuthzController(
        decisions, new EnvoyRequestAdapter(properties, resolvers), properties);
  }

  private MockHttpServletRequest request(String path) {
    var request = new MockHttpServletRequest("POST", path);
    request.addHeader("Host", "shop.example:8443");
    request.addHeader(EnvoyRequestAdapter.SECRET_HEADER, "secret");
    request.setRemoteAddr("192.0.2.1");
    return request;
  }

  private X509Certificate certificate(String subject) {
    var cert = mock(X509Certificate.class);
    when(cert.getSubjectX500Principal()).thenReturn(new X500Principal(subject));
    return cert;
  }

  @Test
  void forgedInsecureCertificateWrongSubjectAndExpiredCertificateNeverReachQuota()
      throws Exception {
    var controller = controller(properties(List.of("shop.example"), List.of()), List.of());
    var request = request("/authz/api/items");
    request.removeHeader(EnvoyRequestAdapter.SECRET_HEADER);
    request.setAttribute(
        "jakarta.servlet.request.X509Certificate",
        new X509Certificate[] {certificate("CN=gateway")});
    assertThat(controller.authorize(request).getStatusCode().value()).isEqualTo(403);
    request.setSecure(true);
    request.setAttribute(
        "jakarta.servlet.request.X509Certificate",
        new X509Certificate[] {certificate("CN=intruder")});
    assertThat(controller.authorize(request).getStatusCode().value()).isEqualTo(403);
    var expired = certificate("CN=gateway");
    doThrow(new CertificateExpiredException("expired")).when(expired).checkValidity();
    request.setAttribute(
        "jakarta.servlet.request.X509Certificate", new X509Certificate[] {expired});
    assertThat(controller.authorize(request).getStatusCode().value()).isEqualTo(403);
    request.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[0]);
    assertThat(controller.authorize(request).getStatusCode().value()).isEqualTo(403);
    request.setAttribute("jakarta.servlet.request.X509Certificate", "forged-attribute");
    assertThat(controller.authorize(request).getStatusCode().value()).isEqualTo(403);
    verifyNoInteractions(decisions);
  }

  @Test
  void duplicateAndOversizedPeerSecretsFailBeforeDecision() {
    var controller = controller(properties(List.of("shop.example"), List.of()), List.of());
    var request = request("/authz/api/items");
    request.addHeader(EnvoyRequestAdapter.SECRET_HEADER, "secret");
    assertThat(controller.authorize(request).getStatusCode().value()).isEqualTo(403);
    request.removeHeader(EnvoyRequestAdapter.SECRET_HEADER);
    request.addHeader(EnvoyRequestAdapter.SECRET_HEADER, "s".repeat(4097));
    assertThat(controller.authorize(request).getStatusCode().value()).isEqualTo(403);
    verifyNoInteractions(decisions);
  }

  @Test
  void allAmbiguousPathRepresentationsAreRejectedBeforeDecision() {
    var controller = controller(properties(List.of("shop.example"), List.of()), List.of());
    for (String path :
        List.of(
            "/api/%5citems",
            "/api/%2eitems",
            "/api/%2fitems",
            "/api/items;v=1",
            "/api/./items",
            "/api/../items",
            "/api//items",
            "/api/\\items")) {
      assertThat(controller.authorize(request("/authz" + path)).getStatusCode().value())
          .as(path)
          .isEqualTo(400);
    }
    verifyNoInteractions(decisions);
  }

  @Test
  void missingDuplicateMalformedOrWrongHostAndWrongMethodNeverSelectPolicy() {
    var controller = controller(properties(List.of("shop.example"), List.of()), List.of());
    for (String host : List.of("", "[broken", "other.example")) {
      var request = request("/authz/api/items");
      request.removeHeader("Host");
      if (!host.isEmpty()) request.addHeader("Host", host);
      assertThat(controller.authorize(request).getStatusCode().value()).isEqualTo(503);
    }
    var request = request("/authz/api/items");
    request.addHeader("Host", "shop.example");
    assertThat(controller.authorize(request).getStatusCode().value()).isEqualTo(503);
    request = request("/authz/api/items");
    request.setMethod("GET");
    assertThat(controller.authorize(request).getStatusCode().value()).isEqualTo(503);
    assertThat(controller.authorize(request("/elsewhere")).getStatusCode().value()).isEqualTo(503);
    assertThat(controller.authorize(request("/authz/api-other")).getStatusCode().value())
        .isEqualTo(503);
    verifyNoInteractions(decisions);
  }

  @Test
  void exactPrefixAndBracketedIpv6HostSelectOnlyServerConfiguredCost() {
    var properties = properties(List.of("[2001:db8::1]"), List.of());
    var controller = controller(properties, List.of());
    when(decisions.decide(eq("policy"), any(), eq(2L))).thenReturn(AuthzDecision.of(200));
    var request = request("/authz/api");
    request.removeHeader("Host");
    request.addHeader("Host", "[2001:DB8::1]:8443");
    request.addHeader("X-FluxGate-Permits", "0");
    request.addHeader("X-FluxGate-Rule-Set", "unlimited");
    assertThat(controller.authorize(request).getStatusCode().value()).isEqualTo(200);
    verify(decisions)
        .decide(
            eq("policy"),
            argThat(
                context ->
                    "/api".equals(context.getEndpoint())
                        && context.getHeader("x-fluxgate-permits") == null
                        && context.getHeader("x-fluxgate-rule-set") == null),
            eq(2L));
  }

  @Test
  void duplicateBlankOversizedAndUnknownApiKeysCannotReachQuota() throws Exception {
    String hash =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest("valid-key".getBytes(StandardCharsets.UTF_8)));
    var properties =
        properties(
            List.of("shop.example"),
            List.of(new EnvoyProperties.ApiKey(hash, "user", "key-id", Map.of())));
    var controller =
        controller(properties, List.of(new ConfiguredApiKeyIdentityResolver(properties)));
    for (String key : List.of(" ", "k".repeat(4097), "unknown")) {
      var request = request("/authz/api/items");
      request.addHeader("X-API-Key", key);
      assertThat(controller.authorize(request).getStatusCode().value()).isEqualTo(403);
    }
    var request = request("/authz/api/items");
    request.addHeader("X-API-Key", "valid-key");
    request.addHeader("X-API-Key", "valid-key");
    assertThat(controller.authorize(request).getStatusCode().value()).isEqualTo(403);
    verifyNoInteractions(decisions);
  }

  @Test
  void absentCredentialAndSpoofedUserFailInRealEngineBeforeRedisConsumption() throws Exception {
    String hash =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest("valid-key".getBytes(StandardCharsets.UTF_8)));
    var properties =
        properties(
            List.of("shop.example"),
            List.of(new EnvoyProperties.ApiKey(hash, "user", "key-id", Map.of())));
    var rule =
        RateLimitRule.builder("user-quota")
            .ruleSetId("policy")
            .scope(LimitScope.PER_USER)
            .addBand(RateLimitBand.builder(Duration.ofHours(1), 10).build())
            .build();
    var policy =
        RateLimitRuleSet.builder("policy")
            .rules(List.of(rule))
            .keyResolver(new LimitScopeKeyResolver(MissingKeyBehavior.REJECT))
            .build();
    RateLimitRuleSetProvider provider = id -> Optional.of(policy);
    var store = mock(RedisTokenBucketStore.class);
    var engine =
        RateLimitEngine.builder()
            .ruleSetProvider(provider)
            .rateLimiter(new RedisRateLimiter(store))
            .build();
    var controller =
        new EnvoyAuthzController(
            new AuthzDecisionService(engine, provider, "policy"),
            new EnvoyRequestAdapter(
                properties, List.of(new ConfiguredApiKeyIdentityResolver(properties))),
            properties);
    var request = request("/authz/api/items");
    request.addHeader("X-User-Id", "user");
    assertThat(controller.authorize(request).getStatusCode().value()).isEqualTo(403);
    verifyNoInteractions(store);
  }

  @Test
  void conflictingVerifiedIdentitySourcesFailClosedBeforeDecision() {
    var identity = new EnvoyIdentityResolver.VerifiedIdentity("user", "key-id", Map.of());
    EnvoyIdentityResolver first = request -> Optional.of(identity);
    EnvoyIdentityResolver second = request -> Optional.of(identity);
    var controller =
        controller(properties(List.of("shop.example"), List.of()), List.of(first, second));
    assertThat(controller.authorize(request("/authz/api/items")).getStatusCode().value())
        .isEqualTo(403);
    verifyNoInteractions(decisions);
  }
}
