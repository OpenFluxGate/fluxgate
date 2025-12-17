package org.fluxgate.spring.filter;

import jakarta.servlet.http.HttpServletRequest;
import org.fluxgate.core.context.RequestContext;

/**
 * Interface for customizing how RequestContext is built from HTTP requests.
 *
 * <p>Implement this interface to customize:
 *
 * <ul>
 *   <li>Client IP extraction (e.g., custom proxy headers)
 *   <li>User identification (e.g., from JWT tokens)
 *   <li>Custom attributes (e.g., tenant ID, region)
 *   <li>Any other RequestContext fields
 * </ul>
 *
 * <p>Example implementation:
 *
 * <pre>{@code
 * @Component
 * public class MyRequestContextCustomizer implements RequestContextCustomizer {
 *
 *     @Override
 *     public RequestContext.Builder customize(RequestContext.Builder builder,
 *                                              HttpServletRequest request) {
 *         // Extract tenant from header
 *         String tenantId = request.getHeader("X-Tenant-Id");
 *         builder.attribute("tenantId", tenantId);
 *
 *         // Override client IP extraction
 *         String clientIp = extractFromCloudflare(request);
 *         builder.clientIp(clientIp);
 *
 *         return builder;
 *     }
 *
 *     private String extractFromCloudflare(HttpServletRequest request) {
 *         String cfIp = request.getHeader("CF-Connecting-IP");
 *         return cfIp != null ? cfIp : request.getRemoteAddr();
 *     }
 * }
 * }</pre>
 *
 * @see FluxgateRateLimitFilter
 */
@FunctionalInterface
public interface RequestContextCustomizer {

  /**
   * Customize the RequestContext builder before the context is built.
   *
   * <p>The builder is already populated with default values extracted from the request. You can
   * override any values or add custom attributes.
   *
   * @param builder The pre-populated RequestContext builder
   * @param request The HTTP request
   * @return The customized builder (usually the same instance)
   */
  RequestContext.Builder customize(RequestContext.Builder builder, HttpServletRequest request);

  /**
   * Default no-op customizer that returns the builder unchanged.
   *
   * @return A customizer that does nothing
   */
  static RequestContextCustomizer identity() {
    return (builder, request) -> builder;
  }

  /**
   * Combines this customizer with another, applying this one first.
   *
   * @param after The customizer to apply after this one
   * @return A combined customizer
   */
  default RequestContextCustomizer andThen(RequestContextCustomizer after) {
    return (builder, request) -> after.customize(this.customize(builder, request), request);
  }
}
