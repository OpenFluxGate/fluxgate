package org.fluxgate.core.constants;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.fluxgate.core.constants.FluxgateConstants.Headers;
import org.fluxgate.core.constants.FluxgateConstants.Metrics;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FluxgateConstants}. */
class FluxgateConstantsTest {

  @Test
  void headersShouldCarryTheStandardRateLimitNames() {
    assertThat(Headers.RATE_LIMIT_LIMIT).isEqualTo("X-RateLimit-Limit");
    assertThat(Headers.RATE_LIMIT_REMAINING).isEqualTo("X-RateLimit-Remaining");
    assertThat(Headers.RATE_LIMIT_RESET).isEqualTo("X-RateLimit-Reset");
    assertThat(Headers.STANDARD_RATE_LIMIT_LIMIT).isEqualTo("RateLimit-Limit");
    assertThat(Headers.STANDARD_RATE_LIMIT_REMAINING).isEqualTo("RateLimit-Remaining");
    assertThat(Headers.STANDARD_RATE_LIMIT_RESET).isEqualTo("RateLimit-Reset");
    assertThat(Headers.STANDARD_RATE_LIMIT_POLICY).isEqualTo("RateLimit-Policy");
  }

  @Test
  void headersShouldCarryTheRequestMetadataNames() {
    assertThat(Headers.USER_AGENT).isEqualTo("User-Agent");
    assertThat(Headers.REFERER).isEqualTo("Referer");
  }

  @Test
  void requestsTotalShouldBeDeprecatedInFavourOfRequests() throws Exception {
    Field legacy = Metrics.class.getField("REQUESTS_TOTAL");

    assertThat(legacy.getAnnotation(Deprecated.class))
        .as("REQUESTS_TOTAL is superseded by fluxgate.requests with the result tag")
        .isNotNull();
    assertThat(legacy.get(null)).isEqualTo("fluxgate.requests.total");
    assertThat(Metrics.REQUESTS).isEqualTo("fluxgate.requests");
    assertThat(Metrics.TAG_RESULT).isEqualTo("result");
  }
}
