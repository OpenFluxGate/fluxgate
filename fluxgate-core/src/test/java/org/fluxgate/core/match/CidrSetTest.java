package org.fluxgate.core.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CidrSet Tests")
class CidrSetTest {

  // ===== IPv4 =====

  @Nested
  @DisplayName("IPv4")
  class IPv4Tests {

    @Test
    @DisplayName("single IPv4 literal matches exactly")
    void singleLiteral() {
      CidrSet set = CidrSet.of(Collections.singletonList("192.168.1.1"));
      assertThat(set.contains("192.168.1.1")).isTrue();
      assertThat(set.contains("192.168.1.2")).isFalse();
    }

    @Test
    @DisplayName("/24 CIDR matches hosts in range")
    void slash24() {
      CidrSet set = CidrSet.of(Collections.singletonList("10.0.0.0/24"));
      assertThat(set.contains("10.0.0.1")).isTrue();
      assertThat(set.contains("10.0.0.255")).isTrue();
      assertThat(set.contains("10.0.1.0")).isFalse();
    }

    @Test
    @DisplayName("/8 CIDR matches class-A block")
    void slash8() {
      CidrSet set = CidrSet.of(Collections.singletonList("10.0.0.0/8"));
      assertThat(set.contains("10.255.255.255")).isTrue();
      assertThat(set.contains("11.0.0.0")).isFalse();
    }

    @Test
    @DisplayName("/32 CIDR matches only that host")
    void slash32() {
      CidrSet set = CidrSet.of(Collections.singletonList("192.168.1.5/32"));
      assertThat(set.contains("192.168.1.5")).isTrue();
      assertThat(set.contains("192.168.1.6")).isFalse();
    }

    @Test
    @DisplayName("/0 CIDR matches everything")
    void slash0() {
      CidrSet set = CidrSet.of(Collections.singletonList("0.0.0.0/0"));
      assertThat(set.contains("1.2.3.4")).isTrue();
      assertThat(set.contains("255.255.255.255")).isTrue();
    }

    @Test
    @DisplayName("non-byte-aligned prefix /17 works correctly")
    void nonByteAligned() {
      CidrSet set = CidrSet.of(Collections.singletonList("192.168.0.0/17"));
      assertThat(set.contains("192.168.0.1")).isTrue();
      assertThat(set.contains("192.168.127.255")).isTrue();
      assertThat(set.contains("192.168.128.0")).isFalse();
    }
  }

  // ===== IPv6 =====

  @Nested
  @DisplayName("IPv6")
  class IPv6Tests {

    @Test
    @DisplayName("loopback ::1 matches exactly")
    void loopback() {
      CidrSet set = CidrSet.of(Collections.singletonList("::1"));
      assertThat(set.contains("::1")).isTrue();
      assertThat(set.contains("::2")).isFalse();
    }

    @Test
    @DisplayName("/128 matches single host")
    void slash128() {
      CidrSet set = CidrSet.of(Collections.singletonList("2001:db8::1/128"));
      assertThat(set.contains("2001:db8::1")).isTrue();
      assertThat(set.contains("2001:db8::2")).isFalse();
    }

    @Test
    @DisplayName("/32 matches prefix block")
    void slash32v6() {
      CidrSet set = CidrSet.of(Collections.singletonList("2001:db8::/32"));
      assertThat(set.contains("2001:db8::1")).isTrue();
      assertThat(set.contains("2001:db8:ffff::1")).isTrue();
      assertThat(set.contains("2001:db9::1")).isFalse();
    }
  }

  // ===== IPv4-mapped IPv6 =====

  @Nested
  @DisplayName("IPv4-mapped IPv6")
  class IPv4MappedTests {

    @Test
    @DisplayName("IPv4-mapped IPv6 address matches IPv4 CIDR")
    void mappedMatchesV4Cidr() {
      CidrSet set = CidrSet.of(Collections.singletonList("192.168.1.0/24"));
      // ::ffff:192.168.1.5 is the IPv4-mapped form
      assertThat(set.contains("::ffff:192.168.1.5")).isTrue();
      assertThat(set.contains("::ffff:192.168.2.5")).isFalse();
    }
  }

  // ===== garbage entries =====

  @Nested
  @DisplayName("Garbage entries")
  class GarbageTests {

    @Test
    @DisplayName("unparseable entries are silently skipped")
    void invalidEntriesSkipped() {
      CidrSet set =
          CidrSet.of(Arrays.asList("not-an-ip", "192.168.1.0/33", null, "  ", "192.168.1.1"));
      // valid entry still works
      assertThat(set.contains("192.168.1.1")).isTrue();
      // garbage never widens the set
      assertThat(set.contains("0.0.0.0")).isFalse();
    }

