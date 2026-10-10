package org.fluxgate.spring.actuator;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.fluxgate.spring.handler.RedisConnectionState;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Spring Boot Actuator Health Indicator for FluxGate.
 *
 * <p>Provides health status for the FluxGate rate limiting system. Reports on the status of:
 *
 * <ul>
 *   <li>Rate limiting enabled/disabled state
 *   <li>Whether a rate limit filter and aspect bean actually exist
 *   <li>Whether the Redis-backed rate limiter finished connecting
 *   <li>MongoDB connection (if enabled)
 *   <li>Redis connection (if enabled)
 * </ul>
 *
 * <p>Access via: {@code GET /actuator/health/fluxgate}
 *
 * <p>An unhealthy dependency reports the custom status {@code DEGRADED}. {@link
 * FluxgateHealthStatusEnvironmentPostProcessor} adds it to the default health status order, so it
 * reaches {@code /actuator/health} and the readiness group, and maps it to HTTP 503 ({@code
 * fluxgate.actuator.health.degraded-http-status}), so external monitoring actually sees the
 * degradation.
 *
 * <p>N-6b: the payload names the status and the exception type, never the failure message or the
 * dependency's {@code host:port}. Those are reconnaissance for whoever can read the endpoint, and
 * they are logged at WARN instead. Set {@code
 * fluxgate.actuator.health.include-endpoint-details=true} to put them back, and keep the endpoint
 * behind {@code management.endpoint.health.show-details=when_authorized} when you do.
 */
public class FluxgateHealthIndicator implements HealthIndicator {

  private static final Logger log = LoggerFactory.getLogger(FluxgateHealthIndicator.class);

  private final FluxgateProperties properties;
  private final MongoHealthChecker mongoHealthChecker;
  private final RedisHealthChecker redisHealthChecker;
  private final BooleanSupplier filterPresent;
  private final BooleanSupplier aspectPresent;
  private final RedisConnectionState redisConnectionState;

  /**
   * Creates an indicator that reports neither a filter nor an aspect.
   *
   * @param properties the FluxGate properties
   * @param mongoHealthChecker optional MongoDB checker (nullable)
   * @param redisHealthChecker optional Redis checker (nullable)
   */
  public FluxgateHealthIndicator(
      FluxgateProperties properties,
      MongoHealthChecker mongoHealthChecker,
      RedisHealthChecker redisHealthChecker) {
    this(properties, mongoHealthChecker, redisHealthChecker, () -> false, () -> false);
  }

  /**
   * Creates an indicator.
   *
   * <p>H15: the filter and aspect state is probed instead of echoing {@code filter-enabled}. An
   * operator who believes rate limiting is off because a property says so, while the filter bean is
   * registered and still enforcing, is worse served by the health endpoint than by no endpoint.
   *
   * @param properties the FluxGate properties
   * @param mongoHealthChecker optional MongoDB checker (nullable)
   * @param redisHealthChecker optional Redis checker (nullable)
   * @param filterPresent tells whether a rate limit filter bean exists
   * @param aspectPresent tells whether a rate limit aspect bean exists
   */
  public FluxgateHealthIndicator(
      FluxgateProperties properties,
      MongoHealthChecker mongoHealthChecker,
      RedisHealthChecker redisHealthChecker,
      BooleanSupplier filterPresent,
      BooleanSupplier aspectPresent) {
    this(properties, mongoHealthChecker, redisHealthChecker, filterPresent, aspectPresent, null);
  }

