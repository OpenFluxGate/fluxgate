package org.fluxgate.sample.standalone.java11.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test API demonstrating multiple rate limit filters.
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>/api/test - Uses "standalone-rules" (10 req/min per IP)
 *   <li>/api/test/multi-filter - Uses "multi-filter-rules" (10 req/min + 20 req/min)
 *   <li>/api/test/composite - Uses "composite-key-rules" (10 req/min per IP+User)
 * </ul>
 */
@RestController
@RequestMapping("/api/test/filter")
@Tag(name = "Test", description = "Rate-limited Filter-Test API")
public class FilterTestController {

  private static final Logger log = LoggerFactory.getLogger(FilterTestController.class);
  private final AtomicLong standaloneCounter = new AtomicLong(0);
  private final AtomicLong multiFilterCounter = new AtomicLong(0);
  private final AtomicLong compositeCounter = new AtomicLong(0);

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

    Map<String, String> limits = new HashMap<>();
    limits.put("rule1", "10 req/min");
    limits.put("rule2", "20 req/min");
    response.put("limits", limits);

    response.put("note", "Both rules apply - rejected if ANY rule exceeded");
    response.put("requestNumber", count);
    response.put("clientIp", clientIp);
    response.put("timestamp", Instant.now().toString());

    return ResponseEntity.ok(response);
  }

  /**
   * Composite key test endpoint.
   *
   * <p>Rate limit: 10 requests per minute per IP+User combination.
   *
   * <p>The composite key is built by combining client IP and user ID: "192.168.1.100:user-123"
   *
   * <p>Usage examples:
   *
   * <ul>
   *   <li>curl http://localhost:8085/api/test/composite (uses IP only)
   *   <li>curl -H "X-User-Id: user-123" http://localhost:8085/api/test/composite (uses IP:user-123)
   * </ul>
   *
   * <p>Different users from the same IP have separate rate limits.
   */
  @GetMapping("/composite")
  @Operation(
      summary = "Composite key rate-limited endpoint",
      description =
          "Rate-limited to 10 requests per minute per IP+User combination. "
              + "Uses 'composite-key-rules' rule set with CUSTOM scope. "
              + "Provide X-User-Id header to set user identifier. "
              + "Call POST /api/admin/rules/composite first to create rules.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Request allowed"),
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
  })
  public ResponseEntity<Map<String, Object>> compositeTest(
      HttpServletRequest request, @RequestParam(value = "userId") String userId) {
    long count = compositeCounter.incrementAndGet();
    String clientIp = getClientIp(request);
    String compositeKey = userId != null ? clientIp + ":" + userId : clientIp;

    log.info("[composite] Request #{} from compositeKey: {}", count, compositeKey);

    Map<String, Object> response = new HashMap<>();
    response.put("success", true);
    response.put("endpoint", "/api/test/composite");
    response.put("ruleSetId", "composite-key-rules");
    response.put("limit", "10 req/min per IP+User");
    response.put("compositeKey", compositeKey);
    response.put("clientIp", clientIp);
    response.put("userId", userId != null ? userId : "(not provided)");
    response.put("requestNumber", count);
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

    Map<String, Object> endpoints = new HashMap<>();

    Map<String, Object> standaloneInfo = new HashMap<>();
    standaloneInfo.put("ruleSetId", "standalone-rules");
    standaloneInfo.put("limit", "10 req/min per IP");
    standaloneInfo.put("requests", standaloneCounter.get());
    endpoints.put("/api/test", standaloneInfo);

    Map<String, Object> multiFilterInfo = new HashMap<>();
    multiFilterInfo.put("ruleSetId", "multi-filter-rules");
    Map<String, String> multiFilterLimits = new HashMap<>();
    multiFilterLimits.put("rule1", "10 req/min");
    multiFilterLimits.put("rule2", "20 req/min");
    multiFilterInfo.put("limits", multiFilterLimits);
    multiFilterInfo.put("requests", multiFilterCounter.get());
    endpoints.put("/api/test/multi-filter", multiFilterInfo);

    Map<String, Object> compositeInfo = new HashMap<>();
    compositeInfo.put("ruleSetId", "composite-key-rules");
    compositeInfo.put("limit", "10 req/min per IP+User");
    compositeInfo.put("keyStrategy", "ipUser (IP:userId composite)");
    compositeInfo.put("requests", compositeCounter.get());
    endpoints.put("/api/test/composite", compositeInfo);

    response.put("endpoints", endpoints);

    Map<String, String> setup = new HashMap<>();
    setup.put("standalone", "POST /api/admin/rules/standalone");
    setup.put("multiFilter", "POST /api/admin/rules/multi-filter");
    setup.put("composite", "POST /api/admin/rules/composite");
    response.put("setup", setup);

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
