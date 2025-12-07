package org.fluxgate.sample.filter.handler;

import java.util.Map;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HTTP-based rate limit handler.
 *
 * <p>This handler calls an external FluxGate API server to check rate limits. Suitable for:
 *
 * <ul>
 *   <li>Distributed deployments without direct Redis access
 *   <li>Microservices that need centralized rate limiting
 *   <li>Applications behind a rate limiting gateway
 * </ul>
 *
 * <p>Configuration:
 *
 * <pre>
 * fluxgate:
 *   api:
 *     url: http://localhost:8080  # FluxGate API server URL
 * </pre>
 */
@Component
public class HttpRateLimitHandler implements FluxgateRateLimitHandler {

  private static final Logger log = LoggerFactory.getLogger(HttpRateLimitHandler.class);

  private final RestClient restClient;
  private final String apiUrl;

  public HttpRateLimitHandler(@Value("${fluxgate.api.url:http://localhost:8080}") String apiUrl) {
    this.apiUrl = apiUrl;
    this.restClient = RestClient.builder().baseUrl(apiUrl).build();
    log.info("HttpRateLimitHandler initialized with API URL: {}", apiUrl);
  }

  @Override
  public RateLimitResponse tryConsume(RequestContext context, String ruleSetId) {
    try {
      RateLimitApiResponse response =
          restClient
              .post()
              .uri("/api/ratelimit/check")
              .contentType(MediaType.APPLICATION_JSON)
              .body(
                  Map.of(
                      "ruleSetId", ruleSetId,
                      "clientIp", context.getClientIp() != null ? context.getClientIp() : "",
                      "userId", context.getUserId() != null ? context.getUserId() : "",
                      "apiKey", context.getApiKey() != null ? context.getApiKey() : "",
                      "endpoint", context.getEndpoint() != null ? context.getEndpoint() : "",
                      "method", context.getMethod() != null ? context.getMethod() : ""))
              .retrieve()
              .body(RateLimitApiResponse.class);

      if (response == null) {
        log.warn("Empty response from FluxGate API, allowing request");
        return RateLimitResponse.allowed(-1, 0);
      }

      if (response.allowed) {
        log.debug("Request allowed by FluxGate API: remaining={}", response.remaining);
        return RateLimitResponse.allowed(response.remaining, 0);
      } else {
        log.info("Request rejected by FluxGate API: retryAfter={}ms", response.retryAfterMs);
        return RateLimitResponse.rejected(response.retryAfterMs);
      }

    } catch (Exception e) {
      log.error("Failed to call FluxGate API at {}: {}", apiUrl, e.getMessage());
      // Fail open: allow request if API is unavailable
      return RateLimitResponse.allowed(-1, 0);
    }
  }

  /** Response from FluxGate API. */
  public static class RateLimitApiResponse {
    public boolean allowed;
    public long remaining;
    public long retryAfterMs;

    public RateLimitApiResponse() {}

    public RateLimitApiResponse(boolean allowed, long remaining, long retryAfterMs) {
      this.allowed = allowed;
      this.remaining = remaining;
      this.retryAfterMs = retryAfterMs;
    }
  }
}
