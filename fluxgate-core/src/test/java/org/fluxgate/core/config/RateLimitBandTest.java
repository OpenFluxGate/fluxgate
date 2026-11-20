package org.fluxgate.core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.fluxgate.core.exception.InvalidRuleConfigException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RateLimitBand}.
 *
 * <p>RateLimitBand represents a single rate limit band, such as: - 1 minute, 100 requests - 10
 * minutes, 500 requests
 */
@DisplayName("RateLimitBand Tests")
class RateLimitBandTest {

  // ==================== Builder / Factory Creation Tests ====================

  @Nested
  @DisplayName("Builder Tests")
  class BuilderTests {

    @Test
    @DisplayName("should create band with required fields")
    void build_shouldCreateBandWithRequiredFields() {
      // given
      Duration window = Duration.ofMinutes(1);
      long capacity = 100;

      // when
      RateLimitBand band = RateLimitBand.builder(window, capacity).build();

      // then
      assertEquals(window, band.getWindow());
      assertEquals(capacity, band.getCapacity());
      assertNull(band.getLabel()); // label is optional
    }

    @Test
    @DisplayName("should create band with optional label")
    void build_shouldCreateBandWithOptionalLabel() {
      // given
      Duration window = Duration.ofSeconds(60);
      long capacity = 100;
      String label = "per-minute-limit";

      // when
      RateLimitBand band = RateLimitBand.builder(window, capacity).label(label).build();

      // then
      assertEquals(window, band.getWindow());
      assertEquals(capacity, band.getCapacity());
      assertEquals(label, band.getLabel());
    }

    @Test
    @DisplayName("should allow various window durations")
    void build_shouldAllowVariousWindowDurations() {
      // given / when / then - Seconds
      RateLimitBand secondBand = RateLimitBand.builder(Duration.ofSeconds(30), 50).build();
      assertEquals(Duration.ofSeconds(30), secondBand.getWindow());

      // given / when / then - Minutes
      RateLimitBand minuteBand = RateLimitBand.builder(Duration.ofMinutes(5), 500).build();
      assertEquals(Duration.ofMinutes(5), minuteBand.getWindow());

      // given / when / then - Hours
      RateLimitBand hourBand = RateLimitBand.builder(Duration.ofHours(1), 10000).build();
      assertEquals(Duration.ofHours(1), hourBand.getWindow());

      // given / when / then - Days
      RateLimitBand dayBand = RateLimitBand.builder(Duration.ofDays(1), 100000).build();
      assertEquals(Duration.ofDays(1), dayBand.getWindow());
    }
  }

  // ==================== Validation Tests ====================

  @Nested
  @DisplayName("Validation Tests")
  class ValidationTests {

    @Test
    @DisplayName("should throw NullPointerException when window is null")
    void build_shouldThrowWhenWindowIsNull() {
      // given / when / then
      NullPointerException exception =
          assertThrows(NullPointerException.class, () -> RateLimitBand.builder(null, 100).build());
      assertTrue(exception.getMessage().contains("window must not be null"));
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when capacity is zero")
    void build_shouldThrowWhenCapacityIsZero() {
      // given / when / then
      IllegalArgumentException exception =
          assertThrows(
              IllegalArgumentException.class,
              () -> RateLimitBand.builder(Duration.ofMinutes(1), 0).build());
      assertTrue(exception.getMessage().contains("capacity must be > 0"));
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when capacity is negative")
    void build_shouldThrowWhenCapacityIsNegative() {
      // given / when / then
      IllegalArgumentException exception =
          assertThrows(
              IllegalArgumentException.class,
              () -> RateLimitBand.builder(Duration.ofMinutes(1), -10).build());
      assertTrue(exception.getMessage().contains("capacity must be > 0"));
    }

    @Test
    @DisplayName("should allow capacity of 1 (minimum valid)")
    void build_shouldAllowMinimumCapacity() {
      // given / when
      RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(1), 1).build();

      // then
      assertEquals(1, band.getCapacity());
    }

    @Test
    @DisplayName("should allow very large capacity")
    void build_shouldAllowLargeCapacity() {
      // given / when
      RateLimitBand band = RateLimitBand.builder(Duration.ofDays(1), Long.MAX_VALUE).build();

      // then
      assertEquals(Long.MAX_VALUE, band.getCapacity());
    }
  }

  // ==================== Getter Tests ====================

  @Nested
  @DisplayName("Getter Tests")
  class GetterTests {

