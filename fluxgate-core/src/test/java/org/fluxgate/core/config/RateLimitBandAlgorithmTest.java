package org.fluxgate.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.ZoneId;
import org.fluxgate.core.exception.InvalidRuleConfigException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RateLimitBand algorithm & quota tests")
class RateLimitBandAlgorithmTest {

  // ===== getKeyLabel stability for existing TOKEN_BUCKET bands =====

  @Nested
  @DisplayName("getKeyLabel stability (TOKEN_BUCKET)")
  class KeyLabelStabilityTests {

    @Test
    @DisplayName("whole-second TOKEN_BUCKET label unchanged")
    void tokenBucket_wholeSecond() {
      RateLimitBand b = RateLimitBand.builder(Duration.ofSeconds(60), 100).build();
      assertThat(b.getKeyLabel()).isEqualTo("100-per-60s");
    }

    @Test
    @DisplayName("millisecond TOKEN_BUCKET label unchanged")
    void tokenBucket_millis() {
      RateLimitBand b = RateLimitBand.builder(Duration.ofMillis(500), 50).build();
      assertThat(b.getKeyLabel()).isEqualTo("50-per-500ms");
    }

    @Test
    @DisplayName("explicit label takes precedence regardless of algorithm")
    void explicitLabel_takesPreference() {
      RateLimitBand b =
          RateLimitBand.builder(Duration.ofSeconds(60), 100)
              .label("my-label")
              .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
              .build();
      assertThat(b.getKeyLabel()).isEqualTo("my-label");
    }
  }

  // ===== SLIDING_WINDOW label =====

  @Nested
  @DisplayName("SLIDING_WINDOW key label")
  class SlidingWindowLabelTests {

    @Test
    @DisplayName("SLIDING_WINDOW label has -sw suffix")
    void slidingWindow_swSuffix() {
      RateLimitBand b =
          RateLimitBand.builder(Duration.ofSeconds(60), 100)
              .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
              .build();
      assertThat(b.getKeyLabel()).isEqualTo("100-per-60s-sw");
    }

    @Test
    @DisplayName("SLIDING_WINDOW with millis window")
    void slidingWindow_millis() {
      RateLimitBand b =
          RateLimitBand.builder(Duration.ofMillis(200), 10)
              .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
              .build();
      assertThat(b.getKeyLabel()).isEqualTo("10-per-200ms-sw");
    }
  }

  // ===== FIXED_WINDOW label =====

  @Nested
  @DisplayName("FIXED_WINDOW key label")
  class FixedWindowLabelTests {

    @Test
    @DisplayName("FIXED_WINDOW without quotaPeriod has -fw suffix")
    void fixedWindow_noQuota() {
      RateLimitBand b =
          RateLimitBand.builder(Duration.ofSeconds(60), 100)
              .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
              .build();
      assertThat(b.getKeyLabel()).isEqualTo("100-per-60s-fw");
    }

    @Test
    @DisplayName("FIXED_WINDOW + MONTHLY has -monthly suffix (spec example)")
    void fixedWindow_monthly() {
      RateLimitBand b =
          RateLimitBand.builder(Duration.ofDays(30), 1000)
              .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
              .quotaPeriod(QuotaPeriod.MONTHLY)
              .build();
      assertThat(b.getKeyLabel()).isEqualTo("1000-per-30d-monthly");
    }

    @Test
    @DisplayName("FIXED_WINDOW + DAILY has -daily suffix")
    void fixedWindow_daily() {
      RateLimitBand b =
          RateLimitBand.builder(Duration.ofDays(1), 500)
              .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
              .quotaPeriod(QuotaPeriod.DAILY)
              .build();
      assertThat(b.getKeyLabel()).isEqualTo("500-per-1d-daily");
    }

