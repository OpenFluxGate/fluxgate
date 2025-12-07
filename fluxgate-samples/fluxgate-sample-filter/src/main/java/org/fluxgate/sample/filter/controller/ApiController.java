package org.fluxgate.sample.filter.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sample API controller demonstrating automatic rate limiting.
 *
 * <p>Notice: There is NO rate limiting code in this controller! All rate limiting is handled
 * automatically by {@code FluxgateRateLimitFilter}.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "API", description = "Rate-limited API endpoints (automatic via filter)")
public class ApiController {

  private final AtomicLong requestCounter = new AtomicLong(0);

  @Operation(
      summary = "Hello endpoint",
      description = "Simple hello endpoint. Rate limited automatically by the filter.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Request allowed"),
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
  })
  @GetMapping("/hello")
  public ResponseEntity<Map<String, Object>> hello() {
    long count = requestCounter.incrementAndGet();

    return ResponseEntity.ok(
        Map.of(
            "message",
            "Hello from FluxGate!",
            "requestNumber",
            count,
            "note",
            "This endpoint is rate-limited automatically by FluxgateRateLimitFilter",
            "timestamp",
            Instant.now().toString()));
  }

  @Operation(
      summary = "Get users",
      description = "Returns a list of sample users. Rate limited automatically.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Users returned"),
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
  })
  @GetMapping("/users")
  public ResponseEntity<Map<String, Object>> getUsers() {
    long count = requestCounter.incrementAndGet();

    List<Map<String, Object>> users =
        List.of(
            Map.of("id", 1, "name", "Alice", "email", "alice@example.com"),
            Map.of("id", 2, "name", "Bob", "email", "bob@example.com"),
            Map.of("id", 3, "name", "Charlie", "email", "charlie@example.com"));

    return ResponseEntity.ok(
        Map.of(
            "users",
            users,
            "count",
            users.size(),
            "requestNumber",
            count,
            "timestamp",
            Instant.now().toString()));
  }

  @Operation(
      summary = "Get user by ID",
      description = "Returns a specific user. Rate limited automatically.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "User returned"),
    @ApiResponse(responseCode = "404", description = "User not found"),
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
  })
  @GetMapping("/users/{id}")
  public ResponseEntity<Map<String, Object>> getUserById(@PathVariable int id) {
    long count = requestCounter.incrementAndGet();

    if (id < 1 || id > 3) {
      return ResponseEntity.notFound().build();
    }

    String[] names = {"Alice", "Bob", "Charlie"};
    String name = names[id - 1];

    return ResponseEntity.ok(
        Map.of(
            "id", id,
            "name", name,
            "email", name.toLowerCase() + "@example.com",
            "requestNumber", count,
            "timestamp", Instant.now().toString()));
  }

  @Operation(
      summary = "Get request statistics",
      description = "Returns total request count. Rate limited automatically.")
  @GetMapping("/stats")
  public ResponseEntity<Map<String, Object>> getStats() {
    return ResponseEntity.ok(
        Map.of(
            "totalRequests", requestCounter.get(),
            "note", "All /api/* endpoints are rate-limited by the filter",
            "timestamp", Instant.now().toString()));
  }
}
