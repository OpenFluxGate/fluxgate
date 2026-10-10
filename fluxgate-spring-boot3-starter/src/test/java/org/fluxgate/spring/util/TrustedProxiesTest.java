package org.fluxgate.spring.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TrustedProxies}. */
class TrustedProxiesTest {

  @Test
  void shouldTrustNothingWhenEmpty() {
    assertThat(TrustedProxies.none().isEmpty()).isTrue();
    assertThat(TrustedProxies.none().contains("10.0.0.1")).isFalse();
    assertThat(TrustedProxies.of(Collections.emptyList()).isEmpty()).isTrue();
    assertThat(TrustedProxies.of((java.util.List<String>) null).isEmpty()).isTrue();
  }

  @Test
  void shouldMatchAnExactIpv4Literal() {
    TrustedProxies proxies = TrustedProxies.of("192.168.1.7");

    assertThat(proxies.contains("192.168.1.7")).isTrue();
    assertThat(proxies.contains("192.168.1.8")).isFalse();
  }

  @Test
  void shouldMatchAnIpv4Cidr() {
    TrustedProxies proxies = TrustedProxies.of("10.0.0.0/8");

    assertThat(proxies.contains("10.0.0.1")).isTrue();
    assertThat(proxies.contains("10.255.255.254")).isTrue();
    assertThat(proxies.contains("11.0.0.1")).isFalse();
  }

  @Test
  void shouldRespectPrefixesThatAreNotByteAligned() {
    TrustedProxies proxies = TrustedProxies.of("192.168.1.0/25");

    assertThat(proxies.contains("192.168.1.1")).isTrue();
    assertThat(proxies.contains("192.168.1.127")).isTrue();
    assertThat(proxies.contains("192.168.1.128")).isFalse();
  }

  @Test
  void shouldMatchAnIpv6Cidr() {
    TrustedProxies proxies = TrustedProxies.of("2001:db8::/32");

    assertThat(proxies.contains("2001:db8::1")).isTrue();
    assertThat(proxies.contains("2001:db8:ffff::1")).isTrue();
    assertThat(proxies.contains("2001:db9::1")).isFalse();
  }

  @Test
  void shouldNotMatchAcrossAddressFamilies() {
    assertThat(TrustedProxies.of("10.0.0.0/8").contains("2001:db8::1")).isFalse();
    assertThat(TrustedProxies.of("2001:db8::/32").contains("10.0.0.1")).isFalse();
  }

  @Test
  void shouldIgnoreUnparseableEntriesWithoutWideningTheSet() {
    TrustedProxies proxies =
        TrustedProxies.of(Arrays.asList("not-an-ip", "10.0.0.0/99", "10.0.0.0/-1", "", "  "));

    assertThat(proxies.isEmpty()).isTrue();
    assertThat(proxies.contains("10.0.0.1")).isFalse();
  }

  @Test
  void shouldNeverResolveHostnames() {
    // A DNS lookup triggered from a forwarding header would be a request-driven side effect.
    assertThat(TrustedProxies.isIpLiteral("localhost")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("evil.example.com")).isFalse();
  }

  @Test
  void shouldValidateIpLiterals() {
    assertThat(TrustedProxies.isIpLiteral("127.0.0.1")).isTrue();
    assertThat(TrustedProxies.isIpLiteral("::1")).isTrue();
    assertThat(TrustedProxies.isIpLiteral("::ffff:10.0.0.1")).isTrue();
    assertThat(TrustedProxies.isIpLiteral(null)).isFalse();
    assertThat(TrustedProxies.isIpLiteral("")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("999.999.999.999")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("1".repeat(46))).isFalse();
  }

  @Test
  void shouldRejectHostnamesMadeOfHexCharactersWithoutResolvingThem() {
    // "cafe1.de" only uses hex digits and dots, which used to pass the character pre-screen and
    // reach InetAddress.getByName - a DNS lookup driven by a request header.
    assertThat(TrustedProxies.isIpLiteral("cafe1.de")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("dead.beef")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("abc")).isFalse();
    assertThat(TrustedProxies.of("cafe1.de").isEmpty()).isTrue();
  }

