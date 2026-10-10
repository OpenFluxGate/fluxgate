package org.fluxgate.envoy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.junit.jupiter.api.Test;

class AuthzDiagnosticsTest {
  private final RequestContext request =
      RequestContext.builder().clientIp("private-client").build();
  private final RateLimitEngine engine = mock(RateLimitEngine.class);
  private final RateLimitRule rule =
      RateLimitRule.builder("private-rule")
          .name("private-name")
          .ruleSetId("private-policy")
          .addBand(RateLimitBand.builder(Duration.ofHours(1), 3).build())
          .build();
  private final RateLimitRuleSet snapshot =
      RateLimitRuleSet.builder("private-policy")
          .rules(List.of(rule))
          .keyResolver(new LimitScopeKeyResolver())
          .build();

  @Test
  void blockedDecisionTracksAdmissionAndCompletionWithoutDiagnosticsBackendIo() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    RateLimitRuleSetProvider provider = mock(RateLimitRuleSetProvider.class);
    when(provider.findById("private-policy"))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("not released");
              return Optional.of(snapshot);
            });
    when(engine.checkUsingSnapshot(snapshot, request, 1L))
        .thenReturn(RateLimitResult.allowedWithoutRule());
    var service = new AuthzDecisionService(engine, provider, "private-policy", 1);
    var pool = Executors.newSingleThreadExecutor();
    try {
      var active = pool.submit(() -> service.decide(request));
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      assertThat(service.decide(request).status()).isEqualTo(503);
      var blocked = service.diagnostics();
      assertThat(blocked.admitted()).isEqualTo(1);
      assertThat(blocked.completed()).isZero();
      assertThat(blocked.inflight()).isEqualTo(1);
      assertThat(blocked.peak()).isEqualTo(1);
      assertThat(blocked.admissionRejected()).isEqualTo(1);
      verify(provider, times(1)).findById("private-policy");
      verifyNoInteractions(engine);
      release.countDown();
      assertThat(active.get(3, TimeUnit.SECONDS).status()).isEqualTo(200);
      assertThat(service.diagnostics().completed()).isEqualTo(1);
      assertThat(service.diagnostics().inflight()).isZero();
    } finally {
      release.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void fixedReasonsDistinguishEvery503AndFinallyIncludesAbruptFailure() {
    var provider = mock(RateLimitRuleSetProvider.class);
    var service = new AuthzDecisionService(engine, provider, "private-policy", 1);
    assertThat(service.decide("private-policy", request, 0).status()).isEqualTo(503);
    verifyNoInteractions(provider, engine);
    when(provider.findById("private-policy")).thenReturn(Optional.empty());
    assertThat(service.decide(request).status()).isEqualTo(503);
    var waitRule =
        RateLimitRule.builder("wait")
            .name("wait")
            .ruleSetId("private-policy")
            .onLimitExceedPolicy(OnLimitExceedPolicy.WAIT_FOR_REFILL)
            .addBand(RateLimitBand.builder(Duration.ofHours(1), 1).build())
            .build();
    when(provider.findById("private-policy"))
        .thenReturn(
            Optional.of(
                RateLimitRuleSet.builder("private-policy")
                    .rules(List.of(waitRule))
                    .keyResolver(new LimitScopeKeyResolver())
                    .build()));
    assertThat(service.decide(request).status()).isEqualTo(503);
    verifyNoInteractions(engine);
    when(provider.findById("private-policy")).thenReturn(Optional.of(snapshot));
    when(engine.checkUsingSnapshot(snapshot, request, 1L))
        .thenReturn(
            RateLimitResult.builder(RateLimitKey.of("private-key"))
                .allowed(false)
                .decisionReason(RateLimitResult.DecisionReason.BACKEND_FAILURE)
                .build());
    assertThat(service.decide(request).status()).isEqualTo(503);
    when(engine.checkUsingSnapshot(snapshot, request, 1L))
        .thenReturn(
            RateLimitResult.builder(RateLimitKey.of("private-key"))
                .allowed(false)
                .policy(OnLimitExceedPolicy.WAIT_FOR_REFILL)
                .build());
    assertThat(service.decide(request).status()).isEqualTo(503);
    doThrow(new IllegalStateException("private exception"))
        .when(engine)
        .checkUsingSnapshot(snapshot, request, 1L);
    assertThat(service.decide(request).status()).isEqualTo(503);
    doThrow(new AssertionError("private error"))
        .when(engine)
        .checkUsingSnapshot(snapshot, request, 1L);
    assertThatThrownBy(() -> service.decide(request)).isInstanceOf(AssertionError.class);
    var counters = service.diagnostics();
    assertThat(counters.invalidPermits()).isEqualTo(1);
    assertThat(counters.unavailableSnapshot()).isEqualTo(2);
    assertThat(counters.waitPolicy()).isEqualTo(1);
    assertThat(counters.engineInfrastructure()).isEqualTo(1);
    assertThat(counters.runtimeException()).isEqualTo(1);
    assertThat(counters.admitted()).isEqualTo(7);
    assertThat(counters.completed()).isEqualTo(7);
    assertThat(counters.inflight()).isZero();
  }

  @Test
  void managementSerializesOnlyAnonymousCountersOnExactGetRoute() throws Exception {
    var provider = mock(RateLimitRuleSetProvider.class);
    var service = new AuthzDecisionService(engine, provider, "private-policy", 1);
    service.decide("private-policy", request, 0);
    var properties =
        new EnvoyProperties(
            "private-secret",
            false,
            List.of(),
            "X-Forwarded-For",
            List.of(),
            List.of(),
            List.of(
                new EnvoyProperties.Route("all", "/", List.of(), List.of(), "private-policy", 1)),
            List.of());
    var server = new GatewayHealthServer(properties, service);
    try {
      int port = server.startServer(0);
      var client = HttpClient.newHttpClient();
      var response = send(client, port, "/diagnostics", "GET");
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
      assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
      assertThat(response.body())
          .isEqualTo(
              "{\"admitted\":1,\"completed\":1,\"inflight\":0,\"peak\":1,\"503\":{\"admission_rejected\":0,\"invalid_permits\":1,\"unavailable_snapshot\":0,\"wait_policy\":0,\"engine_infrastructure\":0,\"runtime_exception\":0}}");
      assertThat(response.body()).doesNotContain("private", "request", "exception:");
      assertThat(send(client, port, "/diagnostics-extra", "GET").statusCode()).isEqualTo(404);
      assertThat(send(client, port, "/authz/api", "GET").statusCode()).isEqualTo(404);
      assertThat(send(client, port, "/diagnostics", "POST").statusCode()).isEqualTo(405);
      verifyNoInteractions(provider, engine);
    } finally {
      server.destroy();
    }
  }

  private HttpResponse<String> send(HttpClient client, int port, String path, String method)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(3))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
