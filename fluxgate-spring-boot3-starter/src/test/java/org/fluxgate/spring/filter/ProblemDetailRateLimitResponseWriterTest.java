package org.fluxgate.spring.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.fluxgate.core.handler.RateLimitResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unit tests for {@link ProblemDetailRateLimitResponseWriter}. */
class ProblemDetailRateLimitResponseWriterTest {

  private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");

  @Test
  void shouldWriteAnRfc9457ProblemDocument() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    new ProblemDetailRateLimitResponseWriter()
        .write(request, response, RateLimitResponse.rejected(30_000));

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getContentType()).isEqualTo("application/problem+json;charset=UTF-8");
    assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
    assertThat(response.getContentAsString())
        .contains("\"type\":\"about:blank\"")
        .contains("\"title\":\"Too Many Requests\"")
        .contains("\"status\":429")
        .contains("retry after 30 seconds")
        .contains("\"retryAfterMillis\":30000");
  }

  @Test
  void shouldUseTheConfiguredContentType() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    new ProblemDetailRateLimitResponseWriter("application/json", null)
        .write(request, response, RateLimitResponse.rejected(1000));

    assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
  }

  @Test
  void shouldNotDuplicateAnExplicitCharset() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    new ProblemDetailRateLimitResponseWriter("application/json;charset=ISO-8859-1", null)
        .write(request, response, RateLimitResponse.rejected(1000));

    assertThat(response.getContentType()).isEqualTo("application/json;charset=ISO-8859-1");
  }

  @Test
  void shouldSubstituteBodyTemplatePlaceholders() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    String template =
        "{\"code\":\"RATE_LIMITED\",\"status\":{status},\"after\":{retryAfterSeconds},"
            + "\"ms\":{retryAfterMillis},\"remaining\":{remaining},\"limit\":{limit}}";

    new ProblemDetailRateLimitResponseWriter(null, template)
        .write(request, response, RateLimitResponse.rejected(2500, null, 0, 100, -1));

    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\":\"RATE_LIMITED\",\"status\":429,\"after\":3,"
                + "\"ms\":2500,\"remaining\":0,\"limit\":100}");
  }

  @Test
  void shouldNotWriteToACommittedResponse() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.getWriter().write("already sent");
    response.flushBuffer();

    new ProblemDetailRateLimitResponseWriter()
        .write(request, response, RateLimitResponse.rejected(1000));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString()).isEqualTo("already sent");
  }
}
