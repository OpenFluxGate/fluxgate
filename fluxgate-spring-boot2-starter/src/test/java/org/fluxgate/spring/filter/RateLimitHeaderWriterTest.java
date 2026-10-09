package org.fluxgate.spring.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.fluxgate.core.handler.RateLimitResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unit tests for {@link RateLimitHeaderWriter}. */
class RateLimitHeaderWriterTest {

  @Test
  void shouldWriteBothHeaderFamiliesForAnAllowedResponse() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    long resetAt = System.currentTimeMillis() + 30_000L;

    new RateLimitHeaderWriter(true, true)
        .write(response, RateLimitResponse.allowed(42, 0, 100, resetAt));

    assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("100");
    assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("42");
    assertThat(response.getHeader("X-RateLimit-Reset"))
        .isEqualTo(String.valueOf((resetAt + 999) / 1000));
    assertThat(response.getHeader("RateLimit-Limit")).isEqualTo("100");
    assertThat(response.getHeader("RateLimit-Remaining")).isEqualTo("42");
    // Delta seconds, not an epoch
    assertThat(Long.parseLong(response.getHeader("RateLimit-Reset"))).isBetween(25L, 31L);
    assertThat(response.getHeader("Retry-After")).isNull();
  }

  @Test
  void shouldWriteRetryAfterOnlyWhenRejected() {
    MockHttpServletResponse response = new MockHttpServletResponse();

    new RateLimitHeaderWriter(true, true).write(response, RateLimitResponse.rejected(30_000));

    assertThat(response.getHeader("Retry-After")).isEqualTo("30");
  }

  @Test
  void shouldRoundRetryAfterUpAndNeverEmitZero() {
    assertThat(RateLimitHeaderWriter.retryAfterSeconds(RateLimitResponse.rejected(1))).isEqualTo(1);
    assertThat(RateLimitHeaderWriter.retryAfterSeconds(RateLimitResponse.rejected(0))).isEqualTo(1);
    assertThat(RateLimitHeaderWriter.retryAfterSeconds(RateLimitResponse.rejected(-1)))
        .isEqualTo(1);
    assertThat(RateLimitHeaderWriter.retryAfterSeconds(RateLimitResponse.rejected(1001)))
        .isEqualTo(2);
  }

  @Test
  void shouldOmitUnknownValues() {
    MockHttpServletResponse response = new MockHttpServletResponse();

    new RateLimitHeaderWriter(true, true).write(response, RateLimitResponse.allowed(-1, 0, -1, -1));

    assertThat(response.getHeader("X-RateLimit-Limit")).isNull();
    assertThat(response.getHeader("X-RateLimit-Remaining")).isNull();
    assertThat(response.getHeader("X-RateLimit-Reset")).isNull();
    assertThat(response.getHeader("RateLimit-Limit")).isNull();
    assertThat(response.getHeader("RateLimit-Policy")).isNull();
  }

  @Test
  void shouldWriteThePolicyHeaderOnlyWhenTheWindowIsKnown() {
    MockHttpServletResponse withWindow = new MockHttpServletResponse();
    new RateLimitHeaderWriter(true, true)
        .write(withWindow, RateLimitResponse.allowed(42, 0, 100, -1), 60);
    assertThat(withWindow.getHeader("RateLimit-Policy")).isEqualTo("100;w=60");

    MockHttpServletResponse withoutWindow = new MockHttpServletResponse();
    new RateLimitHeaderWriter(true, true)
        .write(withoutWindow, RateLimitResponse.allowed(42, 0, 100, -1));
    assertThat(withoutWindow.getHeader("RateLimit-Policy")).isNull();
  }

  @Test
  void shouldTakeTheWindowFromTheResponseWithoutBeingToldAgain() {
    // The window travels with the decision, so the two argument overload must not lose it.
    MockHttpServletResponse response = new MockHttpServletResponse();

    new RateLimitHeaderWriter(true, true).write(response, withWindow(100, 60, true));

    assertThat(response.getHeader("RateLimit-Policy")).isEqualTo("100;w=60");
  }

  @Test
  void shouldWriteThePolicyHeaderOnRejectedResponsesToo() {
    MockHttpServletResponse response = new MockHttpServletResponse();

    new RateLimitHeaderWriter(true, true).write(response, withWindow(100, 60, false));

    assertThat(response.getHeader("RateLimit-Policy")).isEqualTo("100;w=60");
    assertThat(response.getHeader("Retry-After")).isNotNull();
  }

  @Test
  void shouldNeverAdvertiseAZeroWindowOrZeroLimit() {
    // getWindowSeconds() reports 0 for a sub-second window; "100;w=0" is not a quota a client can
    // act on, and neither is "0;w=60".
    MockHttpServletResponse subSecondWindow = new MockHttpServletResponse();
    new RateLimitHeaderWriter(true, true).write(subSecondWindow, withWindow(100, 0, true));
    assertThat(subSecondWindow.getHeader("RateLimit-Policy")).isNull();

    MockHttpServletResponse zeroLimit = new MockHttpServletResponse();
    new RateLimitHeaderWriter(true, true).write(zeroLimit, withWindow(0, 60, true));
    assertThat(zeroLimit.getHeader("RateLimit-Policy")).isNull();
  }

  @Test
  void shouldOmitThePolicyHeaderWhenStandardHeadersAreDisabled() {
    MockHttpServletResponse response = new MockHttpServletResponse();

    new RateLimitHeaderWriter(true, false).write(response, withWindow(100, 60, true));

    assertThat(response.getHeader("RateLimit-Policy")).isNull();
  }

  @Test
  void shouldLetAnExplicitWindowOverrideTheResponse() {
    MockHttpServletResponse response = new MockHttpServletResponse();

    new RateLimitHeaderWriter(true, true).write(response, withWindow(100, 60, true), 3600);

    assertThat(response.getHeader("RateLimit-Policy")).isEqualTo("100;w=3600");
  }

  /** Builds a decision carrying a band window, the way {@code RateLimitResponse.from} does. */
  private static RateLimitResponse withWindow(long limit, long windowSeconds, boolean allowed) {
    return RateLimitResponse.builder()
        .allowed(allowed)
        .remainingTokens(allowed ? 42 : 0)
        .retryAfterMillis(allowed ? 0 : 30_000)
        .limit(limit)
        .resetTimeMillis(-1)
        .windowSeconds(windowSeconds)
        .build();
  }

  @Test
  void shouldWriteOnlyTheEnabledFamilies() {
    MockHttpServletResponse legacyOnly = new MockHttpServletResponse();
    new RateLimitHeaderWriter(true, false)
        .write(legacyOnly, RateLimitResponse.allowed(42, 0, 100, -1));
    assertThat(legacyOnly.getHeader("X-RateLimit-Limit")).isEqualTo("100");
    assertThat(legacyOnly.getHeader("RateLimit-Limit")).isNull();

    MockHttpServletResponse standardOnly = new MockHttpServletResponse();
    new RateLimitHeaderWriter(false, true)
        .write(standardOnly, RateLimitResponse.allowed(42, 0, 100, -1));
    assertThat(standardOnly.getHeader("X-RateLimit-Limit")).isNull();
    assertThat(standardOnly.getHeader("RateLimit-Limit")).isEqualTo("100");
  }

  @Test
  void shouldStillWriteRetryAfterWhenBothFamiliesAreDisabled() {
    MockHttpServletResponse response = new MockHttpServletResponse();

    new RateLimitHeaderWriter(false, false).write(response, RateLimitResponse.rejected(5000));

    assertThat(response.getHeader("Retry-After")).isEqualTo("5");
  }

  @Test
  void shouldIgnoreANullResultOrResponse() {
    MockHttpServletResponse response = new MockHttpServletResponse();

    new RateLimitHeaderWriter(true, true).write(response, null);
    new RateLimitHeaderWriter(true, true).write(null, RateLimitResponse.rejected(1000));

    assertThat(response.getHeaderNames()).isEmpty();
  }
}
