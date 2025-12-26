package org.fluxgate.spring.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link ClientIpExtractor}. */
@ExtendWith(MockitoExtension.class)
class ClientIpExtractorTest {

  @Mock private HttpServletRequest request;

  @Test
  void shouldExtractIpFromXForwardedFor() {
    // Given
    when(request.getHeader("X-Forwarded-For")).thenReturn("192.168.1.100");

    // When
    String ip = ClientIpExtractor.extract(request);

    // Then
    assertThat(ip).isEqualTo("192.168.1.100");
  }

  @Test
  void shouldExtractFirstIpFromMultipleXForwardedFor() {
    // Given
    when(request.getHeader("X-Forwarded-For"))
        .thenReturn("203.0.113.50, 70.41.3.18, 150.172.238.178");

    // When
    String ip = ClientIpExtractor.extract(request);

    // Then
    assertThat(ip).isEqualTo("203.0.113.50");
  }

  @Test
  void shouldTrimWhitespaceFromXForwardedFor() {
    // Given
    when(request.getHeader("X-Forwarded-For")).thenReturn("  192.168.1.100  , 10.0.0.1");

    // When
    String ip = ClientIpExtractor.extract(request);

    // Then
    assertThat(ip).isEqualTo("192.168.1.100");
  }

  @Test
  void shouldFallbackToRemoteAddrWhenNoXForwardedFor() {
    // Given
    when(request.getHeader("X-Forwarded-For")).thenReturn(null);
    when(request.getRemoteAddr()).thenReturn("10.0.0.1");

    // When
    String ip = ClientIpExtractor.extract(request);

    // Then
    assertThat(ip).isEqualTo("10.0.0.1");
  }

  @Test
  void shouldFallbackToRemoteAddrWhenXForwardedForIsEmpty() {
    // Given
    when(request.getHeader("X-Forwarded-For")).thenReturn("");
    when(request.getRemoteAddr()).thenReturn("10.0.0.1");

    // When
    String ip = ClientIpExtractor.extract(request);

    // Then
    assertThat(ip).isEqualTo("10.0.0.1");
  }

  @Test
  void shouldFallbackToRemoteAddrWhenXForwardedForIsBlank() {
    // Given
    when(request.getHeader("X-Forwarded-For")).thenReturn("   ");
    when(request.getRemoteAddr()).thenReturn("10.0.0.1");

    // When
    String ip = ClientIpExtractor.extract(request);

    // Then
    assertThat(ip).isEqualTo("10.0.0.1");
  }
}
