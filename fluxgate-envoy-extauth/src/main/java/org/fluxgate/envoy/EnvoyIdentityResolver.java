package org.fluxgate.envoy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;

/** Integration point for an actual credential verifier; raw identity headers are not identities. */
public interface EnvoyIdentityResolver {
  Optional<VerifiedIdentity> resolve(HttpServletRequest request);

  record VerifiedIdentity(String userId, String apiKeyId, Map<String, Object> attributes) {
    public VerifiedIdentity {
      attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
  }
}
