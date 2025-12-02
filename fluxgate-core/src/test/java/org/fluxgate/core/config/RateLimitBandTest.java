package org.fluxgate.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RateLimitBand}.
 * <p>
 * RateLimitBand represents a single rate limit band, such as:
 * - 1 minute, 100 requests
 * - 10 minutes, 500 requests
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
            RateLimitBand band = RateLimitBand.builder(window, capacity)
                    .label(label)
                    .build();

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
            NullPointerException exception = assertThrows(
                    NullPointerException.class,
                    () -> RateLimitBand.builder(null, 100).build()
            );
            assertTrue(exception.getMessage().contains("window must not be null"));
        }

        @Test
        @DisplayName("should throw IllegalArgumentException when capacity is zero")
        void build_shouldThrowWhenCapacityIsZero() {
            // given / when / then
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> RateLimitBand.builder(Duration.ofMinutes(1), 0).build()
            );
            assertTrue(exception.getMessage().contains("capacity must be > 0"));
        }

        @Test
        @DisplayName("should throw IllegalArgumentException when capacity is negative")
        void build_shouldThrowWhenCapacityIsNegative() {
            // given / when / then
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> RateLimitBand.builder(Duration.ofMinutes(1), -10).build()
            );
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
            RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100)
                    .label(expectedLabel)
                    .build();

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
            RateLimitBand band = RateLimitBand.builder(window, capacity)
                    .label(label)
                    .build();

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
}
