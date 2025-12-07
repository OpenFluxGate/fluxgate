package org.fluxgate.sample.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

/** Admin endpoints that proxy to Control-plane (MongoDB) and Data-plane (Redis) services. */
@RestController
@RequestMapping("/admin")
@Tag(name = "Admin", description = "Rule management and sync between Control-plane and Data-plane")
public class AdminController {

  private static final Logger log = LoggerFactory.getLogger(AdminController.class);

  private final RestClient controlPlaneClient;
  private final RestClient dataPlaneClient;

  public AdminController(
      @Qualifier("controlPlaneClient") RestClient controlPlaneClient,
      @Qualifier("dataPlaneClient") RestClient dataPlaneClient) {
    this.controlPlaneClient = controlPlaneClient;
    this.dataPlaneClient = dataPlaneClient;
  }

  // ========== Control-plane (MongoDB) APIs ==========

  @Operation(
      summary = "Create sample rules in MongoDB",
      description = "Calls Control-plane to create sample rate limit rules in MongoDB")
  @ApiResponse(responseCode = "201", description = "Sample rules created successfully")
  @PostMapping("/rules/init")
  public ResponseEntity<Map<String, Object>> initRules() {
    log.info("Calling Control-plane to create sample rules...");

    Map<String, Object> response =
        controlPlaneClient
            .post()
            .uri("/admin/rules/sample")
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});

    log.info("Control-plane response: {}", response);
    return ResponseEntity.status(201).body(response);
  }

  @Operation(
      summary = "List rules from MongoDB",
      description = "Retrieves rate limit rules from Control-plane (MongoDB)")
  @ApiResponse(responseCode = "200", description = "Rules retrieved successfully")
  @GetMapping("/rules")
  public ResponseEntity<List<Map<String, Object>>> listRules(
      @Parameter(description = "Rule set identifier", example = "api-gateway-rules")
          @RequestParam(value = "ruleSetId", defaultValue = "api-gateway-rules")
          String ruleSetId) {

    log.info("Fetching rules from Control-plane for ruleSetId: {}", ruleSetId);

    List<Map<String, Object>> rules =
        controlPlaneClient
            .get()
            .uri("/admin/rules?ruleSetId={ruleSetId}", ruleSetId)
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});

    return ResponseEntity.ok(rules);
  }

  @Operation(
      summary = "Delete a rule from MongoDB",
      description = "Deletes a rate limit rule via Control-plane")
  @ApiResponse(responseCode = "204", description = "Rule deleted successfully")
  @DeleteMapping("/rules/{id}")
  public ResponseEntity<Void> deleteRule(
      @Parameter(description = "Rule ID", required = true) @PathVariable String id) {

    log.info("Deleting rule via Control-plane: {}", id);

    controlPlaneClient.delete().uri("/admin/rules/{id}", id).retrieve().toBodilessEntity();

    return ResponseEntity.noContent().build();
  }

  // ========== Sync: MongoDB -> Redis ==========

  @Operation(
      summary = "Sync rules from MongoDB to Redis",
      description =
          "Fetches rules from MongoDB and registers them as a RuleSet in Redis Data-plane")
  @ApiResponse(responseCode = "200", description = "Rules synced successfully")
  @PostMapping("/sync")
  public ResponseEntity<Map<String, Object>> syncRulesToRedis(
      @Parameter(description = "Rule set ID to sync", example = "api-gateway-rules")
          @RequestParam(value = "ruleSetId", defaultValue = "api-gateway-rules")
          String ruleSetId) {

    log.info("Syncing rules from MongoDB to Redis for ruleSetId: {}", ruleSetId);

    // 1. Fetch rules from MongoDB (Control-plane)
    List<Map<String, Object>> rules =
        controlPlaneClient
            .get()
            .uri("/admin/rules?ruleSetId={ruleSetId}", ruleSetId)
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});

    if (rules == null || rules.isEmpty()) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "No rules found in MongoDB", "ruleSetId", ruleSetId));
    }

    log.info("Fetched {} rules from MongoDB", rules.size());

    // 2. Extract capacity and window from the first rule's band
    // (simplified: use first rule's first band)
    Map<String, Object> firstRule = rules.get(0);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> bands = (List<Map<String, Object>>) firstRule.get("bands");

    long capacity = 10; // default
    long windowSeconds = 60; // default

    if (bands != null && !bands.isEmpty()) {
      Map<String, Object> firstBand = bands.get(0);
      capacity = ((Number) firstBand.getOrDefault("capacity", 10)).longValue();
      windowSeconds = ((Number) firstBand.getOrDefault("windowSeconds", 60)).longValue();
    }

    // 3. Register RuleSet in Redis (Data-plane)
    Map<String, Object> ruleSetRequest =
        Map.of(
            "ruleSetId", ruleSetId,
            "capacity", capacity,
            "windowSeconds", windowSeconds);

    Map<String, Object> redisResponse =
        dataPlaneClient
            .post()
            .uri("/admin/rules")
            .contentType(MediaType.APPLICATION_JSON)
            .body(ruleSetRequest)
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});

    log.info("Registered RuleSet in Redis: {}", redisResponse);

    return ResponseEntity.ok(
        Map.of(
            "message", "Rules synced successfully",
            "ruleSetId", ruleSetId,
            "rulesFromMongo", rules.size(),
            "capacity", capacity,
            "windowSeconds", windowSeconds,
            "redisResponse", redisResponse));
  }

  // ========== Data-plane (Redis) APIs ==========

  @Operation(
      summary = "List RuleSets in Redis",
      description = "Retrieves all registered RuleSets from Data-plane (Redis)")
  @ApiResponse(responseCode = "200", description = "RuleSets retrieved successfully")
  @GetMapping("/redis/rules")
  public ResponseEntity<Map<String, Object>> listRedisRuleSets() {
    log.info("Fetching RuleSets from Data-plane (Redis)");

    Map<String, Object> response =
        dataPlaneClient
            .get()
            .uri("/admin/rules")
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});

    return ResponseEntity.ok(response);
  }
}
