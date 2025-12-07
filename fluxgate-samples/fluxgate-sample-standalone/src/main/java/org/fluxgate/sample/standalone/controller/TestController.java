package org.fluxgate.sample.standalone.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test API that is rate-limited by @EnableFluxgateFilter.
 *
 * <p>This endpoint is limited to 10 requests per minute per IP address. The rate limit is enforced
 * by the FluxgateRateLimitFilter.
 */
@RestController
@RequestMapping("/api/test")
@Tag(name = "Test", description = "Rate-limited test API")
public class TestController {

  private static final Logger log = LoggerFactory.getLogger(TestController.class);
  private final AtomicLong requestCounter = new AtomicLong(0);

  @GetMapping
  @Operation(
      summary = "Rate-limited test endpoint",
      description =
          "This endpoint is rate-limited to 10 requests per minute per IP. "
              + "Make sure to call POST /api/admin/ruleset first to set up the rules.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Request allowed"),
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
  })
  public ResponseEntity<Map<String, Object>> test(HttpServletRequest request) {
    long count = requestCounter.incrementAndGet();
    String clientIp = getClientIp(request);

    log.info("Test API called - Request #{} from IP: {}", count, clientIp);

    Map<String, Object> response = new HashMap<>();
    response.put("success", true);
    response.put("message", "Request successful!");
    response.put("requestNumber", count);
    response.put("clientIp", clientIp);
    response.put("timestamp", Instant.now().toString());
    response.put("note", "This endpoint is rate-limited to 10 requests per minute per IP.");

    return ResponseEntity.ok(response);
  }

  @GetMapping("/info")
  @Operation(
      summary = "Get rate limit info",
      description = "Returns information about the rate limiting configuration")
  public ResponseEntity<Map<String, Object>> info() {
    Map<String, Object> response = new HashMap<>();
    response.put("endpoint", "/api/test");
    response.put(
        "rateLimit",
        Map.of(
            "requests", 10,
            "window", "1 minute",
            "scope", "per IP address"));
    response.put("totalRequestsReceived", requestCounter.get());
    response.put("note", "Rate limit headers (X-RateLimit-*) are included in responses");

    return ResponseEntity.ok(response);
  }

  private String getClientIp(HttpServletRequest request) {
    String xForwardedFor = request.getHeader("X-Forwarded-For");
    if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
      return xForwardedFor.split(",")[0].trim();
    }
    String xRealIp = request.getHeader("X-Real-IP");
    if (xRealIp != null && !xRealIp.isEmpty()) {
      return xRealIp;
    }
    return request.getRemoteAddr();
  }
}
