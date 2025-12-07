package org.fluxgate.adapter.mongo.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RateLimitBandDocument}.
 *
 * <p>RateLimitBandDocument represents a rate limit band document for MongoDB storage.
 */
@DisplayName("RateLimitBandDocument Tests")
class RateLimitBandDocumentTest {

  // ==================== Constructor Tests ====================

  @Nested
  @DisplayName("Constructor Tests")
  class ConstructorTests {

    @Test
    @DisplayName("should create document with valid values")
    void constructor_shouldCreateDocumentWithValidValues() {
      // given
      long windowSeconds = 60;
      long capacity = 100;
      String label = "per-minute";

      // when
      RateLimitBandDocument document = new RateLimitBandDocument(windowSeconds, capacity, label);

      // then
      assertEquals(windowSeconds, document.getWindowSeconds());
      assertEquals(capacity, document.getCapacity());
      assertEquals(label, document.getLabel());
    }

    @Test
    @DisplayName("should throw NullPointerException when label is null")
    void constructor_shouldThrowWhenLabelIsNull() {
      // given / when / then
      assertThrows(NullPointerException.class, () -> new RateLimitBandDocument(60, 100, null));
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when windowSeconds is zero")
    void constructor_shouldThrowWhenWindowSecondsIsZero() {
      // given / when / then
      IllegalArgumentException exception =
          assertThrows(
              IllegalArgumentException.class, () -> new RateLimitBandDocument(0, 100, "test"));
      assertTrue(exception.getMessage().contains("windowSeconds must be > 0"));
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when windowSeconds is negative")
    void constructor_shouldThrowWhenWindowSecondsIsNegative() {
      // given / when / then
      assertThrows(
          IllegalArgumentException.class, () -> new RateLimitBandDocument(-1, 100, "test"));
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when capacity is zero")
    void constructor_shouldThrowWhenCapacityIsZero() {
      // given / when / then
      IllegalArgumentException exception =
          assertThrows(
              IllegalArgumentException.class, () -> new RateLimitBandDocument(60, 0, "test"));
      assertTrue(exception.getMessage().contains("capacity must be > 0"));
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when capacity is negative")
    void constructor_shouldThrowWhenCapacityIsNegative() {
      // given / when / then
      assertThrows(
          IllegalArgumentException.class, () -> new RateLimitBandDocument(60, -10, "test"));
    }
  }

  // ==================== Getter Tests ====================

  @Nested
  @DisplayName("Getter Tests")
  class GetterTests {

    @Test
    @DisplayName("getWindowSeconds should return correct value")
    void getWindowSeconds_shouldReturnCorrectValue() {
      // given
      RateLimitBandDocument document = new RateLimitBandDocument(3600, 1000, "hourly");

      // when / then
      assertEquals(3600, document.getWindowSeconds());
    }

    @Test
    @DisplayName("getCapacity should return correct value")
    void getCapacity_shouldReturnCorrectValue() {
      // given
      RateLimitBandDocument document = new RateLimitBandDocument(60, 500, "per-minute");

      // when / then
      assertEquals(500, document.getCapacity());
    }

    @Test
    @DisplayName("getLabel should return correct value")
    void getLabel_shouldReturnCorrectValue() {
      // given
      String expectedLabel = "api-rate-limit";
      RateLimitBandDocument document = new RateLimitBandDocument(60, 100, expectedLabel);

      // when / then
      assertEquals(expectedLabel, document.getLabel());
    }
  }

  // ==================== Setter Tests ====================

  @Nested
  @DisplayName("Setter Tests")
  class SetterTests {

    @Test
    @DisplayName("setWindowSeconds should update value")
    void setWindowSeconds_shouldUpdateValue() {
      // given
      RateLimitBandDocument document = new RateLimitBandDocument(60, 100, "test");

      // when
      document.setWindowSeconds(120);

      // then
      assertEquals(120, document.getWindowSeconds());
    }

    @Test
    @DisplayName("setCapacity should update value")
    void setCapacity_shouldUpdateValue() {
      // given
      RateLimitBandDocument document = new RateLimitBandDocument(60, 100, "test");

      // when
      document.setCapacity(200);

      // then
      assertEquals(200, document.getCapacity());
    }

    @Test
    @DisplayName("setLabel should update value")
    void setLabel_shouldUpdateValue() {
      // given
      RateLimitBandDocument document = new RateLimitBandDocument(60, 100, "old-label");

      // when
      document.setLabel("new-label");

      // then
      assertEquals("new-label", document.getLabel());
    }

    @Test
    @DisplayName("setLabel should allow null")
    void setLabel_shouldAllowNull() {
      // given
      RateLimitBandDocument document = new RateLimitBandDocument(60, 100, "existing");

      // when
      document.setLabel(null);

      // then
      assertNull(document.getLabel());
    }
  }

  // ==================== Edge Case Tests ====================

  @Nested
  @DisplayName("Edge Case Tests")
  class EdgeCaseTests {

    @Test
    @DisplayName("should allow minimum valid windowSeconds (1)")
    void shouldAllowMinimumValidWindowSeconds() {
      // given / when
      RateLimitBandDocument document = new RateLimitBandDocument(1, 1, "min");

      // then
      assertEquals(1, document.getWindowSeconds());
    }

    @Test
    @DisplayName("should allow minimum valid capacity (1)")
    void shouldAllowMinimumValidCapacity() {
      // given / when
      RateLimitBandDocument document = new RateLimitBandDocument(1, 1, "min");

      // then
      assertEquals(1, document.getCapacity());
    }

    @Test
    @DisplayName("should allow large windowSeconds")
    void shouldAllowLargeWindowSeconds() {
      // given - 30 days in seconds
      long thirtyDays = 30L * 24 * 60 * 60;

      // when
      RateLimitBandDocument document = new RateLimitBandDocument(thirtyDays, 1000000, "monthly");

      // then
      assertEquals(thirtyDays, document.getWindowSeconds());
    }

    @Test
    @DisplayName("should allow large capacity")
    void shouldAllowLargeCapacity() {
      // given / when
      RateLimitBandDocument document = new RateLimitBandDocument(60, Long.MAX_VALUE, "unlimited");

      // then
      assertEquals(Long.MAX_VALUE, document.getCapacity());
    }
  }
}
