package org.fluxgate.sample.filter.controller;

// =============================================================================
// NOTE: This controller is for Redis direct mode (RedisRateLimitHandler).
//       Currently using HTTP API mode (HttpRateLimitHandler).
//
// To enable this controller:
//   1. Uncomment this class
//   2. Add fluxgate-redis-ratelimiter dependency to pom.xml
//   3. Set fluxgate.redis.enabled=true in application.yml
//   4. Change @EnableFluxgateFilter handler to RedisRateLimitHandler.class
//   5. Uncomment RuleSetConfig.java
// =============================================================================

/*
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.fluxgate.redis.store.RedisRuleSetStore;
import org.fluxgate.redis.store.RuleSetData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/admin/rulesets")
@Tag(name = "RuleSet Admin", description = "Manage RuleSets stored in Redis")
public class RuleSetAdminController {

    private static final Logger log = LoggerFactory.getLogger(RuleSetAdminController.class);

    private final RedisRuleSetStore ruleSetStore;

    public RuleSetAdminController(RedisRuleSetStore ruleSetStore) {
        this.ruleSetStore = ruleSetStore;
    }

    @Operation(
            summary = "List all RuleSets",
            description = "Returns all RuleSet IDs stored in Redis"
    )
    @ApiResponse(responseCode = "200", description = "RuleSet IDs returned")
    @GetMapping
    public ResponseEntity<Map<String, Object>> listRuleSets() {
        List<String> ruleSetIds = ruleSetStore.listAllIds();
        log.info("Found {} RuleSets in Redis", ruleSetIds.size());

        return ResponseEntity.ok(Map.of(
                "ruleSetIds", ruleSetIds,
                "count", ruleSetIds.size()
        ));
    }

    @Operation(
            summary = "Get RuleSet by ID",
            description = "Returns details of a specific RuleSet"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "RuleSet found"),
            @ApiResponse(responseCode = "404", description = "RuleSet not found")
    })
    @GetMapping("/{ruleSetId}")
    public ResponseEntity<Map<String, Object>> getRuleSet(
            @Parameter(description = "RuleSet ID", example = "api-limits")
            @PathVariable("ruleSetId") String ruleSetId) {

        Optional<RuleSetData> dataOpt = ruleSetStore.findById(ruleSetId);

        if (dataOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RuleSetData data = dataOpt.get();
        return ResponseEntity.ok(Map.of(
                "ruleSetId", data.getRuleSetId(),
                "capacity", data.getCapacity(),
                "windowSeconds", data.getWindowSeconds(),
                "keyStrategyId", data.getKeyStrategyId(),
                "createdAt", data.getCreatedAt()
        ));
    }

    @Operation(
            summary = "Create or update RuleSet",
            description = "Creates a new RuleSet or updates an existing one in Redis"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "RuleSet created"),
            @ApiResponse(responseCode = "200", description = "RuleSet updated")
    })
    @PostMapping
    public ResponseEntity<Map<String, Object>> createOrUpdateRuleSet(
            @Parameter(description = "RuleSet ID", example = "my-api-limits")
            @RequestParam("ruleSetId") String ruleSetId,
            @Parameter(description = "Max requests allowed", example = "100")
            @RequestParam(name = "capacity", defaultValue = "10") long capacity,
            @Parameter(description = "Time window in seconds", example = "60")
            @RequestParam(name = "windowSeconds", defaultValue = "60") long windowSeconds) {

        boolean existed = ruleSetStore.exists(ruleSetId);

        RuleSetData data = new RuleSetData(ruleSetId, capacity, windowSeconds, "clientIp");
        ruleSetStore.save(data);

        String action = existed ? "updated" : "created";
        log.info("RuleSet {} {}: {} requests per {} seconds", ruleSetId, action, capacity, windowSeconds);

        HttpStatus status = existed ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(Map.of(
                "message", "RuleSet " + action + " successfully",
                "ruleSetId", ruleSetId,
                "capacity", capacity,
                "windowSeconds", windowSeconds
        ));
    }

    @Operation(
            summary = "Delete RuleSet",
            description = "Deletes a RuleSet from Redis"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "RuleSet deleted"),
            @ApiResponse(responseCode = "404", description = "RuleSet not found")
    })
    @DeleteMapping("/{ruleSetId}")
    public ResponseEntity<Void> deleteRuleSet(
            @Parameter(description = "RuleSet ID", example = "api-limits")
            @PathVariable("ruleSetId") String ruleSetId) {

        boolean deleted = ruleSetStore.delete(ruleSetId);

        if (deleted) {
            log.info("Deleted RuleSet: {}", ruleSetId);
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(
            summary = "Clear all RuleSets",
            description = "Deletes all RuleSets from Redis and restores default"
    )
    @ApiResponse(responseCode = "200", description = "All RuleSets cleared")
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> clearAll() {
        ruleSetStore.clearAll();

        // Restore default
        RuleSetData defaultData = new RuleSetData("api-limits", 10, 60, "clientIp");
        ruleSetStore.save(defaultData);

        log.info("Cleared all RuleSets, restored default 'api-limits'");

        return ResponseEntity.ok(Map.of(
                "message", "All RuleSets cleared, default restored",
                "defaultRuleSet", "api-limits"
        ));
    }
}
*/
