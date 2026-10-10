package org.fluxgate.spring.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Unit tests for {@link ClientIpExtractor}. */
class ClientIpExtractorTest {

  private static final String XFF = "X-Forwarded-For";

  private MockHttpServletRequest request;

  @BeforeEach
  void setUp() {
    request = new MockHttpServletRequest();
    request.setRemoteAddr("10.0.0.1");
  }

  private String extract(TrustedProxies proxies) {
    return ClientIpExtractor.extract(request, XFF, true, proxies);
  }

  @Test
  @SuppressWarnings("deprecation")
  void shouldExtractIpFromXForwardedFor() {
    request.addHeader(XFF, "192.168.1.100");

    assertThat(ClientIpExtractor.extract(request)).isEqualTo("192.168.1.100");
  }

  @Test
  @SuppressWarnings("deprecation")
  void shouldExtractRightMostIpFromMultipleXForwardedFor() {
    // the right-most hop is appended by the immediate proxy, harder to forge
    request.addHeader(XFF, "203.0.113.50, 70.41.3.18, 150.172.238.178");

    assertThat(ClientIpExtractor.extract(request)).isEqualTo("150.172.238.178");
  }

  @Test
  @SuppressWarnings("deprecation")
  void shouldTrimWhitespaceFromXForwardedFor() {
    request.addHeader(XFF, "  192.168.1.100  ,  10.0.0.9  ");

    assertThat(ClientIpExtractor.extract(request)).isEqualTo("10.0.0.9");
  }

  @Test
  void shouldFallbackToRemoteAddrWhenNoXForwardedFor() {
    assertThat(extract(TrustedProxies.none())).isEqualTo("10.0.0.1");
  }

  @Test
  void shouldFallbackToRemoteAddrWhenXForwardedForIsEmpty() {
    request.addHeader(XFF, "");

    assertThat(extract(TrustedProxies.none())).isEqualTo("10.0.0.1");
  }

  @Test
  void shouldFallbackToRemoteAddrWhenXForwardedForIsBlank() {
    request.addHeader(XFF, "   ");

    assertThat(extract(TrustedProxies.none())).isEqualTo("10.0.0.1");
  }

  @Test
  @SuppressWarnings("deprecation")
  void shouldIgnoreForwardedHeaderWhenNotTrusted() {
    request.addHeader(XFF, "203.0.113.50");

    assertThat(ClientIpExtractor.extract(request, XFF, false)).isEqualTo("10.0.0.1");
  }

  @Test
  @SuppressWarnings("deprecation")
  void shouldUseConfiguredClientIpHeaderWhenTrusted() {
    request.addHeader("X-Real-IP", "203.0.113.10");

    assertThat(ClientIpExtractor.extract(request, "X-Real-IP", true)).isEqualTo("203.0.113.10");
  }

  // ===== C5 / C-3: trusted proxies =====

  @Test
  void shouldIgnoreForwardedHeaderWhenRemoteAddrIsNotATrustedProxy() {
    request.setRemoteAddr("203.0.113.9");
    request.addHeader(XFF, "198.51.100.1");

    assertThat(extract(TrustedProxies.of("10.0.0.0/8"))).isEqualTo("203.0.113.9");
  }

  @Test
  void shouldWalkTheForwardedChainFromTheRightSkippingTrustedProxies() {
    request.setRemoteAddr("10.0.0.5");
    request.addHeader(XFF, "9.9.9.9, 203.0.113.50, 192.168.1.7, 10.0.0.5");

    // the forged left-most hop loses to the first non-proxy hop from the right
    assertThat(extract(TrustedProxies.of("10.0.0.0/8", "192.168.0.0/16")))
        .isEqualTo("203.0.113.50");
  }

  @Test
  void shouldFallBackToRemoteAddrWhenEveryHopIsATrustedProxy() {
    request.setRemoteAddr("10.0.0.5");
    request.addHeader(XFF, "10.0.0.7, 10.0.0.5");

    assertThat(extract(TrustedProxies.of("10.0.0.0/8"))).isEqualTo("10.0.0.5");
  }

