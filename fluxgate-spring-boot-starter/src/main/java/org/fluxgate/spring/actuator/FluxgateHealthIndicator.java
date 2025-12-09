package org.fluxgate.spring.actuator;

import java.util.Objects;
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
 *   <li>MongoDB connection (if enabled)
 *   <li>Redis connection (if enabled)
 * </ul>
 *
 * <p>Access via: {@code GET /actuator/health/fluxgate}
 */
public class FluxgateHealthIndicator implements HealthIndicator {

  private static final Logger log = LoggerFactory.getLogger(FluxgateHealthIndicator.class);

  private final FluxgateProperties properties;
  private final MongoHealthChecker mongoHealthChecker;
  private final RedisHealthChecker redisHealthChecker;

  public FluxgateHealthIndicator(
      FluxgateProperties properties,
      MongoHealthChecker mongoHealthChecker,
      RedisHealthChecker redisHealthChecker) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.mongoHealthChecker = mongoHealthChecker; // nullable - optional checker
    this.redisHealthChecker = redisHealthChecker; // nullable - optional checker
  }

  @Override
  public Health health() {
    Health.Builder builder = Health.up();

    boolean hasIssues = false;

    // Check rate limiting status
    builder.withDetail("rateLimitingEnabled", properties.getRatelimit().isEnabled());
    builder.withDetail("filterEnabled", properties.getRatelimit().isFilterEnabled());

    // Check MongoDB status
    if (properties.getMongo().isEnabled()) {
      try {
        HealthStatus mongoStatus =
            mongoHealthChecker != null
                ? mongoHealthChecker.check()
                : HealthStatus.unknown("No checker configured");

        builder.withDetail("mongo.status", mongoStatus.status());
        builder.withDetail("mongo.message", mongoStatus.message());

        if (!mongoStatus.isHealthy()) {
          hasIssues = true;
        }
      } catch (Exception e) {
        log.warn("MongoDB health check failed: {}", e.getMessage());
        builder.withDetail("mongo.status", "ERROR");
        builder.withDetail("mongo.message", e.getMessage());
        hasIssues = true;
      }
    } else {
      builder.withDetail("mongo.status", "DISABLED");
    }

    // Check Redis status
    if (properties.getRedis().isEnabled()) {
      try {
        HealthStatus redisStatus =
            redisHealthChecker != null
                ? redisHealthChecker.check()
                : HealthStatus.unknown("No checker configured");

        builder.withDetail("redis.status", redisStatus.status());
        builder.withDetail("redis.message", redisStatus.message());

        if (!redisStatus.isHealthy()) {
          hasIssues = true;
        }
      } catch (Exception e) {
        log.warn("Redis health check failed: {}", e.getMessage());
        builder.withDetail("redis.status", "ERROR");
        builder.withDetail("redis.message", e.getMessage());
        hasIssues = true;
      }
    } else {
      builder.withDetail("redis.status", "DISABLED");
    }

    // Set overall status
    if (hasIssues) {
      return builder.status("DEGRADED").build();
    }

    return builder.build();
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

  /** Health status result. */
  public record HealthStatus(String status, String message, boolean isHealthy) {

    public static HealthStatus up(String message) {
      return new HealthStatus("UP", message, true);
    }

    public static HealthStatus down(String message) {
      return new HealthStatus("DOWN", message, false);
    }

    public static HealthStatus unknown(String message) {
      return new HealthStatus("UNKNOWN", message, false);
    }
  }
}
