package org.fluxgate.sample.redis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.redis.store.RedisRuleSetStore;
import org.fluxgate.redis.store.RuleSetData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for rate limit checking.
 *
 * <p>This controller provides the HTTP API endpoint that client applications (like
 * fluxgate-sample-filter) can call to check rate limits.
 */
@RestController
@RequestMapping("/api/ratelimit")
@Tag(name = "Rate Limit API", description = "API for rate limit checking")
public class RateLimitController {

  private static final Logger log = LoggerFactory.getLogger(RateLimitController.class);

  private final RateLimiter rateLimiter;
  private final RedisRuleSetStore ruleSetStore;

  public RateLimitController(RateLimiter rateLimiter, RedisRuleSetStore ruleSetStore) {
    this.rateLimiter = rateLimiter;
    this.ruleSetStore = ruleSetStore;
    log.info("RateLimitController initialized");
  }

  @PostConstruct
  public void init() {
    // Register default RuleSet if not exists
    if (!ruleSetStore.exists("api-limits")) {
      RuleSetData defaultData =
          new RuleSetData(
              "api-limits",
              10, // 10 requests
              60, // per 60 seconds (1 minute)
              "clientIp");
      ruleSetStore.save(defaultData);
      log.info("Registered default RuleSet 'api-limits' in Redis: 10 req/min");
    } else {
      log.info("RuleSet 'api-limits' already exists in Redis");
    }
  }

  @Operation(
      summary = "Check rate limit",
      description = "Checks if a request is allowed based on the specified rule set")
  @ApiResponse(responseCode = "200", description = "Rate limit check result")
  @PostMapping("/check")
  public ResponseEntity<RateLimitCheckResponse> checkRateLimit(
      @RequestBody RateLimitCheckRequest request) {
    log.debug(
        "Rate limit check request: ruleSetId={}, clientIp={}", request.ruleSetId, request.clientIp);

    // Load RuleSet from Redis
    Optional<RuleSetData> ruleSetDataOpt = ruleSetStore.findById(request.ruleSetId);

    if (ruleSetDataOpt.isEmpty()) {
      log.warn("RuleSet not found: {}, allowing request", request.ruleSetId);
      return ResponseEntity.ok(new RateLimitCheckResponse(true, -1, 0));
    }

    RuleSetData ruleSetData = ruleSetDataOpt.get();

    // Build RateLimitRuleSet from RuleSetData
    RateLimitRuleSet ruleSet = buildRuleSet(ruleSetData, request);

    // Build RequestContext
    RequestContext context =
        RequestContext.builder()
            .clientIp(request.clientIp)
            .userId(request.userId)
            .apiKey(request.apiKey)
            .endpoint(request.endpoint)
            .method(request.method)
            .build();

    // Execute rate limiting
    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    // Build response
    RateLimitCheckResponse response;
    if (result.isAllowed()) {
      response = new RateLimitCheckResponse(true, result.getRemainingTokens(), 0);
      log.debug("Request ALLOWED: remaining={}", result.getRemainingTokens());
    } else {
      long retryAfterMs = result.getNanosToWaitForRefill() / 1_000_000;
      response = new RateLimitCheckResponse(false, 0, retryAfterMs);
      log.info("Request REJECTED: retryAfter={}ms", retryAfterMs);
    }

    return ResponseEntity.ok(response);
  }

  private RateLimitRuleSet buildRuleSet(RuleSetData data, RateLimitCheckRequest request) {
    // Determine LimitScope based on keyStrategyId
    LimitScope scope;
    switch (data.getKeyStrategyId()) {
      case "userId":
        scope = LimitScope.PER_USER;
        break;
      case "apiKey":
        scope = LimitScope.PER_API_KEY;
        break;
      default:
        scope = LimitScope.PER_IP;
        break;
    }

    // Build a rate limit rule from the RuleSetData
    RateLimitRule rule =
        RateLimitRule.builder(data.getRuleSetId() + "-rule")
            .name("Dynamic Rule - " + data.getCapacity() + " per " + data.getWindowSeconds() + "s")
            .enabled(true)
            .ruleSetId(data.getRuleSetId())
            .scope(scope)
            .keyStrategyId(data.getKeyStrategyId())
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(
                RateLimitBand.builder(
                        Duration.ofSeconds(data.getWindowSeconds()), data.getCapacity())
                    .label(data.getCapacity() + "-per-" + data.getWindowSeconds() + "s")
                    .build())
            .build();

    return RateLimitRuleSet.builder(data.getRuleSetId())
        .rules(List.of(rule))
        .keyResolver(new LimitScopeKeyResolver())
        .build();
  }

  /** Request body for rate limit check. */
  public static class RateLimitCheckRequest {
    public String ruleSetId;
    public String clientIp;
    public String userId;
    public String apiKey;
    public String endpoint;
    public String method;

    public RateLimitCheckRequest() {}
  }

  /** Response for rate limit check. */
  public static class RateLimitCheckResponse {
    public boolean allowed;
    public long remaining;
    public long retryAfterMs;

    public RateLimitCheckResponse() {}

    public RateLimitCheckResponse(boolean allowed, long remaining, long retryAfterMs) {
      this.allowed = allowed;
      this.remaining = remaining;
      this.retryAfterMs = retryAfterMs;
    }
  }
}
