package org.fluxgate.envoy;

/** HTTP authorization result returned to Envoy. */
public record AuthzDecision(int status, long retryAfterSeconds) {
  public static AuthzDecision of(int status) {
    return new AuthzDecision(status, 0);
  }
}
