package org.fluxgate.envoy;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Server-owned routing and trust configuration. No request may choose a rule set or permit cost.
 */
@ConfigurationProperties("fluxgate.envoy")
public record EnvoyProperties(
    String authzSecret,
    @DefaultValue("false") boolean allowInsecure,
    List<String> gatewayCertificateSubjects,
    @DefaultValue("X-Forwarded-For") String clientIpHeader,
    List<String> trustedProxies,
    List<String> headerAllowlist,
    List<Route> routes,
    List<ApiKey> apiKeys,
    @DefaultValue("0") int healthPort) {
  public EnvoyProperties(
      String authzSecret,
      boolean allowInsecure,
      List<String> gatewayCertificateSubjects,
      String clientIpHeader,
      List<String> trustedProxies,
      List<String> headerAllowlist,
      List<Route> routes,
      List<ApiKey> apiKeys) {
    this(
        authzSecret,
        allowInsecure,
        gatewayCertificateSubjects,
        clientIpHeader,
        trustedProxies,
        headerAllowlist,
        routes,
        apiKeys,
        0);
  }

  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public EnvoyProperties {
    if (healthPort < 0 || healthPort > 65535) {
      throw new IllegalArgumentException("Invalid gateway health port");
    }
    gatewayCertificateSubjects =
        copy(gatewayCertificateSubjects).stream()
            .map(subject -> new javax.security.auth.x500.X500Principal(subject).getName())
            .toList();
    trustedProxies = copy(trustedProxies);
    headerAllowlist = copy(headerAllowlist);
    routes = copy(routes);
    apiKeys = copy(apiKeys);
    clientIpHeader = clientIpHeader == null ? "X-Forwarded-For" : clientIpHeader;
    if (!allowInsecure
        && (authzSecret == null || authzSecret.isBlank())
        && gatewayCertificateSubjects.isEmpty()) {
      throw new IllegalArgumentException(
          "Configure an authz secret or verified gateway client certificate subjects");
    }
    for (String proxy : trustedProxies) {
      if (org.fluxgate.spring.util.TrustedProxies.of(proxy).isEmpty()) {
        throw new IllegalArgumentException("Invalid trusted proxy configuration");
      }
    }
    if (apiKeys.stream()
            .map(key -> key.sha256().toLowerCase(java.util.Locale.ROOT))
            .distinct()
            .count()
        != apiKeys.size()) {
      throw new IllegalArgumentException("Duplicate API key digest");
    }
    if (routes.isEmpty()) {
      throw new IllegalArgumentException(
          "At least one server-owned Envoy route binding is required");
    }
    if (routes.stream().map(Route::id).distinct().count() != routes.size()) {
      throw new IllegalArgumentException("Envoy route binding ids must be unique");
    }
  }

  private static <T> List<T> copy(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  /** Ordered bindings, using segment-aware prefixes and exact host/method restrictions. */
  public record Route(
      String id,
      @DefaultValue("/") String pathPrefix,
      List<String> methods,
      List<String> hosts,
      String ruleSetId,
      @DefaultValue("1") long permits) {
    public Route {
      methods = copy(methods);
      hosts = copy(hosts);
      if (id == null
          || id.isBlank()
          || ruleSetId == null
          || ruleSetId.isBlank()
          || pathPrefix == null
          || !pathPrefix.startsWith("/")
          || pathPrefix.contains("?")
          || pathPrefix.contains("#")
          || pathPrefix.contains("..")
          || permits <= 0) {
        throw new IllegalArgumentException("Invalid Envoy route binding");
      }
      if (methods.stream()
              .anyMatch(
                  method ->
                      !java.util.Set.of(
                              "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE",
                              "CONNECT")
                          .contains(method))
          || hosts.stream()
              .anyMatch(
                  host ->
                      host.isBlank()
                          || host.contains("*")
                          || host.contains(" ")
                          || host.contains("/"))) {
        throw new IllegalArgumentException("Route methods and hosts must be explicit valid values");
      }
      if (pathPrefix.length() > 1 && pathPrefix.endsWith("/")) {
        pathPrefix = pathPrefix.substring(0, pathPrefix.length() - 1);
      }
    }
  }

  /** Digest-to-identity mapping; a reusable API key is never stored in request metrics. */
  public record ApiKey(
      String sha256, String userId, String apiKeyId, Map<String, Object> attributes) {
    public ApiKey {
      if (sha256 == null
          || !sha256.matches("[a-fA-F0-9]{64}")
          || apiKeyId == null
          || apiKeyId.isBlank()) {
        throw new IllegalArgumentException(
            "API key mappings require a SHA-256 digest and stable API key id");
      }
      attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
  }
}
