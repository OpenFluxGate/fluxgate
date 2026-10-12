package org.fluxgate.spring.handler;

/**
 * Read-only view of the Redis rate limiter connection state.
 *
 * <p>Exposed as a bean by {@code FluxgateRedisAutoConfiguration} so the actuator health indicator
 * can report the rate limiter as degraded while the connection is still being established, instead
 * of the application failing to boot when Redis is momentarily unavailable.
 */
public interface RedisConnectionState {

  /**
   * Whether the Redis connection and the Lua scripts are ready to serve traffic.
   *
   * @return true once the limiter has been initialized successfully
   */
  boolean isReady();

  /**
   * Returns the message of the last connection failure.
   *
   * @return the failure message, or null if there has been none
   */
  String getLastErrorMessage();

  /**
   * Returns how many connection attempts have failed since the last success.
   *
   * @return the consecutive failure count, {@code 0} when connected
   */
  int getFailedAttempts();
}
