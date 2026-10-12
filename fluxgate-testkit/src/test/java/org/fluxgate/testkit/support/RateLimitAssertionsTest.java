package org.fluxgate.testkit.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RateLimitAssertions")
class RateLimitAssertionsTest {

  private static RateLimitResponse allowed(long remaining) {
    return RateLimitResponse.builder()
        .allowed(true)
        .remainingTokens(remaining)
        .retryAfterMillis(0L)
        .limit(10L)
        .bandLabel("10-per-60s")
        .build();
  }

  private static RateLimitResponse rejected(long retryAfterMillis) {
    return RateLimitResponse.builder()
        .allowed(false)
        .remainingTokens(0L)
        .retryAfterMillis(retryAfterMillis)
        .limit(10L)
        .bandLabel("10-per-60s")
        .build();
  }

  @Test
  @DisplayName("assertAllowed should pass on an allow and fail on a reject")
  void assertAllowed() {
    RateLimitAssertions.assertAllowed(allowed(3));

    assertThatThrownBy(() -> RateLimitAssertions.assertAllowed(rejected(1000)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("expected the request to be allowed")
        .hasMessageContaining("retryAfterMillis=1000")
        .hasMessageContaining("band=10-per-60s");
  }

  @Test
  @DisplayName("assertRejected should pass on a reject and fail on an allow")
  void assertRejected() {
    RateLimitAssertions.assertRejected(rejected(1000));

    assertThatThrownBy(() -> RateLimitAssertions.assertRejected(allowed(3)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("expected the request to be rejected")
        .hasMessageContaining("remaining=3");
  }

  @Test
  @DisplayName("assertAllowedWithRemaining should compare the remaining tokens")
  void assertAllowedWithRemaining() {
    RateLimitAssertions.assertAllowedWithRemaining(allowed(3), 3);

    assertThatThrownBy(() -> RateLimitAssertions.assertAllowedWithRemaining(allowed(3), 2))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("remaining tokens");
  }

  @Test
  @DisplayName("assertRejectedWithRetryAfter should bound the retry delay")
  void assertRejectedWithRetryAfter() {
    RateLimitAssertions.assertRejectedWithRetryAfter(rejected(1000), Duration.ofSeconds(2));
    RateLimitAssertions.assertRejectedWithRetryAfter(
        rejected(1000), Duration.ofMillis(500), Duration.ofSeconds(2));

    assertThatThrownBy(
            () ->
                RateLimitAssertions.assertRejectedWithRetryAfter(
                    rejected(5000), Duration.ofSeconds(2)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("retry delay");

    assertThatThrownBy(
            () ->
                RateLimitAssertions.assertRejectedWithRetryAfter(
                    rejected(100), Duration.ofSeconds(1), Duration.ofSeconds(2)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("retry delay");
  }

  @Test
  @DisplayName("assertRejectedWithRetryAfter should fail when no delay is advertised")
  void assertRejectedWithRetryAfterShouldFailWithoutDelay() {
    assertThatThrownBy(
            () ->
                RateLimitAssertions.assertRejectedWithRetryAfter(
                    rejected(0), Duration.ofSeconds(2)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("retry delay");
  }

  @Test
  @DisplayName("assertLimit and assertBand should compare the advertised quota")
  void assertLimitAndBand() {
    RateLimitAssertions.assertLimit(allowed(3), 10);
    RateLimitAssertions.assertBand(allowed(3), "10-per-60s");

    assertThatThrownBy(() -> RateLimitAssertions.assertLimit(allowed(3), 5))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("advertised limit");
    assertThatThrownBy(() -> RateLimitAssertions.assertBand(allowed(3), "other"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("binding band");
  }

  @Test
  @DisplayName("assertAllowedWithoutRule should require unknown remaining tokens")
  void assertAllowedWithoutRule() {
    RateLimitAssertions.assertAllowedWithoutRule(
        RateLimitResponse.from(RateLimitResult.allowedWithoutRule()));

    assertThatThrownBy(() -> RateLimitAssertions.assertAllowedWithoutRule(allowed(3)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("unknown remaining tokens");
  }

  @Test
  @DisplayName("should also assert on a core RateLimitResult")
  void shouldAssertOnRateLimitResult() {
    RateLimitResult allow = RateLimitResult.allowedWithoutRule();
    RateLimitResult reject =
        RateLimitResult.builder(RateLimitKey.of("ip:10.0.0.1"))
            .allowed(false)
            .remainingTokens(0L)
            .nanosToWaitForRefill(1_000_000L)
            .build();

    RateLimitAssertions.assertAllowed(allow);
    RateLimitAssertions.assertRejected(reject);

    assertThatThrownBy(() -> RateLimitAssertions.assertAllowed(reject))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("expected the request to be allowed");
    assertThatThrownBy(() -> RateLimitAssertions.assertRejected(allow))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("expected the request to be rejected");
  }

  @Test
  @DisplayName("should fail loudly on a null decision instead of NPEing")
  void shouldFailOnNull() {
    assertThatThrownBy(() -> RateLimitAssertions.assertAllowed((RateLimitResponse) null))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("rate limit decision");
    assertThatThrownBy(() -> RateLimitAssertions.assertRejected((RateLimitResult) null))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("rate limit result");
  }
}
