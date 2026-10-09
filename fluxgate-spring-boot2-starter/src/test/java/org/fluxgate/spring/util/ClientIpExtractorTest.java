package org.fluxgate.spring.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletRequest;
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
  void shouldExtractRightMostIpFromMultipleXForwardedFor() {
    // Given - right-most hop is appended by the immediate proxy, harder to forge
    when(request.getHeader("X-Forwarded-For"))
        .thenReturn("203.0.113.50, 70.41.3.18, 150.172.238.178");

    // When
    String ip = ClientIpExtractor.extract(request);

    // Then - right-most valid hop, not the left-most client-supplied value
    assertThat(ip).isEqualTo("150.172.238.178");
  }

  @Test
  void shouldTrimWhitespaceFromXForwardedFor() {
    // Given
    when(request.getHeader("X-Forwarded-For")).thenReturn("  192.168.1.100  , 10.0.0.1");

    // When
    String ip = ClientIpExtractor.extract(request);

    // Then - right-most valid hop
    assertThat(ip).isEqualTo("10.0.0.1");
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

  @Test
  void shouldIgnoreForwardedHeaderWhenNotTrusted() {
    // Given
    when(request.getRemoteAddr()).thenReturn("10.0.0.1");

    // When
    String ip = ClientIpExtractor.extract(request, "X-Forwarded-For", false);

    // Then
    assertThat(ip).isEqualTo("10.0.0.1");
  }

  @Test
  void shouldUseConfiguredClientIpHeaderWhenTrusted() {
    // Given
    when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.10");

    // When
    String ip = ClientIpExtractor.extract(request, "X-Real-IP", true);

    // Then
    assertThat(ip).isEqualTo("203.0.113.10");
  }

  // ===== C5 / C-3: trusted proxies =====

  @Test
  void shouldIgnoreForwardedHeaderWhenRemoteAddrIsNotATrustedProxy() {
    // Given
    when(request.getRemoteAddr()).thenReturn("203.0.113.9");

    // When
    String ip =
        ClientIpExtractor.extract(
            request, "X-Forwarded-For", true, TrustedProxies.of("10.0.0.0/8"));

    // Then - the header is never even read
    assertThat(ip).isEqualTo("203.0.113.9");
  }

  @Test
  void shouldWalkTheForwardedChainFromTheRightSkippingTrustedProxies() {
    // Given
    when(request.getRemoteAddr()).thenReturn("10.0.0.5");
    when(request.getHeader("X-Forwarded-For"))
        .thenReturn("9.9.9.9, 203.0.113.50, 192.168.1.7, 10.0.0.5");

    // When
    String ip =
        ClientIpExtractor.extract(
            request, "X-Forwarded-For", true, TrustedProxies.of("10.0.0.0/8", "192.168.0.0/16"));

    // Then - the forged left-most hop loses to the first non-proxy hop from the right
    assertThat(ip).isEqualTo("203.0.113.50");
  }

  @Test
  void shouldFallBackToRemoteAddrWhenEveryHopIsATrustedProxy() {
    // Given
    when(request.getRemoteAddr()).thenReturn("10.0.0.5");
    when(request.getHeader("X-Forwarded-For")).thenReturn("10.0.0.7, 10.0.0.5");

    // When
    String ip =
        ClientIpExtractor.extract(
            request, "X-Forwarded-For", true, TrustedProxies.of("10.0.0.0/8"));

    // Then
    assertThat(ip).isEqualTo("10.0.0.5");
  }

  @Test
  void shouldRejectForwardedValuesThatAreNotIpLiterals() {
    // Given - an arbitrary string must never reach a bucket key
    when(request.getRemoteAddr()).thenReturn("10.0.0.5");
    when(request.getHeader("X-Forwarded-For")).thenReturn("evil.example.com, ../../etc/passwd");

    // When
    String ip =
        ClientIpExtractor.extract(
            request, "X-Forwarded-For", true, TrustedProxies.of("10.0.0.0/8"));

    // Then
    assertThat(ip).isEqualTo("10.0.0.5");
  }

  @Test
  void shouldRejectForwardedValuesLongerThanAnIpAddress() {
    // Given
    String oversized = "1".repeat(200);
    when(request.getRemoteAddr()).thenReturn("10.0.0.5");
    when(request.getHeader("X-Forwarded-For")).thenReturn(oversized);

    // When
    String ip =
        ClientIpExtractor.extract(
            request, "X-Forwarded-For", true, TrustedProxies.of("10.0.0.0/8"));

    // Then
    assertThat(ip).isEqualTo("10.0.0.5");
  }

  @Test
  void shouldSupportIpv6TrustedProxies() {
    // Given
    when(request.getRemoteAddr()).thenReturn("2001:db8::1");
    when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 2001:db8::1");

    // When
    String ip =
        ClientIpExtractor.extract(
            request, "X-Forwarded-For", true, TrustedProxies.of("2001:db8::/32"));

    // Then
    assertThat(ip).isEqualTo("203.0.113.50");
  }

  @Test
  void shouldUseRightMostHopWhenNoTrustedProxiesAreConfigured() {
    // Given - the right-most hop (appended by the immediate proxy) is returned when
    // trusted-proxies is empty; a startup WARN tells operators to configure the list
    when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 70.41.3.18");

    // When
    String ip = ClientIpExtractor.extract(request, "X-Forwarded-For", true, TrustedProxies.none());

    // Then - right-most valid hop, not the left-most client-supplied value
    assertThat(ip).isEqualTo("70.41.3.18");
  }

  @Test
  void shouldSkipInvalidHopsAndUseRightMostValidOneWhenNoTrustedProxiesConfigured() {
    // Given - the right-most invalid hops are skipped; fall back through to the first valid one
    when(request.getRemoteAddr()).thenReturn("10.0.0.1");
    when(request.getHeader("X-Forwarded-For"))
        .thenReturn("203.0.113.50, evil.example.com, bad/../path");

    // When
    String ip = ClientIpExtractor.extract(request, "X-Forwarded-For", true, TrustedProxies.none());

    // Then - right-most valid IP literal wins
    assertThat(ip).isEqualTo("203.0.113.50");
  }
}
