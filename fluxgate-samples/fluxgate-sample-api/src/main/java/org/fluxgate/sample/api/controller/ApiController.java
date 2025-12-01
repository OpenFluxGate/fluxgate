package org.fluxgate.sample.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * API endpoints that proxy to Data-plane (Redis) service.
 * Requests are rate-limited by the Data-plane service.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "API", description = "API endpoints proxied to Data-plane (rate-limited)")
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final RestClient dataPlaneClient;

    public ApiController(@Qualifier("dataPlaneClient") RestClient dataPlaneClient) {
        this.dataPlaneClient = dataPlaneClient;
    }

    @Operation(
            summary = "Test rate limiting",
            description = "Proxies to Data-plane /api/test with specified ruleSetId. Rate limited by rules synced from MongoDB."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request allowed"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> test(
            @Parameter(description = "RuleSet ID (must be synced from MongoDB first)", example = "api-gateway-rules")
            @RequestParam(value = "ruleSetId", defaultValue = "api-gateway-rules") String ruleSetId) {

        log.info("Proxying request to Data-plane /api/test with ruleSetId: {}", ruleSetId);

        try {
            Map<String, Object> response = dataPlaneClient.get()
                    .uri("/api/test?ruleSetId={ruleSetId}", ruleSetId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            return ResponseEntity.ok(response);
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("Rate limit exceeded from Data-plane for ruleSetId: {}", ruleSetId);
            return ResponseEntity.status(429).body(Map.of(
                    "status", "REJECTED",
                    "error", "Rate limit exceeded",
                    "ruleSetId", ruleSetId,
                    "message", "Too many requests. Please try again later."
            ));
        } catch (HttpClientErrorException.BadRequest e) {
            log.warn("Bad request from Data-plane: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "RuleSet not found in Redis",
                    "ruleSetId", ruleSetId,
                    "hint", "Sync rules first: POST /admin/sync?ruleSetId=" + ruleSetId
            ));
        }
    }

    @Operation(
            summary = "Hello endpoint (default rate limit)",
            description = "Simple hello endpoint using default 'api-limits' RuleSet"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request allowed"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    @GetMapping("/hello")
    public ResponseEntity<Map<String, Object>> hello() {
        return test("api-limits");
    }

    @Operation(
            summary = "Status endpoint",
            description = "Proxies to Data-plane /api/status (no rate limiting)"
    )
    @ApiResponse(responseCode = "200", description = "Status returned successfully")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        log.info("Proxying request to Data-plane /api/status");

        Map<String, Object> response = dataPlaneClient.get()
                .uri("/api/status")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        return ResponseEntity.ok(response);
    }
}
