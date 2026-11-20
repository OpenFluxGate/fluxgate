package org.fluxgate.envoy;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** Receives Envoy HTTP extAuth checks using authenticated, server-owned policy bindings. */
@RestController
public final class EnvoyAuthzController {
  private final AuthzDecisionService service;
  private final EnvoyRequestAdapter adapter;
  private final EnvoyProperties properties;

  public EnvoyAuthzController(
      AuthzDecisionService service, EnvoyRequestAdapter adapter, EnvoyProperties properties) {
    this.service = service;
    this.adapter = adapter;
    this.properties = properties;
  }

  @GetMapping("/healthz")
  public ResponseEntity<Void> health() {
    return ResponseEntity.ok().build();
  }

  @GetMapping("/readyz")
  public ResponseEntity<Void> ready() {
    boolean ready =
        properties.routes().stream().allMatch(route -> service.isReady(route.ruleSetId()));
    return ResponseEntity.status(ready ? 200 : 503).build();
  }

  @RequestMapping("/authz/**")
  public ResponseEntity<Void> authorize(HttpServletRequest request) {
    if (!adapter.isAuthorizedPeer(request)) {
      return ResponseEntity.status(403).build();
    }
    try {
      var adapted = adapter.adapt(request);
      if (adapted.isEmpty()) {
        return ResponseEntity.status(503).build();
      }
      var check = adapted.get();
      AuthzDecision decision =
          service.decide(check.route().ruleSetId(), check.context(), check.route().permits());
      ResponseEntity.BodyBuilder response = ResponseEntity.status(decision.status());
      if (decision.status() == 429) {
        response.header(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
      }
      return response.build();
    } catch (SecurityException e) {
      return ResponseEntity.status(403).build();
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().build();
    } catch (RuntimeException e) {
      return ResponseEntity.status(503).build();
    }
  }

  /** Spring otherwise answers OPTIONS automatically before evaluating authorization. */
  @RequestMapping(value = "/authz/**", method = RequestMethod.OPTIONS)
  public ResponseEntity<Void> authorizeOptions(HttpServletRequest request) {
    return authorize(request);
  }
}
