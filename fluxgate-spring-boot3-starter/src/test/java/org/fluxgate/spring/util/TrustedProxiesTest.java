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
}
