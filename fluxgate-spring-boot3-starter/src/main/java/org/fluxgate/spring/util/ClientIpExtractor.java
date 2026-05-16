package org.fluxgate.spring.util;

import static org.fluxgate.core.constants.FluxgateConstants.Headers;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * Utility class for extracting client IP address from HTTP requests.
 *
 * <p>Supports extraction from X-Forwarded-For header for proxied requests.
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
   */
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
   */
  public static String extract(
      HttpServletRequest request, String clientIpHeader, boolean trustClientIpHeader) {
    if (!trustClientIpHeader) {
      return request.getRemoteAddr();
    }

    String headerName =
        StringUtils.hasText(clientIpHeader) ? clientIpHeader : Headers.X_FORWARDED_FOR;
    String forwardedFor = request.getHeader(headerName);
    if (StringUtils.hasText(forwardedFor)) {
      // X-Forwarded-For can contain multiple IPs, take the first one
      String[] ips = forwardedFor.split(",");
      return ips[0].trim();
    }
    return request.getRemoteAddr();
  }
}
