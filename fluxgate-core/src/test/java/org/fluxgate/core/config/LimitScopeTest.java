package org.fluxgate.core.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LimitScope} enum.
 *
 * <p>LimitScope defines the logical scope of a rate limit: - GLOBAL: Single global bucket for all
 * traffic - PER_API_KEY: One bucket per API key - PER_USER: One bucket per user identifier -
 * PER_IP: One bucket per client IP address - CUSTOM: Custom scope resolved by a pluggable key
 * strategy
 */
@DisplayName("LimitScope Enum Tests")
class LimitScopeTest {

  // ==================== valueOf / values() Sanity Tests ====================

  @Test
  @DisplayName("valueOf should return correct enum constant for valid names")
  void valueOf_shouldReturnCorrectEnumConstant() {
    // given / when / then
    assertEquals(LimitScope.GLOBAL, LimitScope.valueOf("GLOBAL"));
    assertEquals(LimitScope.PER_API_KEY, LimitScope.valueOf("PER_API_KEY"));
    assertEquals(LimitScope.PER_USER, LimitScope.valueOf("PER_USER"));
    assertEquals(LimitScope.PER_IP, LimitScope.valueOf("PER_IP"));
    assertEquals(LimitScope.CUSTOM, LimitScope.valueOf("CUSTOM"));
  }

  @Test
  @DisplayName("valueOf should throw IllegalArgumentException for invalid name")
  void valueOf_shouldThrowForInvalidName() {
    // given / when / then
    assertThrows(IllegalArgumentException.class, () -> LimitScope.valueOf("INVALID"));
    assertThrows(
        IllegalArgumentException.class, () -> LimitScope.valueOf("global")); // case-sensitive
  }

  @Test
  @DisplayName("values should return all five enum constants")
  void values_shouldReturnAllConstants() {
    // given / when
    LimitScope[] values = LimitScope.values();

    // then
    assertEquals(5, values.length);
    assertArrayEquals(
        new LimitScope[] {
          LimitScope.GLOBAL,
          LimitScope.PER_API_KEY,
          LimitScope.PER_USER,
          LimitScope.PER_IP,
          LimitScope.CUSTOM
        },
        values);
  }

  // ==================== Semantic Tests ====================

  @Test
  @DisplayName("GLOBAL should represent single bucket for all traffic")
  void global_shouldRepresentSingleBucketForAllTraffic() {
    // GLOBAL means one shared bucket across all requests
    // This is useful for system-wide rate limits
    LimitScope scope = LimitScope.GLOBAL;
    assertEquals("GLOBAL", scope.name());
    assertEquals(0, scope.ordinal());
  }

  @Test
  @DisplayName("PER_API_KEY should represent one bucket per API key")
  void perApiKey_shouldRepresentOneBucketPerApiKey() {
    // PER_API_KEY means each API key has its own bucket
    // Useful for API consumers with different rate limits
    LimitScope scope = LimitScope.PER_API_KEY;
    assertEquals("PER_API_KEY", scope.name());
    assertEquals(1, scope.ordinal());
  }

  @Test
  @DisplayName("PER_USER should represent one bucket per user")
  void perUser_shouldRepresentOneBucketPerUser() {
    // PER_USER means each authenticated user has their own bucket
    // Useful for user-level rate limiting
    LimitScope scope = LimitScope.PER_USER;
    assertEquals("PER_USER", scope.name());
    assertEquals(2, scope.ordinal());
  }

  @Test
  @DisplayName("PER_IP should represent one bucket per client IP")
  void perIp_shouldRepresentOneBucketPerClientIp() {
    // PER_IP means each client IP address has its own bucket
    // Useful for protecting against DDoS or brute force attacks
    LimitScope scope = LimitScope.PER_IP;
    assertEquals("PER_IP", scope.name());
    assertEquals(3, scope.ordinal());
  }

  @Test
  @DisplayName("CUSTOM should represent pluggable key strategy")
  void custom_shouldRepresentPluggableKeyStrategy() {
    // CUSTOM allows implementing custom key resolution logic
    // Useful for complex scenarios like tenant-based rate limiting
    LimitScope scope = LimitScope.CUSTOM;
    assertEquals("CUSTOM", scope.name());
    assertEquals(4, scope.ordinal());
  }

  // ==================== Stability Tests ====================

  @Test
  @DisplayName("enum constants should be stable for serialization compatibility")
  void enumConstants_shouldBeStable() {
    // This test ensures ordinal values don't change unexpectedly
    // Important for serialization/deserialization compatibility
    assertEquals(0, LimitScope.GLOBAL.ordinal());
    assertEquals(1, LimitScope.PER_API_KEY.ordinal());
    assertEquals(2, LimitScope.PER_USER.ordinal());
    assertEquals(3, LimitScope.PER_IP.ordinal());
    assertEquals(4, LimitScope.CUSTOM.ordinal());
  }
}