  /**
   * Creates an indicator that also reports the Redis rate limiter's connection state.
   *
   * <p>M25: Redis being unavailable at startup no longer fails the boot, so the limiter can be
   * alive but not yet serving. Without this detail the endpoint would report UP while every request
   * is falling back, which is the failure mode an operator most needs to see.
   *
   * @param properties the FluxGate properties
   * @param mongoHealthChecker optional MongoDB checker (nullable)
   * @param redisHealthChecker optional Redis checker (nullable)
   * @param filterPresent tells whether a rate limit filter bean exists
   * @param aspectPresent tells whether a rate limit aspect bean exists
   * @param redisConnectionState the Redis limiter's connection state, or null when not Redis backed
   */
  public FluxgateHealthIndicator(
      FluxgateProperties properties,
      MongoHealthChecker mongoHealthChecker,
      RedisHealthChecker redisHealthChecker,
      BooleanSupplier filterPresent,
      BooleanSupplier aspectPresent,
      RedisConnectionState redisConnectionState) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.mongoHealthChecker = mongoHealthChecker; // nullable - optional checker
    this.redisHealthChecker = redisHealthChecker; // nullable - optional checker
    this.filterPresent = filterPresent != null ? filterPresent : () -> false;
    this.aspectPresent = aspectPresent != null ? aspectPresent : () -> false;
    this.redisConnectionState = redisConnectionState; // nullable - only for the Redis limiter
  }

  @Override
  public Health health() {
    Health.Builder builder = Health.up();

    boolean hasIssues = false;
    boolean includeEndpointDetails =
        properties.getActuator().getHealth().isIncludeEndpointDetails();

    // Check rate limiting status
    builder.withDetail("rateLimitingEnabled", properties.getRatelimit().isEnabled());
    builder.withDetail("filterEnabled", safeProbe(filterPresent));
    builder.withDetail("aspectEnabled", safeProbe(aspectPresent));
    builder.withDetail("failureBehavior", properties.getRatelimit().getFailureBehavior().name());
    builder.withDetail(
        "missingRuleBehavior", properties.getRatelimit().getMissingRuleBehavior().name());
    builder.withDetail("trustClientIpHeader", properties.getRatelimit().isTrustClientIpHeader());
    builder.withDetail(
        "defaultRuleSetIdConfigured",
        properties.getRatelimit().getDefaultRuleSetId() != null
            && !properties.getRatelimit().getDefaultRuleSetId().isBlank());

    // Check MongoDB status
    if (properties.getMongo().isEnabled()) {
      try {
        HealthStatus mongoStatus =
            mongoHealthChecker != null
                ? mongoHealthChecker.check()
                : HealthStatus.unknown("No checker configured");

        builder.withDetail("mongo.status", mongoStatus.status());
        if (includeEndpointDetails) {
          builder.withDetail("mongo.message", mongoStatus.message());
          mongoStatus.details().forEach((key, value) -> builder.withDetail("mongo." + key, value));
        }

        if (!mongoStatus.isHealthy()) {
          log.warn(
              "MongoDB health check reported {}: {}", mongoStatus.status(), mongoStatus.message());
          hasIssues = true;
        }
      } catch (Exception e) {
        log.warn("MongoDB health check failed", e);
        builder.withDetail("mongo.status", "ERROR");
        builder.withDetail("mongo.error", e.getClass().getSimpleName());
        hasIssues = true;
      }
    } else {
      builder.withDetail("mongo.status", "DISABLED");
    }

    // Check the rate limiter's own readiness, which is not the same as Redis answering PING: the
    // limiter also has to have loaded its Lua scripts before it can serve traffic.
    if (redisConnectionState != null) {
      try {
        boolean ready = redisConnectionState.isReady();
        builder.withDetail("rateLimiter.ready", ready);
        if (!ready) {
          builder.withDetail(
              "rateLimiter.failedAttempts", redisConnectionState.getFailedAttempts());
          String lastError = redisConnectionState.getLastErrorMessage();
          if (lastError != null) {
            // The message carries the Redis host:port, so it goes to the log, not the payload.
            log.warn("Redis rate limiter is not ready yet: {}", lastError);
            if (includeEndpointDetails) {
              builder.withDetail("rateLimiter.lastError", lastError);
            }
          }
          hasIssues = true;
        }
      } catch (RuntimeException e) {
        log.warn("Rate limiter state check failed", e);
        builder.withDetail("rateLimiter.ready", false);
        builder.withDetail("rateLimiter.error", e.getClass().getSimpleName());
        hasIssues = true;
      }
    }

    // Check Redis status
    if (properties.getRedis().isEnabled()) {
      try {
        HealthStatus redisStatus =
            redisHealthChecker != null
                ? redisHealthChecker.check()
                : HealthStatus.unknown("No checker configured");

        builder.withDetail("redis.status", redisStatus.status());
        if (includeEndpointDetails) {
          builder.withDetail("redis.message", redisStatus.message());
          redisStatus.details().forEach((key, value) -> builder.withDetail("redis." + key, value));
        }

        if (!redisStatus.isHealthy()) {
          log.warn(
              "Redis health check reported {}: {}", redisStatus.status(), redisStatus.message());
          hasIssues = true;
        }
      } catch (Exception e) {
        log.warn("Redis health check failed", e);
        builder.withDetail("redis.status", "ERROR");
        builder.withDetail("redis.error", e.getClass().getSimpleName());
        hasIssues = true;
      }
    } else {
      builder.withDetail("redis.status", "DISABLED");
    }

    // Set overall status
    if (hasIssues) {
      builder.withDetail("dependencyIssues", true);
      builder.withDetail("statusReason", "One or more enabled FluxGate dependencies are unhealthy");
      return builder.status("DEGRADED").build();
    }

    builder.withDetail("dependencyIssues", false);
    return builder.build();
  }

  /** Probes a collaborator without letting a lookup failure fail the health endpoint. */
  private static boolean safeProbe(BooleanSupplier supplier) {
    try {
      return supplier.getAsBoolean();
    } catch (RuntimeException | LinkageError e) {
      return false;
    }
  }

  /** Functional interface for MongoDB health checking. */
  @FunctionalInterface
  public interface MongoHealthChecker {
    HealthStatus check();
  }

  /** Functional interface for Redis health checking. */
  @FunctionalInterface
  public interface RedisHealthChecker {
    HealthStatus check();
  }

  /** Health status result with optional details. */
  public static final class HealthStatus {

    private final String status;
    private final String message;
    private final boolean isHealthy;
    private final java.util.Map<String, Object> details;

    public HealthStatus(
        String status, String message, boolean isHealthy, java.util.Map<String, Object> details) {
      this.status = status;
      this.message = message;
      this.isHealthy = isHealthy;
      this.details = details;
    }

    public HealthStatus(String status, String message, boolean isHealthy) {
      this(status, message, isHealthy, java.util.Collections.emptyMap());
    }

    public static HealthStatus up(String message) {
      return new HealthStatus("UP", message, true, java.util.Collections.emptyMap());
    }

    public static HealthStatus up(String message, java.util.Map<String, Object> details) {
      return new HealthStatus("UP", message, true, details);
    }

    public static HealthStatus down(String message) {
      return new HealthStatus("DOWN", message, false, java.util.Collections.emptyMap());
    }

    public static HealthStatus down(String message, java.util.Map<String, Object> details) {
      return new HealthStatus("DOWN", message, false, details);
    }

    public static HealthStatus unknown(String message) {
      return new HealthStatus("UNKNOWN", message, false, java.util.Collections.emptyMap());
    }

    public String status() {
      return status;
    }

    public String message() {
      return message;
    }

    public boolean isHealthy() {
      return isHealthy;
    }

    public java.util.Map<String, Object> details() {
      return details;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof HealthStatus)) return false;
      HealthStatus that = (HealthStatus) o;
      return isHealthy == that.isHealthy
          && Objects.equals(status, that.status)
          && Objects.equals(message, that.message)
          && Objects.equals(details, that.details);
    }

    @Override
    public int hashCode() {
      return Objects.hash(status, message, isHealthy, details);
    }

    @Override
    public String toString() {
      return "HealthStatus{"
          + "status='"
          + status
          + '\''
          + ", message='"
          + message
          + '\''
          + ", isHealthy="
          + isHealthy
          + ", details="
          + details
          + '}';
    }
  }
}
