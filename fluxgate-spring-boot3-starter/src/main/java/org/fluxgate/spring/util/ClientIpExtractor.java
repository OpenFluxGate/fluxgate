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
    String forwardedFor = request.getHeader(Headers.X_FORWARDED_FOR);
    if (StringUtils.hasText(forwardedFor)) {
      // X-Forwarded-For can contain multiple IPs, take the first one
      String[] ips = forwardedFor.split(",");
      return ips[0].trim();
    }
    return request.getRemoteAddr();
  }
}
