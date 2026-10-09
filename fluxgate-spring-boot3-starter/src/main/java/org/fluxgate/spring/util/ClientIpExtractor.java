package org.fluxgate.spring.util;

import static org.fluxgate.core.constants.FluxgateConstants.Headers;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * Utility class for extracting client IP address from HTTP requests.
 *
 * <p>Supports extraction from forwarding headers such as {@code X-Forwarded-For}, but only when the
 * request demonstrably arrived through a trusted reverse proxy. A forwarding header is client
 * supplied data: honouring it unconditionally lets any caller rotate the header per request and
 * bypass {@code PER_IP} limits entirely while filling Redis with one bucket per forged value.
 */
public final class ClientIpExtractor {

  private ClientIpExtractor() {
    // Utility class
  }

  /**
   * Extracts the client IP address from the request.
   *
   * <p>First checks the X-Forwarded-For header for proxied requests. If multiple IPs are present,
   * returns the first one (original client). Falls back to {@link
   * HttpServletRequest#getRemoteAddr()} if no forwarded header is present.
   *
   * @param request the HTTP request
   * @return the client IP address
   * @deprecated unsafe: trusts {@code X-Forwarded-For} unconditionally, so any caller can rotate
   *     the header per request and bypass every {@code PER_IP} limit. Will be removed in the next
   *     major release. Use {@link #extract(HttpServletRequest, String, boolean, TrustedProxies)}
   *     and configure {@code fluxgate.ratelimit.trusted-proxies}.
   */
  @Deprecated
  public static String extract(HttpServletRequest request) {
    return extract(request, Headers.X_FORWARDED_FOR, true);
  }

  /**
   * Extracts the client IP address using the configured forwarding header only when it is trusted.
   *
   * @param request the HTTP request
   * @param clientIpHeader forwarding header to inspect when trusted
   * @param trustClientIpHeader whether forwarding headers are trusted
   * @return the client IP address
   * @deprecated unsafe: with no trusted proxy list the left-most forwarded hop is taken, which a
   *     client can forge, so {@code trustClientIpHeader=true} trusts {@code X-Forwarded-For}
   *     unconditionally. Will be removed in the next major release. Use {@link
   *     #extract(HttpServletRequest, String, boolean, TrustedProxies)} and configure {@code
   *     fluxgate.ratelimit.trusted-proxies}.
   */
  @Deprecated
  public static String extract(
      HttpServletRequest request, String clientIpHeader, boolean trustClientIpHeader) {
    return extract(request, clientIpHeader, trustClientIpHeader, TrustedProxies.none());
  }

  /**
   * Extracts the client IP address, honouring the forwarding header only for requests that arrived
   * through a trusted proxy.
   *
   * <p>Selection rules:
   *
   * <ul>
   *   <li>{@code trustClientIpHeader = false}: always use {@link
   *       HttpServletRequest#getRemoteAddr()}.
   *   <li>Non-empty {@code trustedProxies} that does not contain the remote address: the header is
   *       forged or the deployment is misconfigured, so use the remote address.
   *   <li>Non-empty {@code trustedProxies} containing the remote address: walk the forwarded chain
   *       from the right (the hop closest to this server) and return the first entry that is not
   *       itself a trusted proxy.
   *   <li>Empty {@code trustedProxies}: use the right-most valid hop (appended by the immediate
   *       proxy, harder to forge than the left-most client-supplied value). Nothing can be verified
   *       end-to-end, so the auto-configuration logs one startup WARN asking for a trusted-proxies
   *       list for multi-hop setups.
   * </ul>
   *
   * <p>In every case a candidate is only accepted when it is a valid IPv4 or IPv6 literal of at
   * most 45 characters, so a forged header cannot inject an arbitrary string into the bucket key.
   *
   * @param request the HTTP request
   * @param clientIpHeader forwarding header to inspect when trusted
   * @param trustClientIpHeader whether forwarding headers are trusted
   * @param trustedProxies the proxies allowed to set the forwarding header (never null)
   * @return the client IP address
   */
  public static String extract(
      HttpServletRequest request,
      String clientIpHeader,
      boolean trustClientIpHeader,
      TrustedProxies trustedProxies) {

    String remoteAddr = request.getRemoteAddr();
    if (!trustClientIpHeader) {
      return remoteAddr;
    }

    TrustedProxies proxies = trustedProxies != null ? trustedProxies : TrustedProxies.none();
    if (!proxies.isEmpty() && !proxies.contains(remoteAddr)) {
      return remoteAddr;
    }

    String headerName =
        StringUtils.hasText(clientIpHeader) ? clientIpHeader : Headers.X_FORWARDED_FOR;
    String forwardedFor = request.getHeader(headerName);
    if (!StringUtils.hasText(forwardedFor)) {
      return remoteAddr;
    }

    String[] hops = forwardedFor.split(",");
    if (proxies.isEmpty()) {
      // Nothing can be verified end-to-end. Take the right-most valid hop: it was appended by the
      // immediate proxy (the one that set this header), which is harder to forge than the
      // left-most client-supplied value. The startup WARN tells operators to configure
      // trusted-proxies for multi-hop setups.
      for (int i = hops.length - 1; i >= 0; i--) {
        String candidate = hops[i].trim();
        if (TrustedProxies.isIpLiteral(candidate)) {
          return candidate;
        }
      }
      return remoteAddr;
    }

    for (int i = hops.length - 1; i >= 0; i--) {
      String candidate = hops[i].trim();
      if (!TrustedProxies.isIpLiteral(candidate)) {
        continue;
      }
      if (!proxies.contains(candidate)) {
        return candidate;
      }
    }
    return remoteAddr;
  }
}
