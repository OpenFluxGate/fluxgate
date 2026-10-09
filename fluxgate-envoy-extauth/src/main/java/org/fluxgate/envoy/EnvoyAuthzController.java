package org.fluxgate.envoy;

import jakarta.servlet.http.HttpServletRequest;
import org.fluxgate.core.context.RequestContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** Receives Envoy's HTTP extAuth subrequest at /authz plus the original path. */
@RestController
public final class EnvoyAuthzController {
  private final AuthzDecisionService service;

  public EnvoyAuthzController(AuthzDecisionService service) {
    this.service = service;
  }

  @GetMapping("/healthz")
  public ResponseEntity<Void> health() {
    return ResponseEntity.ok().build();
  }

  @RequestMapping("/authz/**")
  public ResponseEntity<Void> authorize(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String originalPath = uri.substring("/authz".length());
    if (originalPath.isEmpty()) {
      originalPath = "/";
    }
    RequestContext context =
        RequestContext.builder()
            .clientIp(request.getRemoteAddr())
            .endpoint(originalPath)
            .method(request.getMethod())
            .build();
    AuthzDecision decision = service.decide(context);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatusCode.valueOf(decision.status()));
    if (decision.status() == 429) {
      response.header(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
    }
    return response.build();
  }

  /** Spring otherwise answers OPTIONS automatically with 200, bypassing the decision. */
  @RequestMapping(value = "/authz/**", method = RequestMethod.OPTIONS)
  public ResponseEntity<Void> authorizeOptions(HttpServletRequest request) {
    return authorize(request);
  }
}
