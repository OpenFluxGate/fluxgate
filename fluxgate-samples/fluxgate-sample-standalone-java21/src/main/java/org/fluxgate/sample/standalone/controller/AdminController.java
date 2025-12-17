package org.fluxgate.sample.standalone.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Admin API for managing rate limit rules.
 *
 * <p>This controller provides APIs to create different rule sets:
 *
 * <ul>
 *   <li>POST /api/admin/rules/standalone - Create rule for /api/test (10 req/min)
 *   <li>POST /api/admin/rules/multi-filter - Create 2 rules for /api/test/multi-filter (5 req/sec +
 *       20 req/min)
 *   <li>POST /api/admin/rules/all - Create all rules at once
 *   <li>GET /api/admin/rules/{ruleSetId} - Get rules for a specific rule set
 * </ul>
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin", description = "Rate limit rule management APIs")
public class AdminController {

  private static final Logger log = LoggerFactory.getLogger(AdminController.class);

  // Rule Set IDs
  private static final String STANDALONE_RULESET_ID = "standalone-rules";
  private static final String MULTI_FILTER_RULESET_ID = "multi-filter-rules";
  private static final String COMPOSITE_KEY_RULESET_ID = "composite-key-rules";

  private final RateLimitRuleSetProvider ruleSetProvider;
  private final RateLimitRuleRepository ruleRepository;

  public AdminController(
      RateLimitRuleSetProvider ruleSetProvider, RateLimitRuleRepository ruleRepository) {
    this.ruleSetProvider = ruleSetProvider;
    this.ruleRepository = ruleRepository;
  }