    @Test
    @DisplayName("empty collection returns empty set")
    void emptyCollection() {
      CidrSet set = CidrSet.of(Collections.emptyList());
      assertThat(set.isEmpty()).isTrue();
      assertThat(set.contains("1.2.3.4")).isFalse();
    }

    @Test
    @DisplayName("null IP argument returns false")
    void nullIpReturnsFalse() {
      CidrSet set = CidrSet.of(Collections.singletonList("10.0.0.0/8"));
      assertThat(set.contains(null)).isFalse();
    }

    @Test
    @DisplayName("garbage IP string returns false")
    void garbageIpReturnsFalse() {
      CidrSet set = CidrSet.of(Collections.singletonList("10.0.0.0/8"));
      assertThat(set.contains("not-an-ip")).isFalse();
    }
  }

  // ===== EMPTY constant =====

  @Test
  @DisplayName("EMPTY constant contains nothing")
  void emptyConstant() {
    assertThat(CidrSet.EMPTY.isEmpty()).isTrue();
    assertThat(CidrSet.EMPTY.contains("1.2.3.4")).isFalse();
  }

  // ===== IP literals only (no DNS) =====

  @Nested
  @DisplayName("non-literal input is never resolved")
  class LiteralOnlyTests {

    private final CidrSet loopback = CidrSet.of(Arrays.asList("127.0.0.0/8", "::1"));

    @Test
    @DisplayName("hostnames are not resolved and never match")
    void hostnameNotResolved() {
      // "localhost" resolves to 127.0.0.1 / ::1 via the resolver; it must not match
      assertThat(loopback.contains("localhost")).isFalse();
      assertThat(loopback.contains("example.com")).isFalse();
    }

    @Test
    @DisplayName("placeholder values such as \"unknown\" never match")
    void unknownNeverMatches() {
      assertThat(loopback.contains("unknown")).isFalse();
      assertThat(CidrSet.of(Collections.singletonList("0.0.0.0/0")).contains("unknown")).isFalse();
    }

    @Test
    @DisplayName("abbreviated IPv4 forms are not treated as literals")
    void abbreviatedIpv4Rejected() {
      assertThat(loopback.contains("127.1")).isFalse();
      assertThat(loopback.contains("127.0.0.1.5")).isFalse();
      assertThat(loopback.contains("127.0.0.256")).isFalse();
    }

    @Test
    @DisplayName("IPv4 and IPv6 literals still match")
    void literalsMatch() {
      assertThat(loopback.contains("127.0.0.1")).isTrue();
      assertThat(loopback.contains(" 127.0.0.1 ")).isTrue();
      assertThat(loopback.contains("::1")).isTrue();
      assertThat(loopback.contains("0:0:0:0:0:0:0:1")).isTrue();
      assertThat(loopback.contains("::ffff:127.0.0.1")).isTrue();
      assertThat(loopback.contains("2001:db8::1")).isFalse();
    }

    @Test
    @DisplayName("hostname entries are skipped, not resolved")
    void hostnameEntrySkipped() {
      CidrSet set = CidrSet.of(Collections.singletonList("localhost"));
      assertThat(set.isEmpty()).isTrue();
      assertThat(set.contains("127.0.0.1")).isFalse();
    }
  }

  @Nested
  @DisplayName("Value semantics")
  class ValueSemanticsTests {

    @Test
    @DisplayName("sets with the same networks are equal regardless of order and host bits")
    void equalByNetworks() {
      CidrSet a = CidrSet.of(Arrays.asList("10.0.0.0/8", "192.168.1.1"));
      CidrSet b = CidrSet.of(Arrays.asList(" 192.168.1.1 ", "10.1.2.3/8"));

      assertThat(a).isEqualTo(b);
      assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("sets with different networks are not equal")
    void differentNetworksDiffer() {
      assertThat(CidrSet.of(Collections.singletonList("10.0.0.0/8")))
          .isNotEqualTo(CidrSet.of(Collections.singletonList("10.0.0.0/16")));
      assertThat(CidrSet.of(Collections.singletonList("10.0.0.0/8"))).isNotEqualTo(CidrSet.EMPTY);
    }

    @Test
    @DisplayName("toString lists the configured entries")
    void toStringListsEntries() {
      assertThat(CidrSet.of(Arrays.asList("10.0.0.0/8", "::1")).toString())
          .contains("10.0.0.0/8")
          .contains("::1");
    }
  }
}