    @Test
    @DisplayName("FIXED_WINDOW + WEEKLY has -weekly suffix")
    void fixedWindow_weekly() {
      RateLimitBand b =
          RateLimitBand.builder(Duration.ofDays(7), 2000)
              .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
              .quotaPeriod(QuotaPeriod.WEEKLY)
              .build();
      assertThat(b.getKeyLabel()).isEqualTo("2000-per-7d-weekly");
    }
  }

  // ===== build() validation =====

  @Nested
  @DisplayName("build() validation")
  class ValidationTests {

    @Test
    @DisplayName("quotaPeriod without FIXED_WINDOW throws InvalidRuleConfigException")
    void quotaPeriodWithoutFixedWindow_throws() {
      assertThatThrownBy(
              () ->
                  RateLimitBand.builder(Duration.ofDays(1), 100)
                      .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                      .quotaPeriod(QuotaPeriod.DAILY)
                      .build())
          .isInstanceOf(InvalidRuleConfigException.class)
          .hasMessageContaining("FIXED_WINDOW");
    }

    @Test
    @DisplayName("quotaPeriod without explicit algorithm (TOKEN_BUCKET default) throws")
    void quotaPeriodWithoutAlgorithm_throws() {
      assertThatThrownBy(
              () ->
                  RateLimitBand.builder(Duration.ofDays(1), 100)
                      .quotaPeriod(QuotaPeriod.DAILY)
                      .build())
          .isInstanceOf(InvalidRuleConfigException.class);
    }

    @Test
    @DisplayName("slidingWindowBuckets below 2 throws InvalidRuleConfigException")
    void slidingWindowBuckets_tooLow() {
      assertThatThrownBy(
              () ->
                  RateLimitBand.builder(Duration.ofSeconds(60), 100)
                      .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
                      .slidingWindowBuckets(1)
                      .build())
          .isInstanceOf(InvalidRuleConfigException.class)
          .hasMessageContaining("slidingWindowBuckets");
    }

    @Test
    @DisplayName("slidingWindowBuckets above 60 throws InvalidRuleConfigException")
    void slidingWindowBuckets_tooHigh() {
      assertThatThrownBy(
              () ->
                  RateLimitBand.builder(Duration.ofSeconds(60), 100)
                      .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
                      .slidingWindowBuckets(61)
                      .build())
          .isInstanceOf(InvalidRuleConfigException.class)
          .hasMessageContaining("slidingWindowBuckets");
    }

    @Test
    @DisplayName("slidingWindowBuckets in [2,60] is valid")
    void slidingWindowBuckets_validRange() {
      RateLimitBand b =
          RateLimitBand.builder(Duration.ofSeconds(60), 100)
              .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
              .slidingWindowBuckets(12)
              .build();
      assertThat(b.getSlidingWindowBuckets()).isEqualTo(12);
    }

    @Test
    @DisplayName("FIXED_WINDOW + quotaPeriod + custom zoneId is valid")
    void fixedWindow_customZone() {
      ZoneId zone = ZoneId.of("America/New_York");
      RateLimitBand b =
          RateLimitBand.builder(Duration.ofDays(1), 100)
              .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
              .quotaPeriod(QuotaPeriod.DAILY)
              .zoneId(zone)
              .build();
      assertThat(b.getZoneId()).isEqualTo(zone);
    }
  }

  // ===== default algorithm =====

  @Test
  @DisplayName("default algorithm is TOKEN_BUCKET")
  void defaultAlgorithm_isTokenBucket() {
    RateLimitBand b = RateLimitBand.builder(Duration.ofSeconds(10), 50).build();
    assertThat(b.getAlgorithm()).isEqualTo(RateLimitAlgorithm.TOKEN_BUCKET);
  }

  @Test
  @DisplayName("default slidingWindowBuckets is 10")
  void defaultSlidingWindowBuckets() {
    RateLimitBand b =
        RateLimitBand.builder(Duration.ofSeconds(60), 100)
            .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
            .build();
    assertThat(b.getSlidingWindowBuckets()).isEqualTo(10);
  }
}