  @Test
  void shouldAcceptOnlyStrictDottedQuads() {
    assertThat(TrustedProxies.isIpLiteral("0.0.0.0")).isTrue();
    assertThat(TrustedProxies.isIpLiteral("255.255.255.255")).isTrue();
    assertThat(TrustedProxies.isIpLiteral("10.1.2.3")).isTrue();
    // shorthand, hex, octal and other forms InetAddress (or inet_aton) would accept
    assertThat(TrustedProxies.isIpLiteral("1.2.3")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("1.2")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("16909060")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("0x7f.1")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("0x7f.0.0.1")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("010.0.0.1")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("1.2.3.4.5")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("1.2.3.")).isFalse();
    assertThat(TrustedProxies.isIpLiteral(".1.2.3")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("1..2.3")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("256.0.0.1")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("1.2.3.4%eth0")).isFalse();
  }

  @Test
  void shouldAcceptIpv6InAllItsTextualForms() {
    assertThat(TrustedProxies.isIpLiteral("2001:db8:0:0:0:0:0:1")).isTrue();
    assertThat(TrustedProxies.isIpLiteral("2001:db8::1")).isTrue();
    assertThat(TrustedProxies.isIpLiteral("::")).isTrue();
    assertThat(TrustedProxies.isIpLiteral("1::")).isTrue();
    assertThat(TrustedProxies.isIpLiteral("FE80::ABCD")).isTrue();
    assertThat(TrustedProxies.isIpLiteral("fe80::1%eth0")).isTrue();
    assertThat(TrustedProxies.isIpLiteral("fe80::1%25")).isTrue();
    assertThat(TrustedProxies.isIpLiteral("::ffff:192.0.2.1")).isTrue();
    assertThat(TrustedProxies.isIpLiteral("::192.0.2.1")).isTrue();
    assertThat(TrustedProxies.isIpLiteral("1:2:3:4:5:6:192.0.2.1")).isTrue();
  }

  @Test
  void shouldRejectMalformedIpv6() {
    assertThat(TrustedProxies.isIpLiteral("1:2:3:4:5:6:7")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("1:2:3:4:5:6:7:8:9")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("1::2::3")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("1:::2")).isFalse();
    assertThat(TrustedProxies.isIpLiteral(":1::")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("1::2:")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("12345::1")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("g::1")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("1:2:3:4:5:6:7:192.0.2.1")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("::192.0.2.1:1")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("::ffff:1.2.3")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("fe80::1%")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("fe80::1%eth 0")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("[::1]")).isFalse();
  }

  @Test
  void shouldAcceptAnEmbeddedDottedQuadOnlyAsTheLastGroups() {
    // before "::" the dotted quad would not end the address
    assertThat(TrustedProxies.isIpLiteral("1.2.3.4::")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("1.2.3.4::1")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("1:1.2.3.4::")).isFalse();
    assertThat(TrustedProxies.isIpLiteral("1:2:3:4:5:1.2.3.4::")).isFalse();
    assertThat(TrustedProxies.of("1.2.3.4::/96").isEmpty()).isTrue();
    // after "::", or without one, it is the IPv4-embedded form
    assertThat(TrustedProxies.isIpLiteral("1::1.2.3.4")).isTrue();
    assertThat(TrustedProxies.isIpLiteral("1:2:3:4:5:6:1.2.3.4")).isTrue();
  }

  @Test
  void shouldMatchIpv4MappedAddressesAgainstIpv4Entries() {
    TrustedProxies proxies = TrustedProxies.of("10.0.0.0/8");

    assertThat(proxies.contains("::ffff:10.1.2.3")).isTrue();
    assertThat(proxies.contains("::ffff:11.1.2.3")).isFalse();
  }

  @Test
  void shouldMatchAZonedAddressByItsAddress() {
    assertThat(TrustedProxies.of("fe80::/10").contains("fe80::1%eth0")).isTrue();
  }
}