    @Test
    @DisplayName("getWindow should return correct window duration")
    void getWindow_shouldReturnCorrectDuration() {
      // given
      Duration expectedWindow = Duration.ofMinutes(10);
      RateLimitBand band = RateLimitBand.builder(expectedWindow, 500).build();

      // when
      Duration actualWindow = band.getWindow();

      // then
      assertEquals(expectedWindow, actualWindow);
    }

    @Test
    @DisplayName("getCapacity should return correct capacity")
    void getCapacity_shouldReturnCorrectCapacity() {
      // given
      long expectedCapacity = 1000;
      RateLimitBand band = RateLimitBand.builder(Duration.ofHours(1), expectedCapacity).build();

      // when
      long actualCapacity = band.getCapacity();

      // then
      assertEquals(expectedCapacity, actualCapacity);
    }

    @Test
    @DisplayName("getLabel should return null when not set")
    void getLabel_shouldReturnNullWhenNotSet() {
      // given
      RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100).build();

      // when / then
      assertNull(band.getLabel());
    }

    @Test
    @DisplayName("getLabel should return correct label when set")
    void getLabel_shouldReturnCorrectLabelWhenSet() {
      // given
      String expectedLabel = "api-rate-limit";
      RateLimitBand band =
          RateLimitBand.builder(Duration.ofMinutes(1), 100).label(expectedLabel).build();

      // when
      String actualLabel = band.getLabel();

      // then
      assertEquals(expectedLabel, actualLabel);
    }
  }

  // ==================== toString Tests ====================

  @Nested
  @DisplayName("toString Tests")
  class ToStringTests {

    @Test
    @DisplayName("toString should contain all fields")
    void toString_shouldContainAllFields() {
      // given
      Duration window = Duration.ofMinutes(5);
      long capacity = 500;
      String label = "test-label";
      RateLimitBand band = RateLimitBand.builder(window, capacity).label(label).build();

      // when
      String result = band.toString();

      // then
      assertTrue(result.contains("RateLimitBand"));
      assertTrue(result.contains("window=PT5M"));
      assertTrue(result.contains("capacity=500"));
      assertTrue(result.contains("label='test-label'"));
    }

    @Test
    @DisplayName("toString should handle null label")
    void toString_shouldHandleNullLabel() {
      // given
      RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(30), 100).build();

      // when
      String result = band.toString();

