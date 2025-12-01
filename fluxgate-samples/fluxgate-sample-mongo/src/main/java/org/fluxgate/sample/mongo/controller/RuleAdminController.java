package org.fluxgate.sample.mongo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST API for managing rate limit rules in MongoDB.
 * <p>
 * Uses the {@link RateLimitRuleRepository} interface, allowing for different
 * storage implementations (MongoDB, JDBC, etc.).
 */
@RestController
@RequestMapping("/admin/rules")
@Tag(name = "Rule Admin", description = "Manage rate limit rules in MongoDB")
public class RuleAdminController {

    private static final Logger log = LoggerFactory.getLogger(RuleAdminController.class);

    private final RateLimitRuleRepository ruleRepository;

    public RuleAdminController(RateLimitRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @Operation(
            summary = "List rules by ruleSetId",
            description = "Retrieves all rate limit rules belonging to the specified rule set"
    )
    @ApiResponse(responseCode = "200", description = "Rules retrieved successfully")
    @GetMapping
    public ResponseEntity<List<RateLimitRule>> listRules(
            @Parameter(description = "Rule set identifier", required = true, example = "api-gateway-rules")
            @RequestParam(value = "ruleSetId") String ruleSetId) {

        List<RateLimitRule> rules = ruleRepository.findByRuleSetId(ruleSetId);
        log.info("Found {} rules for ruleSetId: {}", rules.size(), ruleSetId);

        return ResponseEntity.ok(rules);
    }

    @Operation(
            summary = "Get all rules",
            description = "Retrieves all rate limit rules"
    )
    @ApiResponse(responseCode = "200", description = "Rules retrieved successfully")
    @GetMapping("/all")
    public ResponseEntity<List<RateLimitRule>> getAllRules() {
        List<RateLimitRule> rules = ruleRepository.findAll();
        log.info("Found {} total rules", rules.size());
        return ResponseEntity.ok(rules);
    }

    @Operation(
            summary = "Get rule by ID",
            description = "Retrieves a specific rate limit rule by its ID"
    )
    @ApiResponse(responseCode = "200", description = "Rule found")
    @ApiResponse(responseCode = "404", description = "Rule not found")
    @GetMapping("/{id}")
    public ResponseEntity<RateLimitRule> getRuleById(
            @Parameter(description = "Rule ID", required = true, example = "api-rate-limit-100rpm")
            @PathVariable String id) {

        Optional<RateLimitRule> rule = ruleRepository.findById(id);
        if (rule.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(rule.get());
    }

    @Operation(
            summary = "Delete a rule",
            description = "Deletes a rate limit rule by its ID"
    )
    @ApiResponse(responseCode = "204", description = "Rule deleted successfully")
    @ApiResponse(responseCode = "404", description = "Rule not found")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRule(
            @Parameter(description = "Rule ID", required = true, example = "api-rate-limit-100rpm")
            @PathVariable String id) {

        boolean deleted = ruleRepository.deleteById(id);
        if (deleted) {
            log.info("Deleted rule: {}", id);
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(
            summary = "Delete all rules in a rule set",
            description = "Deletes all rate limit rules belonging to the specified rule set"
    )
    @ApiResponse(responseCode = "200", description = "Rules deleted successfully")
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> deleteRuleSet(
            @Parameter(description = "Rule set identifier", required = true, example = "api-gateway-rules")
            @RequestParam(value = "ruleSetId") String ruleSetId) {

        int deleted = ruleRepository.deleteByRuleSetId(ruleSetId);
        log.info("Deleted {} rules for ruleSetId: {}", deleted, ruleSetId);

        return ResponseEntity.ok(Map.of(
                "message", "Rules deleted successfully",
                "ruleSetId", ruleSetId,
                "rulesDeleted", deleted
        ));
    }

    @Operation(
            summary = "Create sample rules",
            description = "Creates 3 sample rate limit rules for testing: 100 RPM per IP, 10 RPS burst limit, 1000 per hour per user"
    )
    @ApiResponse(responseCode = "201", description = "Sample rules created successfully")
    @PostMapping("/sample")
    public ResponseEntity<Map<String, Object>> createSampleRules() {
        log.info("Creating sample rules...");

        String ruleSetId = "api-gateway-rules";

        // Rule 1: 100 requests per minute per IP
        RateLimitRule rule1 = RateLimitRule.builder("api-rate-limit-100rpm")
                .name("API Rate Limit - 100 RPM")
                .enabled(true)
                .scope(LimitScope.PER_IP)
                .keyStrategyId("clientIp")
                .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
                .addBand(RateLimitBand.builder(Duration.ofSeconds(60), 100)
                        .label("100-per-minute")
                        .build())
                .ruleSetId(ruleSetId)
                .build();
        ruleRepository.save(rule1);

        // Rule 2: 10 requests per second per IP (burst protection)
        RateLimitRule rule2 = RateLimitRule.builder("api-burst-limit-10rps")
                .name("API Burst Limit - 10 RPS")
                .enabled(true)
                .scope(LimitScope.PER_IP)
                .keyStrategyId("clientIp")
                .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
                .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10)
                        .label("10-per-second")
                        .build())
                .ruleSetId(ruleSetId)
                .build();
        ruleRepository.save(rule2);

        // Rule 3: 1000 requests per hour per user
        RateLimitRule rule3 = RateLimitRule.builder("user-hourly-limit")
                .name("User Hourly Limit")
                .enabled(true)
                .scope(LimitScope.PER_USER)
                .keyStrategyId("userId")
                .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
                .addBand(RateLimitBand.builder(Duration.ofSeconds(3600), 1000)
                        .label("1000-per-hour")
                        .build())
                .ruleSetId(ruleSetId)
                .build();
        ruleRepository.save(rule3);

        log.info("Created 3 sample rules");

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "message", "Sample rules created successfully",
                "ruleSetId", ruleSetId,
                "rulesCreated", 3
        ));
    }
}
