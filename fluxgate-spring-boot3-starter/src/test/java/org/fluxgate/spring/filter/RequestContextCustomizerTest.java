package org.fluxgate.spring.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.fluxgate.core.context.RequestContext;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RequestContextCustomizer}. */
class RequestContextCustomizerTest {

  @Test
  void identity_shouldReturnBuilderUnchanged() {
    // Given
    RequestContextCustomizer customizer = RequestContextCustomizer.identity();
    HttpServletRequest request = mock(HttpServletRequest.class);

    RequestContext.Builder builder =
        RequestContext.builder().clientIp("192.168.1.1").endpoint("/api/test").method("GET");

    // When
    RequestContext.Builder result = customizer.customize(builder, request);

    // Then
    assertThat(result).isSameAs(builder);
    RequestContext context = result.build();
    assertThat(context.getClientIp()).isEqualTo("192.168.1.1");
  }

  @Test
  void andThen_shouldCombineCustomizers() {
    // Given
    RequestContextCustomizer first =
        (builder, req) -> {
          builder.attribute("first", "value1");
          return builder;
        };

    RequestContextCustomizer second =
        (builder, req) -> {
          builder.attribute("second", "value2");
          return builder;
        };

    RequestContextCustomizer combined = first.andThen(second);
    HttpServletRequest request = mock(HttpServletRequest.class);

    RequestContext.Builder builder = RequestContext.builder().clientIp("192.168.1.1");

    // When
    RequestContext.Builder result = combined.customize(builder, request);

    // Then
    RequestContext context = result.build();
    assertThat(context.getAttribute("first")).isEqualTo("value1");
    assertThat(context.getAttribute("second")).isEqualTo("value2");
  }

  @Test
  void customizer_canOverrideExistingValues() {
    // Given
    RequestContextCustomizer customizer =
        (builder, req) -> {
          String cfIp = req.getHeader("CF-Connecting-IP");
          if (cfIp != null) {
            builder.clientIp(cfIp);
          }
          return builder;
        };

    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("CF-Connecting-IP")).thenReturn("203.0.113.50");

    RequestContext.Builder builder =
        RequestContext.builder().clientIp("10.0.0.1").endpoint("/api/test").method("GET");

    // When
    RequestContext.Builder result = customizer.customize(builder, request);

    // Then
    RequestContext context = result.build();
    assertThat(context.getClientIp()).isEqualTo("203.0.113.50");
  }

  @Test
  void customizer_canReadBuilderCurrentValues() {
    // Given
    RequestContextCustomizer customizer =
        (builder, req) -> {
          // Read current values from builder
          String currentIp = builder.getClientIp();
          String currentEndpoint = builder.getEndpoint();

          // Add attributes based on current values
          builder.attribute("originalIp", currentIp);
          builder.attribute("originalEndpoint", currentEndpoint);

          return builder;
        };

    HttpServletRequest request = mock(HttpServletRequest.class);

    RequestContext.Builder builder =
        RequestContext.builder().clientIp("192.168.1.100").endpoint("/api/users").method("GET");

    // When
    RequestContext.Builder result = customizer.customize(builder, request);

    // Then
    RequestContext context = result.build();
    assertThat(context.getAttribute("originalIp")).isEqualTo("192.168.1.100");
    assertThat(context.getAttribute("originalEndpoint")).isEqualTo("/api/users");
  }

  @Test
  void customizer_canAddMultipleHeaders() {
    // Given
    RequestContextCustomizer customizer =
        (builder, req) -> {
          builder.header("X-Custom-1", "value1");
          builder.header("X-Custom-2", "value2");
          return builder;
        };

    HttpServletRequest request = mock(HttpServletRequest.class);

    RequestContext.Builder builder = RequestContext.builder().clientIp("192.168.1.1");

    // When
    RequestContext.Builder result = customizer.customize(builder, request);

    // Then
    RequestContext context = result.build();
    assertThat(context.getHeader("X-Custom-1")).isEqualTo("value1");
    assertThat(context.getHeader("X-Custom-2")).isEqualTo("value2");
  }

  @Test
  void customizer_canRemoveHeaders() {
    // Given
    RequestContextCustomizer customizer =
        (builder, req) -> {
          // Remove sensitive headers
          builder.getHeaders().remove("Authorization");
          builder.getHeaders().remove("Cookie");
          return builder;
        };

    HttpServletRequest request = mock(HttpServletRequest.class);

    RequestContext.Builder builder =
        RequestContext.builder()
            .clientIp("192.168.1.1")
            .header("Authorization", "Bearer secret-token")
            .header("Cookie", "session=abc123")
            .header("Accept", "application/json");

    // When
    RequestContext.Builder result = customizer.customize(builder, request);

    // Then
    RequestContext context = result.build();
    assertThat(context.getHeader("Authorization")).isNull();
    assertThat(context.getHeader("Cookie")).isNull();
    assertThat(context.getHeader("Accept")).isEqualTo("application/json");
  }

  @Test
  void andThen_shouldApplyInOrder() {
    // Given - First customizer sets value, second modifies it
    RequestContextCustomizer first =
        (builder, req) -> {
          builder.attribute("counter", 1);
          return builder;
        };

    RequestContextCustomizer second =
        (builder, req) -> {
          Integer current = (Integer) builder.getAttribute("counter");
          builder.attribute("counter", current + 1);
          return builder;
        };

    RequestContextCustomizer third =
        (builder, req) -> {
          Integer current = (Integer) builder.getAttribute("counter");
          builder.attribute("counter", current + 1);
          return builder;
        };

    RequestContextCustomizer combined = first.andThen(second).andThen(third);
    HttpServletRequest request = mock(HttpServletRequest.class);

    RequestContext.Builder builder = RequestContext.builder().clientIp("192.168.1.1");

    // When
    RequestContext.Builder result = combined.customize(builder, request);

    // Then
    RequestContext context = result.build();
    assertThat(context.getAttribute("counter")).isEqualTo(3);
  }
}
