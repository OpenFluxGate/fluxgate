package org.fluxgate.sample.redis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.sample.redis.config.DynamicRuleSetProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API endpoints for testing rate limiting with Redis. */
@RestController
@RequestMapping("/api")
@Tag(name = "API", description = "Test rate limiting with different RuleSets")
public class ApiController {

  private static final Logger log = LoggerFactory.getLogger(ApiController.class);

  private final AtomicLong requestCounter = new AtomicLong(0);
  private final RateLimiter rateLimiter;
  private final DynamicRuleSetProvider ruleSetProvider;

  public ApiController(RateLimiter rateLimiter, DynamicRuleSetProvider ruleSetProvider) {
    this.rateLimiter = rateLimiter;
    this.ruleSetProvider = ruleSetProvider;
  }

  @Operation(
      summary = "Test rate limiting",
      description =
          "Tests rate limiting with the specified RuleSet. Returns 429 if rate limit exceeded.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Request allowed"),
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded"),
    @ApiResponse(responseCode = "400", description = "RuleSet not found")
  })
  @GetMapping("/test")
  public ResponseEntity<Map<String, Object>> testRateLimit(
      @Parameter(description = "RuleSet ID to apply", example = "api-limits")
          @RequestParam(value = "ruleSetId", defaultValue = "api-limits")
          String ruleSetId,
      HttpServletRequest request) {

    long count = requestCounter.incrementAndGet();
    String clientIp = getClientIp(request);

    log.info("Request #{} from {} using ruleSetId: {}", count, clientIp, ruleSetId);

    // Find the RuleSet
    Optional<RateLimitRuleSet> ruleSetOpt = ruleSetProvider.findById(ruleSetId);
    if (ruleSetOpt.isEmpty()) {
      return ResponseEntity.badRequest()
          .body(
              Map.of(
                  "error",
                  "RuleSet not found",
                  "ruleSetId",
                  ruleSetId,
                  "availableRuleSets",
                  ruleSetProvider.listRuleSetIds()));
    }

    RateLimitRuleSet ruleSet = ruleSetOpt.get();

    // Build RequestContext
    RequestContext context =
        RequestContext.builder()
            .clientIp(clientIp)
            .endpoint(request.getRequestURI())
            .method(request.getMethod())
            .build();

    // Try to consume
    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    if (result.isAllowed()) {
      return ResponseEntity.ok(
          Map.of(
              "status",
              "ALLOWED",
              "requestNumber",
              count,
              "ruleSetId",
              ruleSetId,
              "remainingTokens",
              result.getRemainingTokens(),
              "clientIp",
              clientIp,
              "timestamp",
              Instant.now().toString()));
    } else {
      long retryAfterSeconds = Math.max(1, result.getNanosToWaitForRefill() / 1_000_000_000);
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
          .header("Retry-After", String.valueOf(retryAfterSeconds))
          .body(
              Map.of(
                  "status", "REJECTED",
                  "error", "Rate limit exceeded",
                  "requestNumber", count,
                  "ruleSetId", ruleSetId,
                  "retryAfterSeconds", retryAfterSeconds,
                  "clientIp", clientIp,
                  "timestamp", Instant.now().toString()));
    }
  }

  @Operation(
      summary = "Hello endpoint (default rate limit)",
      description = "Simple hello endpoint using default 'api-limits' RuleSet (10 RPM)")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Request allowed"),
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
  })
  @GetMapping("/hello")
  public ResponseEntity<Map<String, Object>> hello(HttpServletRequest request) {
    return testRateLimit("api-limits", request);
  }

  @Operation(summary = "Status endpoint", description = "Returns service status (no rate limiting)")
  @ApiResponse(responseCode = "200", description = "Status returned")
  @GetMapping("/status")
  public ResponseEntity<Map<String, Object>> status() {
    return ResponseEntity.ok(
        Map.of(
            "status", "UP",
            "totalRequests", requestCounter.get(),
            "availableRuleSets", ruleSetProvider.listRuleSetIds(),
            "timestamp", Instant.now().toString()));
  }

  private String getClientIp(HttpServletRequest request) {
    String xForwardedFor = request.getHeader("X-Forwarded-For");
    if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
      return xForwardedFor.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