  @Test
  void shouldRejectForwardedValuesThatAreNotIpLiterals() {
    // an arbitrary string must never reach a bucket key
    request.setRemoteAddr("10.0.0.5");
    request.addHeader(XFF, "evil.example.com, ../../etc/passwd");

    assertThat(extract(TrustedProxies.of("10.0.0.0/8"))).isEqualTo("10.0.0.5");
  }

  @Test
  void shouldRejectHostnamesSpelledWithHexDigitsAndShorthandAddresses() {
    // "cafe1.de" passed the old character pre-screen and was resolved through DNS; "1.2.3" was
    // expanded by InetAddress to 1.2.0.3. Neither is an IP literal.
    request.setRemoteAddr("10.0.0.5");
    request.addHeader(XFF, "cafe1.de");
    assertThat(extract(TrustedProxies.of("10.0.0.0/8"))).isEqualTo("10.0.0.5");

    MockHttpServletRequest shorthand = new MockHttpServletRequest();
    shorthand.setRemoteAddr("10.0.0.5");
    shorthand.addHeader(XFF, "1.2.3");
    assertThat(ClientIpExtractor.extract(shorthand, XFF, true, TrustedProxies.of("10.0.0.0/8")))
        .isEqualTo("10.0.0.5");
  }

  @Test
  void shouldRejectForwardedValuesLongerThanAnIpAddress() {
    request.setRemoteAddr("10.0.0.5");
    request.addHeader(XFF, "1".repeat(200));

    assertThat(extract(TrustedProxies.of("10.0.0.0/8"))).isEqualTo("10.0.0.5");
  }

  @Test
  void shouldSupportIpv6TrustedProxies() {
    request.setRemoteAddr("2001:db8::1");
    request.addHeader(XFF, "203.0.113.50, 2001:db8::1");

    assertThat(extract(TrustedProxies.of("2001:db8::/32"))).isEqualTo("203.0.113.50");
  }

  @Test
  void shouldUseRightMostHopWhenNoTrustedProxiesAreConfigured() {
    request.addHeader(XFF, "203.0.113.50, 70.41.3.18");

    assertThat(extract(TrustedProxies.none())).isEqualTo("70.41.3.18");
  }

  // ===== R4: every header line, fail closed, normalisation =====

  @Nested
  class MultipleHeaderLines {

    @Test
    void shouldReadEveryHeaderLineAndUseTheRightMostHop() {
      // A client sends its own X-Forwarded-For line; the proxy appends a second line instead of
      // concatenating. Reading only the first line handed the client's value to the limiter.
      request.addHeader(XFF, "198.51.100.66");
      request.addHeader(XFF, "203.0.113.50");

      assertThat(extract(TrustedProxies.none())).isEqualTo("203.0.113.50");
    }

    @Test
    void shouldWalkHopsAcrossHeaderLinesWithTrustedProxies() {
      request.setRemoteAddr("10.0.0.5");
      request.addHeader(XFF, "198.51.100.66, 203.0.113.50");
      request.addHeader(XFF, "192.168.1.7");

      assertThat(extract(TrustedProxies.of("10.0.0.0/8", "192.168.0.0/16")))
          .isEqualTo("203.0.113.50");
    }

    @Test
    void shouldNotLetASpoofedFirstLineWinWhenTheLastLineIsTrusted() {
      request.setRemoteAddr("10.0.0.5");
      request.addHeader(XFF, "1.2.3.4");
      request.addHeader(XFF, "203.0.113.50, 10.0.0.9");

      assertThat(extract(TrustedProxies.of("10.0.0.0/8"))).isEqualTo("203.0.113.50");
    }
  }

  @Nested
  class FailClosed {

    @Test
    void shouldFallBackToRemoteAddrWhenTheRightMostHopIsNotAnIpLiteral() {
      // Previously the invalid hop was skipped and the client-controlled value to its left won.
      request.addHeader(XFF, "203.0.113.50, evil.example.com");

      assertThat(extract(TrustedProxies.none())).isEqualTo("10.0.0.1");
    }

