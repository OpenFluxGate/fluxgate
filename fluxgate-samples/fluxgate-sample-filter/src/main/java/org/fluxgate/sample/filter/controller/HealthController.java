package org.fluxgate.sample.filter.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Health check controller - NOT rate limited.
 * <p>
 * This controller is excluded from rate limiting because:
 * <ul>
 *   <li>The filter only applies to /api/* patterns</li>
 *   <li>/health is outside that pattern</li>
 * </ul>
 */
@RestController
@Tag(name = "Health", description = "Health check endpoints (NOT rate limited)")
public class HealthController {

    @Operation(
            summary = "Health check",
            description = "Returns service health status. This endpoint is NOT rate limited."
    )
    @ApiResponse(responseCode = "200", description = "Service is healthy")
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "note", "This endpoint is NOT rate limited (outside /api/* pattern)",
                "timestamp", Instant.now().toString()
        ));
    }

    @Operation(
            summary = "Ready check",
            description = "Returns service readiness. This endpoint is NOT rate limited."
    )
    @ApiResponse(responseCode = "200", description = "Service is ready")
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        return ResponseEntity.ok(Map.of(
                "status", "READY",
                "note", "This endpoint is NOT rate limited",
                "timestamp", Instant.now().toString()
        ));
    }
}
