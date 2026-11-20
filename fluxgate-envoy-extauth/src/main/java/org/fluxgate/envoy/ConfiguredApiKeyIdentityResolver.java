package org.fluxgate.envoy;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Verifies configured high-entropy API keys locally before assigning identity. */
@Component
public final class ConfiguredApiKeyIdentityResolver implements EnvoyIdentityResolver {
  private final EnvoyProperties properties;

  public ConfiguredApiKeyIdentityResolver(EnvoyProperties properties) {
    this.properties = properties;
  }

  @Override
  public Optional<VerifiedIdentity> resolve(HttpServletRequest request) {
    if (properties.apiKeys().isEmpty()) {
      return Optional.empty();
    }
    var values = Collections.list(request.getHeaders("X-API-Key"));
    if (values.isEmpty()) {
      return Optional.empty();
    }
    if (values.size() != 1 || values.get(0).isBlank() || values.get(0).length() > 4096) {
      throw new SecurityException("Invalid API key credential");
    }
    byte[] digest;
    try {
      digest =
          MessageDigest.getInstance("SHA-256")
              .digest(values.get(0).getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    EnvoyProperties.ApiKey match = null;
    for (var configured : properties.apiKeys()) {
      if (MessageDigest.isEqual(digest, HexFormat.of().parseHex(configured.sha256()))) {
        if (match != null) {
          throw new IllegalStateException("Duplicate API key mapping");
        }
        match = configured;
      }
    }
    if (match == null) {
      throw new SecurityException("Invalid API key credential");
    }
    return Optional.of(new VerifiedIdentity(match.userId(), match.apiKeyId(), match.attributes()));
  }
}