    @Test
    void shouldFallBackToRemoteAddrWhenTheFirstUntrustedHopIsNotAnIpLiteral() {
      request.setRemoteAddr("10.0.0.5");
      request.addHeader(XFF, "203.0.113.50, unknown, 10.0.0.9");

      assertThat(extract(TrustedProxies.of("10.0.0.0/8"))).isEqualTo("10.0.0.5");
    }

    @Test
    void shouldFallBackToRemoteAddrForAnEmptyHop() {
      request.addHeader(XFF, "203.0.113.50, ");

      assertThat(extract(TrustedProxies.none())).isEqualTo("10.0.0.1");
    }

    @Test
    void shouldFallBackToRemoteAddrForAnInvalidPort() {
      request.addHeader(XFF, "203.0.113.50:http");

      assertThat(extract(TrustedProxies.none())).isEqualTo("10.0.0.1");
    }
  }

  @Nested
  class Normalisation {

    @Test
    void shouldStripThePortFromAnIpv4Hop() {
      request.addHeader(XFF, "203.0.113.50:51234");

      assertThat(extract(TrustedProxies.none())).isEqualTo("203.0.113.50");
    }

    @Test
    void shouldStripBracketsAndPortFromAnIpv6Hop() {
      request.addHeader(XFF, "[2001:db8::7]:4711");

      assertThat(extract(TrustedProxies.none())).isEqualTo("2001:db8:0:0:0:0:0:7");
    }

    @Test
    void shouldStripBracketsFromAnIpv6HopWithoutPort() {
      request.addHeader(XFF, "[2001:db8::7]");

      assertThat(extract(TrustedProxies.none())).isEqualTo("2001:db8:0:0:0:0:0:7");
    }

    @Test
    void shouldCanonicaliseEquivalentIpv6Spellings() {
      request.addHeader(XFF, "2001:DB8:0::7");
      String upper = extract(TrustedProxies.none());

      MockHttpServletRequest other = new MockHttpServletRequest();
      other.setRemoteAddr("10.0.0.1");
      other.addHeader(XFF, "2001:db8:0:0:0:0:0:7");
      String full = ClientIpExtractor.extract(other, XFF, true, TrustedProxies.none());

      assertThat(upper).isEqualTo(full).isEqualTo("2001:db8:0:0:0:0:0:7");
    }

    @Test
    void shouldReduceIpv4MappedIpv6ToIpv4() {
      request.addHeader(XFF, "::ffff:203.0.113.50");

      assertThat(extract(TrustedProxies.none())).isEqualTo("203.0.113.50");
    }

    @Test
    void shouldMatchTrustedProxiesAfterStrippingThePort() {
      request.setRemoteAddr("10.0.0.5");
      request.addHeader(XFF, "203.0.113.50:1234, 10.0.0.9:443");

      assertThat(extract(TrustedProxies.of("10.0.0.0/8"))).isEqualTo("203.0.113.50");
    }

    @Test
    void shouldCanonicaliseTheRemoteAddress() {
      request.setRemoteAddr("::1");

      assertThat(ClientIpExtractor.extract(request, XFF, false, TrustedProxies.none()))
          .isEqualTo("0:0:0:0:0:0:0:1");
    }

    @Test
    void shouldCanonicaliseAZonedRemoteAddressWithoutItsZone() {
      request.setRemoteAddr("fe80::1%eth0");

      assertThat(ClientIpExtractor.extract(request, XFF, false, TrustedProxies.none()))
          .isEqualTo("fe80:0:0:0:0:0:0:1");
    }

    @Test
    void shouldReturnANonLiteralRemoteAddressUnchanged() {
      request.setRemoteAddr("unix-socket");

      assertThat(ClientIpExtractor.extract(request, XFF, false, TrustedProxies.none()))
          .isEqualTo("unix-socket");
    }
  }
}
