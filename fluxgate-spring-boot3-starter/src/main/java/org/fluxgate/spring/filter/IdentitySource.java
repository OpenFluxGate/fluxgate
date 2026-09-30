package org.fluxgate.spring.filter;

/**
 * Where {@link org.fluxgate.spring.filter.RequestContextFactory} takes the caller's identity from.
 *
 * <p>C-4: identity headers are client input. {@code X-User-Id: victim} is one curl flag away, so a
 * deployment that limits per user or per API key on header values alone lets any caller pick which
 * bucket to spend, and whose quota to exhaust. Taking the identity from the authenticated principal
 * instead makes the value one the security stack has already verified.
 *
 * <p>The default filter order ({@code 1}) places FluxGate after Spring Security's filter chain
 * ({@code -100}), so the principal is available. Lowering {@code fluxgate.ratelimit.filter-order}
 * below {@code -100} to get a pre-authentication {@code PER_IP} layer also removes the principal,
 * and identity resolution falls back to headers.
 */
public enum IdentitySource {

  /**
   * Read {@code user-id-header} and {@code api-key-header} from the request.
   *
   * <p>The historical behaviour, and the only option without Spring Security. Combine it with
   * {@code fluxgate.ratelimit.missing-key-behavior=REJECT} and a proxy that strips these headers at
   * the edge, or the identity is whatever the caller claims.
   */
  HEADERS,

  /**
   * Take the user id from the authenticated principal only, and never read identity headers.
   *
   * <p>An unauthenticated or anonymous request therefore has no user id and no API key, and a rule
   * scoped to one falls back according to {@code fluxgate.ratelimit.missing-key-behavior}.
   */
  PRINCIPAL,

  /**
   * Take the user id from the authenticated principal, falling back to the header when there is no
   * authentication.
   *
   * <p>The default when Spring Security is on the classpath: authenticated traffic is limited by
   * verified identity, while an unauthenticated endpoint keeps working as before.
   */
  PRINCIPAL_THEN_HEADERS
}
