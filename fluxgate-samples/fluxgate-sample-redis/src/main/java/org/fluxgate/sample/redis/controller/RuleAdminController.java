package org.fluxgate.sample.redis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.fluxgate.sample.redis.config.DynamicRuleSetProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Admin API for managing RuleSets in Data-plane. */
@RestController
@RequestMapping("/admin/rules")
@Tag(name = "Rule Admin", description = "Manage RuleSets for rate limiting")
public class RuleAdminController {

  private static final Logger log = LoggerFactory.getLogger(RuleAdminController.class);

  private final DynamicRuleSetProvider ruleSetProvider;

  public RuleAdminController(DynamicRuleSetProvider ruleSetProvider) {
    this.ruleSetProvider = ruleSetProvider;
  }

  @Operation(
      summary = "Register a RuleSet",
      description = "Creates a new rate limit RuleSet with specified capacity and window")
  @ApiResponse(responseCode = "201", description = "RuleSet created successfully")
  @PostMapping
  public ResponseEntity<Map<String, Object>> createRuleSet(
      @RequestBody CreateRuleSetRequest request) {

    log.info(
        "Creating RuleSet: {} with capacity={}, windowSeconds={}",
        request.ruleSetId(),
        request.capacity(),
        request.windowSeconds());

    ruleSetProvider.registerRuleSet(
        request.ruleSetId(), request.capacity(), request.windowSeconds());

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            Map.of(
                "message", "RuleSet created successfully",
                "ruleSetId", request.ruleSetId(),
                "capacity", request.capacity(),
                "windowSeconds", request.windowSeconds()));
  }

  @Operation(
      summary = "Create sample RuleSet",
      description = "Creates a sample RuleSet: 5 requests per 10 seconds (for easy testing)")
  @ApiResponse(responseCode = "201", description = "Sample RuleSet created")
  @PostMapping("/sample")
  public ResponseEntity<Map<String, Object>> createSampleRuleSet() {
    String ruleSetId = "test-limits";
    long capacity = 5;
    long windowSeconds = 10;

    ruleSetProvider.registerRuleSet(ruleSetId, capacity, windowSeconds);

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            Map.of(
                "message", "Sample RuleSet created",
                "ruleSetId", ruleSetId,
                "capacity", capacity,
                "windowSeconds", windowSeconds,
                "hint",
                    "Try: for i in {1..10}; do curl -s http://localhost:8082/api/test?ruleSetId=test-limits; echo; sleep 0.5; done"));
  }

  @Operation(summary = "List all RuleSets", description = "Returns all registered RuleSet IDs")
  @ApiResponse(responseCode = "200", description = "List of RuleSet IDs")
  @GetMapping
  public ResponseEntity<Map<String, Object>> listRuleSets() {
    List<String> ruleSetIds = ruleSetProvider.listRuleSetIds();
    return ResponseEntity.ok(Map.of("ruleSets", ruleSetIds, "count", ruleSetIds.size()));
  }

  @Operation(summary = "Delete a RuleSet", description = "Removes a RuleSet by ID")
  @ApiResponse(responseCode = "204", description = "RuleSet deleted")
  @ApiResponse(responseCode = "404", description = "RuleSet not found")
  @DeleteMapping("/{ruleSetId}")
  public ResponseEntity<Void> deleteRuleSet(
      @Parameter(description = "RuleSet ID", required = true) @PathVariable String ruleSetId) {

    boolean removed = ruleSetProvider.removeRuleSet(ruleSetId);
    if (removed) {
      return ResponseEntity.noContent().build();
    }
    return ResponseEntity.notFound().build();
  }

  @Operation(
      summary = "Clear all RuleSets",
      description = "Removes all RuleSets and restores the default")
  @ApiResponse(responseCode = "200", description = "All RuleSets cleared")
  @DeleteMapping
  public ResponseEntity<Map<String, Object>> clearAllRuleSets() {
    ruleSetProvider.clearAll();
    return ResponseEntity.ok(Map.of("message", "All RuleSets cleared, default restored"));
  }

  /** Request body for creating a RuleSet. */
  public record CreateRuleSetRequest(String ruleSetId, long capacity, long windowSeconds) {}
}
