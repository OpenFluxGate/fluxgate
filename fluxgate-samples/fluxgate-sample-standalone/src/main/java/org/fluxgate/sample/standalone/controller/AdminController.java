package org.fluxgate.sample.standalone.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Admin API for managing rate limit rules.
 * <p>
 * Endpoints:
 * - POST /api/admin/ruleset - Create/update the default ruleset (10 requests/minute)
 * - POST /api/admin/sync - Sync rules (just confirms setup is ready)
 * - GET /api/admin/ruleset - Get current ruleset
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin", description = "Rate limit rule management APIs")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final String DEFAULT_RULESET_ID = "standalone-rules";

    private final RateLimitRuleSetProvider ruleSetProvider;
    private final RateLimitRuleRepository ruleRepository;

    public AdminController(
            RateLimitRuleSetProvider ruleSetProvider,
            RateLimitRuleRepository ruleRepository) {
        this.ruleSetProvider = ruleSetProvider;
        this.ruleRepository = ruleRepository;
    }

    @PostMapping("/ruleset")
    @Operation(summary = "Create default ruleset",
               description = "Creates a rate limit ruleset with 10 requests per minute limit")
    public ResponseEntity<Map<String, Object>> createRuleSet() {
        log.info("Creating default ruleset: {}", DEFAULT_RULESET_ID);

        // Create a rule: 10 requests per minute per IP
        RateLimitRule rule = RateLimitRule.builder("rate-limit-rule-1")
                .name("Standard Rate Limit")
                .enabled(true)
                .scope(LimitScope.PER_IP)
                .keyStrategyId("ip")
                .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
                .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10)
                        .label("per-minute")
                        .build())
                .ruleSetId(DEFAULT_RULESET_ID)
                .build();

        // Save rule to MongoDB via repository
        ruleRepository.save(rule);
        log.info("Rule saved to MongoDB: {}", rule.getId());

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("ruleSetId", DEFAULT_RULESET_ID);
        response.put("ruleId", rule.getId());
        response.put("limit", "10 requests per minute per IP");
        response.put("createdAt", Instant.now().toString());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/ruleset")
    @Operation(summary = "Get current ruleset",
               description = "Retrieves the current rate limit ruleset from MongoDB")
    public ResponseEntity<Map<String, Object>> getRuleSet() {
        Optional<RateLimitRuleSet> ruleSetOpt = ruleSetProvider.findById(DEFAULT_RULESET_ID);

        Map<String, Object> response = new HashMap<>();
        if (ruleSetOpt.isEmpty()) {
            response.put("exists", false);
            response.put("message", "RuleSet not found. Call POST /api/admin/ruleset to create it.");
            return ResponseEntity.ok(response);
        }

        RateLimitRuleSet ruleSet = ruleSetOpt.get();
        response.put("exists", true);
        response.put("id", ruleSet.getId());
        response.put("description", ruleSet.getDescription());
        response.put("rulesCount", ruleSet.getRules().size());
        response.put("rules", ruleSet.getRules().stream()
                .map(rule -> Map.of(
                        "id", rule.getId(),
                        "name", rule.getName(),
                        "enabled", rule.isEnabled(),
                        "scope", rule.getScope().name(),
                        "bands", rule.getBands().stream()
                                .map(band -> Map.of(
                                        "label", band.getLabel(),
                                        "capacity", band.getCapacity(),
                                        "windowSeconds", band.getWindow().toSeconds()
                                ))
                                .toList()
                ))
                .toList());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/sync")
    @Operation(summary = "Sync rules to Redis",
               description = "Confirms that rules are synced and ready for rate limiting")
    public ResponseEntity<Map<String, Object>> syncRules() {
        log.info("Syncing rules to Redis...");

        Optional<RateLimitRuleSet> ruleSetOpt = ruleSetProvider.findById(DEFAULT_RULESET_ID);

        Map<String, Object> response = new HashMap<>();
        if (ruleSetOpt.isEmpty()) {
            response.put("success", false);
            response.put("message", "RuleSet not found. Call POST /api/admin/ruleset first.");
            return ResponseEntity.badRequest().body(response);
        }

        RateLimitRuleSet ruleSet = ruleSetOpt.get();

        // In a real application, you might want to:
        // 1. Pre-load rules into a cache
        // 2. Initialize Redis token buckets
        // 3. Warm up connections

        response.put("success", true);
        response.put("ruleSetId", ruleSet.getId());
        response.put("rulesCount", ruleSet.getRules().size());
        response.put("status", "synced");
        response.put("message", "Rules are ready for rate limiting. " +
                "Rate limiter will use Redis for token bucket operations.");
        response.put("syncedAt", Instant.now().toString());

        log.info("Rules synced successfully: {} rules", ruleSet.getRules().size());
        return ResponseEntity.ok(response);
    }
}