  /**
   * Create rule for /api/test endpoint.
   *
   * <p>Creates 1 rule: 10 requests per minute per IP
   */
  @PostMapping("/rules/standalone")
  @Operation(
      summary = "Create standalone rule",
      description =
          "Creates a rate limit rule for /api/test endpoint. "
              + "Limit: 10 requests per minute per IP. "
              + "RuleSet: standalone-rules")
  public ResponseEntity<Map<String, Object>> createStandaloneRule() {
    log.info("Creating standalone rule for /api/test");

    RateLimitRule rule =
        RateLimitRule.builder("standalone-10-per-minute")
            .name("Standalone API - 10 req/min")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .keyStrategyId("ip")
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).label("per-minute").build())
            .ruleSetId(STANDALONE_RULESET_ID)
            .attribute("endpoint", "/api/test")
            .attribute("description", "Standard rate limit for test API")
            .build();

    ruleRepository.save(rule);
    log.info("Standalone rule saved: {}", rule.getId());

    Map<String, Object> response = new HashMap<>();
    response.put("success", true);
    response.put("ruleSetId", STANDALONE_RULESET_ID);
    response.put("ruleId", rule.getId());
    response.put("endpoint", "/api/test");
    response.put("limit", "10 requests per minute per IP");
    response.put("createdAt", Instant.now().toString());

    return ResponseEntity.ok(response);
  }

  /**
   * Create rules for /api/test/multi-filter endpoint.
   *
   * <p>Creates 2 rules:
   *
   * <ul>
   *   <li>Rule 1: 10 requests per minute
   *   <li>Rule 2: 20 requests per minute
   * </ul>
   *
   * <p>Both rules apply - request is rejected if ANY rule is exceeded.
   */
  @PostMapping("/rules/multi-filter")
  @Operation(
      summary = "Create multi-filter rules",
      description =
          "Creates 2 rate limit rules for /api/test/multi-filter endpoint. "
              + "Rule 1: 10 requests per minute. "
              + "Rule 2: 20 requests per minute. "
              + "RuleSet: multi-filter-rules")
  public ResponseEntity<Map<String, Object>> createMultiFilterRules() {
    log.info("Creating multi-filter rules for /api/test/multi-filter");

    // Rule 1: 10 requests per minute
    RateLimitRule rule1 =
        RateLimitRule.builder("multi-filter-10-per-minute")
            .name("Multi-Filter API - Rule 1 (10 req/min)")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .keyStrategyId("ip")
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(
                RateLimitBand.builder(Duration.ofMinutes(1), 10).label("rule1-per-minute").build())
            .ruleSetId(MULTI_FILTER_RULESET_ID)
            .attribute("endpoint", "/api/test/multi-filter")
            .attribute("ruleNumber", "1")
            .attribute("description", "First rate limit rule - 10 requests per minute")
            .build();

    // Rule 2: 20 requests per minute
    RateLimitRule rule2 =
        RateLimitRule.builder("multi-filter-20-per-minute")
            .name("Multi-Filter API - Rule 2 (20 req/min)")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .keyStrategyId("ip")
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(
                RateLimitBand.builder(Duration.ofMinutes(1), 20).label("rule2-per-minute").build())
            .ruleSetId(MULTI_FILTER_RULESET_ID)
            .attribute("endpoint", "/api/test/multi-filter")
            .attribute("ruleNumber", "2")
            .attribute("description", "Second rate limit rule - 20 requests per minute")
            .build();

    ruleRepository.save(rule1);
    ruleRepository.save(rule2);
    log.info("Multi-filter rules saved: {}, {}", rule1.getId(), rule2.getId());

    Map<String, Object> response = new HashMap<>();
    response.put("success", true);
    response.put("ruleSetId", MULTI_FILTER_RULESET_ID);
    response.put("endpoint", "/api/test/multi-filter");
    response.put(
        "rules",
        Map.of(
            "rule1",
            Map.of("ruleId", rule1.getId(), "limit", "10 requests per minute"),
            "rule2",
            Map.of("ruleId", rule2.getId(), "limit", "20 requests per minute")));
    response.put("note", "Both rules apply - request rejected if ANY rule exceeded");
    response.put("createdAt", Instant.now().toString());

    return ResponseEntity.ok(response);
  }

  /**
   * Create rule for /api/test/composite endpoint.
   *
   * <p>Creates a rule with CUSTOM scope and keyStrategyId="ipUser". The RequestContextCustomizer
   * builds the composite key by combining IP and User ID: "192.168.1.100:user-123"
   *
   * <p>If X-User-Id header is not provided, falls back to IP only.
   */
  @PostMapping("/rules/composite")
  @Operation(
      summary = "Create composite key rule",
      description =
          "Creates a rate limit rule for /api/test/composite endpoint with composite key (IP+User). "
              + "Limit: 10 requests per minute per IP+User combination. "
              + "RuleSet: composite-key-rules. "
              + "Use X-User-Id header to provide user identifier.")
  public ResponseEntity<Map<String, Object>> createCompositeKeyRule() {
    log.info("Creating composite key rule for /api/test/composite");

    RateLimitRule rule =
        RateLimitRule.builder("composite-10-per-minute")
            .name("Composite Key API - 10 req/min per IP+User")
            .enabled(true)
            .scope(LimitScope.CUSTOM)
            .keyStrategyId("ipUser") // Custom key strategy - resolved from RequestContext attribute
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).label("per-minute").build())
            .ruleSetId(COMPOSITE_KEY_RULESET_ID)
            .attribute("endpoint", "/api/test/composite")
            .attribute("description", "Rate limit by IP+User composite key")
            .build();

    ruleRepository.save(rule);
    log.info("Composite key rule saved: {}", rule.getId());

    Map<String, Object> response = new HashMap<>();
    response.put("success", true);
    response.put("ruleSetId", COMPOSITE_KEY_RULESET_ID);
    response.put("ruleId", rule.getId());
    response.put("endpoint", "/api/test/composite");
    response.put("limit", "10 requests per minute per IP+User");
    response.put("keyStrategy", "ipUser (composite of IP and User ID)");
    response.put("usage", "curl -H 'X-User-Id: user-123' http://localhost:8085/api/test/composite");
    response.put("createdAt", Instant.now().toString());

    return ResponseEntity.ok(response);
  }

  /** Get rules for a specific rule set. */
  @GetMapping("/rules/{ruleSetId}")
  @Operation(
      summary = "Get rules by rule set ID",
      description = "Retrieves all rules for a specific rule set from MongoDB")
  public ResponseEntity<Map<String, Object>> getRuleSet(@PathVariable String ruleSetId) {
    Optional<RateLimitRuleSet> ruleSetOpt = ruleSetProvider.findById(ruleSetId);

    Map<String, Object> response = new HashMap<>();
    if (ruleSetOpt.isEmpty()) {
      response.put("exists", false);
      response.put("ruleSetId", ruleSetId);
      response.put(
          "message", "RuleSet not found. Create rules first using POST /api/admin/rules/*");
      return ResponseEntity.ok(response);
    }

    RateLimitRuleSet ruleSet = ruleSetOpt.get();
    response.put("exists", true);
    response.put("ruleSetId", ruleSet.getId());
    response.put("rulesCount", ruleSet.getRules().size());
    response.put(
        "rules",
        ruleSet.getRules().stream()
            .map(
                rule -> {
                  Map<String, Object> ruleMap = new HashMap<>();
                  ruleMap.put("id", rule.getId());
                  ruleMap.put("name", rule.getName());
                  ruleMap.put("enabled", rule.isEnabled());
                  ruleMap.put("scope", rule.getScope().name());
                  ruleMap.put(
                      "bands",
                      rule.getBands().stream()
                          .map(
                              band ->
                                  Map.of(
                                      "label", band.getLabel(),
                                      "capacity", band.getCapacity(),
                                      "windowSeconds", band.getWindow().toSeconds()))
                          .collect(java.util.stream.Collectors.toList()));
                  if (!rule.getAttributes().isEmpty()) {
                    ruleMap.put("attributes", rule.getAttributes());
                  }
                  return ruleMap;
                })
            .collect(java.util.stream.Collectors.toList()));

    return ResponseEntity.ok(response);
  }
}