      // then
      assertTrue(result.contains("label='null'"));
    }
  }

  // ==================== Window Validation Tests ====================

  @Nested
  @DisplayName("Window Validation Tests")
  class WindowValidationTests {

    @Test
    @DisplayName("should throw InvalidRuleConfigException when window is zero")
    void build_shouldThrowWhenWindowIsZero() {
      // given / when / then
      InvalidRuleConfigException exception =
          assertThrows(
              InvalidRuleConfigException.class,
              () -> RateLimitBand.builder(Duration.ZERO, 100).build());
      assertTrue(exception.getMessage().contains("window must be positive"));
    }

    @Test
    @DisplayName("should throw InvalidRuleConfigException when window is negative")
    void build_shouldThrowWhenWindowIsNegative() {
      // given / when / then
      assertThrows(
          InvalidRuleConfigException.class,
          () -> RateLimitBand.builder(Duration.ofMinutes(1).negated(), 100).build());
    }
  }

  // ==================== getKeyLabel Tests ====================

  @Nested
  @DisplayName("getKeyLabel Tests")
  class GetKeyLabelTests {

    @Test
    @DisplayName("getKeyLabel should return the label when it is set")
    void getKeyLabel_shouldReturnLabel() {
      // given
      RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100).label("burst").build();

      // when / then
      assertEquals("burst", band.getKeyLabel());
    }

    @Test
    @DisplayName("getKeyLabel should derive a label from capacity and window when unlabelled")
    void getKeyLabel_shouldDeriveFromConfiguration() {
      // given
      RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100).build();

      // when / then
      assertEquals("100-per-60s", band.getKeyLabel());
    }

    @Test
    @DisplayName("getKeyLabel should derive a label when the label is blank")
    void getKeyLabel_shouldDeriveFromConfigurationWhenLabelIsBlank() {
      // given
      RateLimitBand band = RateLimitBand.builder(Duration.ofHours(1), 1000).label("  ").build();

      // when / then
      assertEquals("1000-per-3600s", band.getKeyLabel());
    }

    @Test
    @DisplayName("getKeyLabel should differ for two unlabelled bands of different configuration")
    void getKeyLabel_shouldDifferForDifferentConfiguration() {
      // given
      RateLimitBand shortBand = RateLimitBand.builder(Duration.ofSeconds(1), 10).build();
      RateLimitBand longBand = RateLimitBand.builder(Duration.ofMinutes(1), 100).build();

      // when / then
      assertNotEquals(shortBand.getKeyLabel(), longBand.getKeyLabel());
    }

    @Test
    @DisplayName("getKeyLabel should render a sub-second window in milliseconds")
    void getKeyLabel_shouldRenderSubSecondWindowInMillis() {
      // given - truncating to whole seconds rendered both of these as "10-per-0s"
      RateLimitBand half = RateLimitBand.builder(Duration.ofMillis(500), 10).build();
      RateLimitBand fifth = RateLimitBand.builder(Duration.ofMillis(200), 10).build();

      // when / then
      assertEquals("10-per-500ms", half.getKeyLabel());
      assertEquals("10-per-200ms", fifth.getKeyLabel());
      assertNotEquals(half.getKeyLabel(), fifth.getKeyLabel());
    }

    @Test
    @DisplayName("getKeyLabel should keep a fractional-second window apart from a whole one")
    void getKeyLabel_shouldRenderFractionalSecondWindowInMillis() {
      // given - both truncated to "10-per-1s" before
      RateLimitBand oneSecond = RateLimitBand.builder(Duration.ofSeconds(1), 10).build();
      RateLimitBand oneAndAHalf = RateLimitBand.builder(Duration.ofMillis(1500), 10).build();

      // when / then
      assertEquals("10-per-1s", oneSecond.getKeyLabel());
      assertEquals("10-per-1500ms", oneAndAHalf.getKeyLabel());
      assertNotEquals(oneSecond.getKeyLabel(), oneAndAHalf.getKeyLabel());
    }

    @Test
    @DisplayName("getKeyLabel should fall back to nanoseconds below millisecond precision")
    void getKeyLabel_shouldRenderSubMillisWindowInNanos() {
      // given - 1ms and 1.5ms would both render as "10-per-1ms"
      RateLimitBand oneMilli = RateLimitBand.builder(Duration.ofMillis(1), 10).build();
      RateLimitBand oneAndAHalfMilli =
          RateLimitBand.builder(Duration.ofNanos(1_500_000), 10).build();

      // when / then
      assertEquals("10-per-1ms", oneMilli.getKeyLabel());
      assertEquals("10-per-1500000ns", oneAndAHalfMilli.getKeyLabel());
      assertNotEquals(oneMilli.getKeyLabel(), oneAndAHalfMilli.getKeyLabel());
    }

    @Test
    @DisplayName("getKeyLabel should keep the second form for every whole-second window")
    void getKeyLabel_shouldKeepTheSecondsFormForWholeSeconds() {
      // given - the storage key format must not change for the windows that already worked
      assertEquals(
          "3-per-1s", RateLimitBand.builder(Duration.ofSeconds(1), 3).build().getKeyLabel());
      assertEquals(
          "100-per-60s", RateLimitBand.builder(Duration.ofSeconds(60), 100).build().getKeyLabel());
      assertEquals(
          "1000-per-86400s", RateLimitBand.builder(Duration.ofDays(1), 1000).build().getKeyLabel());
    }
  }

  // ==================== Equality Tests ====================

  @Nested
  @DisplayName("Equality Tests")
  class EqualityTests {

    @Test
    @DisplayName("independently built identical bands should be equal")
    void equals_shouldBeValueBased() {
      // given
      RateLimitBand first =
          RateLimitBand.builder(Duration.ofMinutes(1), 100).label("per-minute").build();
      RateLimitBand second =
          RateLimitBand.builder(Duration.ofMinutes(1), 100).label("per-minute").build();

      // when / then
      assertEquals(first, second);
      assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    @DisplayName("bands differing in window, capacity or label should not be equal")
    void equals_shouldDistinguishDifferentFields() {
      // given
      RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100).label("a").build();

      // when / then
      assertNotEquals(band, RateLimitBand.builder(Duration.ofMinutes(2), 100).label("a").build());
      assertNotEquals(band, RateLimitBand.builder(Duration.ofMinutes(1), 200).label("a").build());
      assertNotEquals(band, RateLimitBand.builder(Duration.ofMinutes(1), 100).label("b").build());
      assertNotEquals(band, RateLimitBand.builder(Duration.ofMinutes(1), 100).build());
    }

    @Test
    @DisplayName("equals should return false for null and other types")
    void equals_shouldReturnFalseForNullAndOtherTypes() {
      // given
      RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100).build();

      // when / then
      assertNotEquals(null, band);
      assertNotEquals(band, "not-a-band");
    }
  }
}
