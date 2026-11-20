package org.fluxgate.testkit.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.core.ratelimiter.RateLimitResult;

/**
 * AssertJ based assertions for a rate limit decision.
 *
 * <p>{@code assertThat(response.isAllowed()).isTrue()} tells you a decision was wrong but not why.
 * These helpers fail with the whole decision in the message — remaining tokens, limit, retry delay
 * and band — so a red test usually needs no debugger.
 *
 * <pre>{@code
 * import static org.fluxgate.testkit.support.RateLimitAssertions.*;
 *
 * assertAllowed(handler.tryConsume(ctx, "api-limits"));
 * assertAllowedWithRemaining(handler.tryConsume(ctx, "api-limits"), 0);
 * assertRejectedWithRetryAfter(handler.tryConsume(ctx, "api-limits"), Duration.ofMinutes(1));
 * }</pre>
 *
 * <p>There is deliberately no servlet assertion here: {@code spring-test} is not a dependency of
 * the testkit, so asserting on a {@code MockHttpServletResponse} belongs in the starter's own
 * tests.
 *
 * @see InMemoryRateLimitHandler
 */
public final class RateLimitAssertions {

  private RateLimitAssertions() {}

  /**
   * Asserts the request was allowed.
   *
   * @param response the decision (must not be null)
   */
  public static void assertAllowed(RateLimitResponse response) {
    assertThat(response).as("rate limit decision").isNotNull();
    assertThat(response.isAllowed())
        .as("expected the request to be allowed, but %s", describe(response))
        .isTrue();
  }

  /**
   * Asserts the request was allowed and the band reports the expected remaining tokens.
   *
   * @param response the decision (must not be null)
   * @param expectedRemaining the expected value of {@code remainingTokens}
   */
  public static void assertAllowedWithRemaining(
      RateLimitResponse response, long expectedRemaining) {
    assertAllowed(response);
    assertThat(response.getRemainingTokens())
        .as("remaining tokens of %s", describe(response))
        .isEqualTo(expectedRemaining);
  }

  /**
   * Asserts the request was rejected.
   *
   * @param response the decision (must not be null)
   */
  public static void assertRejected(RateLimitResponse response) {
    assertThat(response).as("rate limit decision").isNotNull();
    assertThat(response.isAllowed())
        .as("expected the request to be rejected, but %s", describe(response))
        .isFalse();
  }

  /**
   * Asserts the request was rejected and the advertised retry delay is at most the given bound.
   *
   * <p>An upper bound rather than an exact value, because the delay counts down from the moment the
   * bucket emptied: asserting equality makes the test fail whenever the machine is slow.
   *
   * @param response the decision (must not be null)
   * @param maxRetryAfter the longest delay the test accepts (must not be null)
   */
  public static void assertRejectedWithRetryAfter(
      RateLimitResponse response, Duration maxRetryAfter) {
    assertRejected(response);
    assertThat(maxRetryAfter).as("maxRetryAfter").isNotNull();
    assertThat(response.getRetryAfterMillis())
        .as("retry delay of %s", describe(response))
        .isGreaterThan(0L)
        .isLessThanOrEqualTo(maxRetryAfter.toMillis());
  }

  /**
   * Asserts the request was rejected and the advertised retry delay falls inside the given range,
   * both bounds inclusive.
   *
   * @param response the decision (must not be null)
   * @param minRetryAfter the shortest delay the test accepts (must not be null)
   * @param maxRetryAfter the longest delay the test accepts (must not be null)
   */
  public static void assertRejectedWithRetryAfter(
      RateLimitResponse response, Duration minRetryAfter, Duration maxRetryAfter) {
    assertRejected(response);
    assertThat(minRetryAfter).as("minRetryAfter").isNotNull();
    assertThat(maxRetryAfter).as("maxRetryAfter").isNotNull();
    assertThat(response.getRetryAfterMillis())
        .as("retry delay of %s", describe(response))
        .isBetween(minRetryAfter.toMillis(), maxRetryAfter.toMillis());
  }

  /**
   * Asserts the decision advertises the given band capacity, which is what reaches the client as
   * {@code RateLimit-Limit}.
   *
   * @param response the decision (must not be null)
   * @param expectedLimit the expected capacity of the binding band
   */
  public static void assertLimit(RateLimitResponse response, long expectedLimit) {
    assertThat(response).as("rate limit decision").isNotNull();
    assertThat(response.getLimit())
        .as("advertised limit of %s", describe(response))
        .isEqualTo(expectedLimit);
  }

  /**
   * Asserts which band produced the decision, identified by its key label — the explicit band
   * label, or {@code <capacity>-per-<windowSeconds>s} when the band has none.
   *
   * @param response the decision (must not be null)
   * @param expectedBandLabel the expected band key label
   */
  public static void assertBand(RateLimitResponse response, String expectedBandLabel) {
    assertThat(response).as("rate limit decision").isNotNull();
    assertThat(response.getBandLabel())
        .as("binding band of %s", describe(response))
        .isEqualTo(expectedBandLabel);
  }

  /**
   * Asserts the request was allowed by a decision that consulted no rule at all, which is what an
   * unlimited path looks like.
   *
   * @param response the decision (must not be null)
   */
  public static void assertAllowedWithoutRule(RateLimitResponse response) {
    assertAllowed(response);
    assertThat(response.getRemainingTokens())
        .as("an allow without a rule reports unknown remaining tokens, but %s", describe(response))
        .isEqualTo(-1L);
  }

  /**
   * Asserts on a core {@link RateLimitResult}, for tests that call a {@code RateLimiter} directly
   * instead of going through a handler.
   *
   * @param result the result (must not be null)
   */
  public static void assertAllowed(RateLimitResult result) {
    assertThat(result).as("rate limit result").isNotNull();
    assertThat(result.isAllowed())
        .as("expected the request to be allowed, but %s", result)
        .isTrue();
  }

  /**
   * Asserts a core {@link RateLimitResult} was rejected.
   *
   * @param result the result (must not be null)
   */
  public static void assertRejected(RateLimitResult result) {
    assertThat(result).as("rate limit result").isNotNull();
    assertThat(result.isAllowed())
        .as("expected the request to be rejected, but %s", result)
        .isFalse();
  }

  /** Renders a decision compactly enough to read inside an assertion message. */
  private static String describe(RateLimitResponse response) {
    return "it was "
        + (response.isAllowed() ? "allowed" : "rejected")
        + " (remaining="
        + response.getRemainingTokens()
        + ", limit="
        + response.getLimit()
        + ", retryAfterMillis="
        + response.getRetryAfterMillis()
        + ", band="
        + response.getBandLabel()
        + ")";
  }
}
