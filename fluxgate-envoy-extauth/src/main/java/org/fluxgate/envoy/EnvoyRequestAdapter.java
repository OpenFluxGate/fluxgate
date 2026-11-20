package org.fluxgate.envoy;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.spring.filter.IdentitySource;
import org.fluxgate.spring.filter.RequestContextFactory;
import org.fluxgate.spring.util.TrustedProxies;
import org.springframework.stereotype.Component;

/** Adapts the HTTP extAuth request after authenticating the gateway-to-service connection. */
@Component
public final class EnvoyRequestAdapter {
  public static final String SECRET_HEADER = "X-FluxGate-Authz-Secret";
  private final EnvoyProperties properties;
  private final RequestContextFactory contextFactory;
  private final List<EnvoyIdentityResolver> identityResolvers;

  public EnvoyRequestAdapter(
      EnvoyProperties properties, List<EnvoyIdentityResolver> identityResolvers) {
    this.properties = properties;
    this.identityResolvers = List.copyOf(identityResolvers);
    List<String> headers =
        properties.headerAllowlist().stream()
            .filter(
                h ->
                    !h.equalsIgnoreCase(SECRET_HEADER)
                        && !h.equalsIgnoreCase("X-User-Id")
                        && !h.equalsIgnoreCase("X-FluxGate-Rule-Set")
                        && !h.equalsIgnoreCase("X-FluxGate-Permits"))
            .toList();
    contextFactory =
        new RequestContextFactory(
            properties.clientIpHeader(),
            !properties.trustedProxies().isEmpty(),
            TrustedProxies.of(properties.trustedProxies()),
            true,
            headers,
            null,
            IdentitySource.PRINCIPAL,
            null,
            null);
  }

  public boolean isAuthorizedPeer(HttpServletRequest request) {
    if (properties.allowInsecure()) {
      return true;
    }
    var values = Collections.list(request.getHeaders(SECRET_HEADER));
    if (properties.authzSecret() != null
        && !properties.authzSecret().isBlank()
        && values.size() == 1
        && values.get(0).length() <= 4096
        && MessageDigest.isEqual(
            properties.authzSecret().getBytes(StandardCharsets.UTF_8),
            values.get(0).getBytes(StandardCharsets.UTF_8))) {
      return true;
    }
    // This attribute is populated only by the TLS servlet connector after certificate validation.
    // Configure server.ssl.client-auth=need with a dedicated gateway CA trust store.
    Object certificate = request.getAttribute("jakarta.servlet.request.X509Certificate");
    if (!request.isSecure()
        || !(certificate instanceof X509Certificate[] chain)
        || chain.length == 0) {
      return false;
    }
    try {
      chain[0].checkValidity();
      return properties
          .gatewayCertificateSubjects()
          .contains(chain[0].getSubjectX500Principal().getName());
    } catch (java.security.cert.CertificateException e) {
      return false;
    }
  }

  public Optional<AdaptedRequest> adapt(HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (!uri.startsWith("/authz/") && !uri.equals("/authz")) {
      return Optional.empty();
    }
    String path = uri.substring("/authz".length());
    path = path.isEmpty() ? "/" : path;
    // Reject paths whose interpretation could differ between the gateway and servlet container.
    String lower = path.toLowerCase(Locale.ROOT);
    if (lower.contains("%2f")
        || lower.contains("%5c")
        || lower.contains("%2e")
        || path.contains("\\")
        || path.contains(";")
        || path.contains("//")
        || java.util.Arrays.asList(path.split("/")).contains("..")
        || java.util.Arrays.asList(path.split("/")).contains(".")) {
      throw new IllegalArgumentException("Ambiguous original request path");
    }
    var hostHeaders = Collections.list(request.getHeaders("Host"));
    if (hostHeaders.size() != 1) {
      return Optional.empty();
    }
    String host = hostHeaders.get(0).toLowerCase(Locale.ROOT);
    if (host.startsWith("[")) {
      int end = host.indexOf(']');
      if (end < 0) {
        return Optional.empty();
      }
      host = host.substring(0, end + 1);
    } else {
      int colon = host.indexOf(':');
      host = colon < 0 ? host : host.substring(0, colon);
    }
    final String originalPath = path;
    final String originalHost = host;
    Optional<EnvoyProperties.Route> route =
        properties.routes().stream()
            .filter(
                r ->
                    r.pathPrefix().equals("/")
                        || originalPath.equals(r.pathPrefix())
                        || originalPath.startsWith(r.pathPrefix() + "/"))
            .filter(
                r ->
                    r.hosts().isEmpty()
                        || r.hosts().stream().anyMatch(originalHost::equalsIgnoreCase))
            .filter(r -> r.methods().isEmpty() || r.methods().contains(request.getMethod()))
            .findFirst();
    if (route.isEmpty()) {
      return Optional.empty();
    }
    RequestContext base = contextFactory.create(request, path);
    RequestContext.Builder builder =
        RequestContext.builder()
            .clientIp(base.getClientIp())
            .endpoint(base.getEndpoint())
            .method(base.getMethod())
            .userId(base.getUserId())
            .attribute("host", host);
    base.getHeaders()
        .forEach((name, value) -> builder.header(name.toLowerCase(Locale.ROOT), value));
    boolean identityApplied = false;
    for (var resolver : identityResolvers) {
      var verified = resolver.resolve(request);
      if (verified.isPresent()) {
        if (identityApplied) {
          throw new SecurityException("Multiple identity sources claimed the request");
        }
        var identity = verified.get();
        builder
            .userId(identity.userId())
            .apiKey(identity.apiKeyId())
            .attributes(identity.attributes());
        identityApplied = true;
      }
    }
    builder.attribute("host", host);
    return Optional.of(new AdaptedRequest(route.get(), builder.build()));
  }

  public record AdaptedRequest(EnvoyProperties.Route route, RequestContext context) {}
}
