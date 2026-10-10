package org.fluxgate.spring.util;

import static org.fluxgate.core.constants.FluxgateConstants.Headers;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
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
   * <p>Every line of the forwarding header is read ({@link HttpServletRequest#getHeaders}), in
   * order, and the hops are walked from the right (the hop closest to this server):
   *
   * <ul>
   *   <li>{@code trustClientIpHeader = false}: always use {@link
   *       HttpServletRequest#getRemoteAddr()}.
   *   <li>Non-empty {@code trustedProxies} that does not contain the remote address: the header is
   *       forged or the deployment is misconfigured, so use the remote address.
   *   <li>Non-empty {@code trustedProxies} containing the remote address: skip hops that are
   *       trusted proxies and return the first one that is not.
   *   <li>Empty {@code trustedProxies}: return the right-most hop (appended by the immediate proxy,
   *       harder to forge than the left-most client-supplied value). Nothing can be verified
   *       end-to-end, so the auto-configuration logs one startup WARN asking for a trusted-proxies
   *       list for multi-hop setups.
   * </ul>
   *
   * <p>R4: a hop is normalised before it is judged: {@code ip:port}, {@code [v6]} and {@code
   * [v6]:port} are reduced to the address. When the hop that would be returned is not a valid IPv4
   * or IPv6 literal of at most 45 characters, the extraction fails closed to the remote address
   * instead of skipping it, because everything to its left is client-controlled. The returned
   * address is canonical ({@link InetAddress#getHostAddress()}), so different spellings of one
   * address share one bucket key.
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

    String remoteAddr = canonicalOrSelf(request.getRemoteAddr());
    if (!trustClientIpHeader) {
      return remoteAddr;
    }

    TrustedProxies proxies = trustedProxies != null ? trustedProxies : TrustedProxies.none();
    if (!proxies.isEmpty() && !proxies.contains(remoteAddr)) {
      return remoteAddr;
    }

    String headerName =
        StringUtils.hasText(clientIpHeader) ? clientIpHeader : Headers.X_FORWARDED_FOR;
    List<String> hops = forwardedHops(request, headerName);
    if (hops.isEmpty()) {
      return remoteAddr;
    }

    for (int i = hops.size() - 1; i >= 0; i--) {
      String candidate = canonicalHop(hops.get(i));
      if (candidate == null) {
        // Fail closed: an unparseable hop cannot be attributed, and every hop to its left is
        // under the client's control.
        return remoteAddr;
      }
      if (proxies.isEmpty() || !proxies.contains(candidate)) {
        return candidate;
      }
    }
    return remoteAddr;
  }

  /** Collects the comma-separated hops of every line of the header, in order. */
  private static List<String> forwardedHops(HttpServletRequest request, String headerName) {
    List<String> hops = new ArrayList<>();
    Enumeration<String> lines = request.getHeaders(headerName);
    if (lines == null) {
      return hops;
    }
    while (lines.hasMoreElements()) {
      String line = lines.nextElement();
      if (line == null || line.trim().isEmpty()) {
        continue;
      }
      for (String hop : line.split(",", -1)) {
        hops.add(hop.trim());
      }
    }
    return hops;
  }

  /**
   * Reduces a forwarded hop to a canonical IP literal.
   *
   * @return the canonical address, or null when the hop is not an IP literal (with optional port)
   */
  static String canonicalHop(String hop) {
    if (hop == null || hop.isEmpty()) {
      return null;
    }
    String address;
    if (hop.charAt(0) == '[') {
      int end = hop.indexOf(']');
      if (end < 0 || !isPortSuffix(hop.substring(end + 1))) {
        return null;
      }
      address = hop.substring(1, end);
      if (address.indexOf(':') < 0) {
        return null; // brackets are only valid around IPv6
      }
    } else {
      int firstColon = hop.indexOf(':');
      if (firstColon >= 0 && firstColon == hop.lastIndexOf(':')) {
        // exactly one colon: IPv4 with a port
        if (!isPortSuffix(hop.substring(firstColon))) {
          return null;
        }
        address = hop.substring(0, firstColon);
        if (address.indexOf('.') < 0) {
          return null;
        }
      } else {
        address = hop;
      }
    }
    return canonical(address);
  }

  private static boolean isPortSuffix(String suffix) {
    if (suffix.isEmpty()) {
      return true;
    }
    if (suffix.charAt(0) != ':' || suffix.length() < 2 || suffix.length() > 6) {
      return false;
    }
    for (int i = 1; i < suffix.length(); i++) {
      char c = suffix.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  /** Canonical form of an IP literal, or null when the value is not one. */
  private static String canonical(String address) {
    // TrustedProxies parses the literal itself; getByAddress only formats the bytes and never
    // performs a name lookup, unlike getByName.
    byte[] bytes = TrustedProxies.toAddress(address);
    if (bytes == null) {
      return null;
    }
    try {
      return InetAddress.getByAddress(bytes).getHostAddress();
    } catch (UnknownHostException e) {
      return null; // unreachable: toAddress returns 4 or 16 bytes
    }
  }

  /** The canonical form of an IP literal; any other value (for example a socket path) unchanged. */
  private static String canonicalOrSelf(String address) {
    String canonical = canonical(address);
    return canonical != null ? canonical : address;
  }
}
