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
 * Test API demonstrating multiple rate limit filters.
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>/api/test - Uses "standalone-rules" (10 req/min)
 *   <li>/api/test/multi-filter - Uses "multi-filter-rules" (5 req/sec + 20 req/min)
 * </ul>
 */
@RestController
@RequestMapping("/api/test")
@Tag(name = "Test", description = "Rate-limited test API")
public class TestController {

  private static final Logger log = LoggerFactory.getLogger(TestController.class);
  private final AtomicLong standaloneCounter = new AtomicLong(0);
  private final AtomicLong multiFilterCounter = new AtomicLong(0);

  /**
   * Standard test endpoint.
   *
   * <p>Rate limit: 10 requests per minute per IP (standalone-rules)
   */
  @GetMapping
  @Operation(
      summary = "Standard rate-limited endpoint",
      description =
          "Rate-limited to 10 requests per minute per IP. "
              + "Uses 'standalone-rules' rule set. "
              + "Call POST /api/admin/rules/standalone first to create rules.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Request allowed"),
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
  })
  public ResponseEntity<Map<String, Object>> test(HttpServletRequest request) {
    long count = standaloneCounter.incrementAndGet();
    String clientIp = getClientIp(request);

    log.info("[standalone] Request #{} from IP: {}", count, clientIp);

    Map<String, Object> response = new HashMap<>();
    response.put("success", true);
    response.put("endpoint", "/api/test");
    response.put("ruleSetId", "standalone-rules");
    response.put("limit", "10 req/min");
    response.put("requestNumber", count);
    response.put("clientIp", clientIp);
    response.put("timestamp", Instant.now().toString());

    return ResponseEntity.ok(response);
  }

  /**
   * Multi-filter test endpoint.
   *
   * <p>Rate limits (both rules apply - rejected if ANY rule exceeded):
   *
   * <ul>
   *   <li>Rule 1: 10 requests per minute
   *   <li>Rule 2: 20 requests per minute
   * </ul>
   */
  @GetMapping("/multi-filter")
  @Operation(
      summary = "Multi-rule rate-limited endpoint",
      description =
          "Rate-limited by 2 rules: 10 req/min (rule1) + 20 req/min (rule2). "
              + "Both rules apply - rejected if ANY rule exceeded. "
              + "Uses 'multi-filter-rules' rule set. "
              + "Call POST /api/admin/rules/multi-filter first to create rules.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Request allowed"),
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
  })
  public ResponseEntity<Map<String, Object>> multiFilterTest(HttpServletRequest request) {
    long count = multiFilterCounter.incrementAndGet();
    String clientIp = getClientIp(request);

    log.info("[multi-filter] Request #{} from IP: {}", count, clientIp);

    Map<String, Object> response = new HashMap<>();
    response.put("success", true);
    response.put("endpoint", "/api/test/multi-filter");
    response.put("ruleSetId", "multi-filter-rules");
    response.put("limits", Map.of("rule1", "10 req/min", "rule2", "20 req/min"));
    response.put("note", "Both rules apply - rejected if ANY rule exceeded");
    response.put("requestNumber", count);
    response.put("clientIp", clientIp);
    response.put("timestamp", Instant.now().toString());

    return ResponseEntity.ok(response);
  }

  /** Get rate limit configuration info. */
  @GetMapping("/info")
  @Operation(
      summary = "Get rate limit info",
      description = "Returns information about all rate limiting configurations")
  public ResponseEntity<Map<String, Object>> info() {
    Map<String, Object> response = new HashMap<>();
    response.put(
        "endpoints",
        Map.of(
            "/api/test",
            Map.of(
                "ruleSetId",
                "standalone-rules",
                "limit",
                "10 req/min",
                "requests",
                standaloneCounter.get()),
            "/api/test/multi-filter",
            Map.of(
                "ruleSetId",
                "multi-filter-rules",
                "limits",
                Map.of("rule1", "10 req/min", "rule2", "20 req/min"),
                "requests",
                multiFilterCounter.get())));
    response.put(
        "setup",
        Map.of(
            "standalone", "POST /api/admin/rules/standalone",
            "multiFilter", "POST /api/admin/rules/multi-filter",
            "all", "POST /api/admin/rules/all"));

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
