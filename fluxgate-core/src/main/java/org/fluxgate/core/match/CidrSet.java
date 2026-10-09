package org.fluxgate.core.match;

import java.math.BigInteger;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable set of IPv4 and IPv6 addresses and CIDR blocks.
 *
 * <p>Accepts single addresses (e.g. {@code "192.168.1.1"}, {@code "::1"}) and CIDR notation (e.g.
 * {@code "10.0.0.0/8"}, {@code "2001:db8::/32"}). Entries that cannot be parsed are silently
 * skipped with a {@code WARN} log — they never widen the set.
 *
 * <p>Only IP <em>literals</em> are accepted, both as entries and in {@link #contains(String)}:
 * hostnames and placeholders such as {@code "unknown"} are never resolved through DNS and never
 * match.
 *
 * <p>IPv4-mapped IPv6 addresses (e.g. {@code "::ffff:192.168.1.1"}) are normalised to their IPv4
 * equivalents before matching, so an IPv4 CIDR entry will match the mapped form.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * CidrSet trusted = CidrSet.of(List.of("10.0.0.0/8", "192.168.1.1"));
 * trusted.contains("10.0.0.5");   // true
 * trusted.contains("172.16.0.1"); // false
 * }</pre>
 *
 * @since 0.4.0
 */
public final class CidrSet {

  private static final Logger log = LoggerFactory.getLogger(CidrSet.class);

  /** Strict dotted-quad IPv4 literal (no abbreviated or octal-looking forms). */
  private static final Pattern IPV4_LITERAL =
      Pattern.compile(
          "^(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

  /** Characters of an IPv6 literal (hex groups, colons, optional embedded IPv4). */
  private static final Pattern IPV6_LITERAL = Pattern.compile("^[0-9A-Fa-f:.]+$");

  /** An empty set that never matches any address. */
  public static final CidrSet EMPTY = new CidrSet(Collections.emptyList());

  private final List<Entry> entries;

  private CidrSet(List<Entry> entries) {
    this.entries = entries;
  }

  /**
   * Creates a new {@link CidrSet} from the given collection of CIDR strings or plain IP addresses.
   *
   * <p>Unparseable entries are logged at {@code WARN} level and skipped; they never widen the set.
   *
   * @param cidrs the addresses/CIDRs to include (must not be {@code null})
   * @return the immutable set
   */
  public static CidrSet of(Collection<String> cidrs) {
    Objects.requireNonNull(cidrs, "cidrs must not be null");
    List<Entry> entries = new ArrayList<>();
    for (String cidr : cidrs) {
      if (cidr == null || cidr.trim().isEmpty()) {
        continue;
      }
      try {
        entries.add(Entry.parse(cidr.trim()));
      } catch (Exception e) {
        log.warn("CidrSet: unparseable entry '{}', skipping: {}", cidr, e.getMessage());
      }
    }
    return new CidrSet(Collections.unmodifiableList(entries));
  }

  /**
   * Returns {@code true} if the given IP address is contained within this set.
   *
   * <p>Both IPv4 and IPv6 addresses are accepted. IPv4-mapped IPv6 addresses are normalised to IPv4
   * before the check.
   *
   * @param ip the IP address string to test (may be null; {@code null} always returns {@code
   *     false})
   * @return {@code true} if the address is within any entry of this set
   */
  public boolean contains(String ip) {
    if (ip == null || ip.trim().isEmpty()) {
      return false;
    }
    InetAddress addr;
    try {
      addr = parseLiteral(ip.trim());
    } catch (UnknownHostException e) {
      return false;
    }
    addr = normalise(addr);
    for (Entry entry : entries) {
      if (entry.contains(addr)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Parses an IPv4 or IPv6 <em>literal</em> without ever consulting DNS. {@link
   * InetAddress#getByName} performs a lookup for anything that is not a literal (e.g. {@code
   * "unknown"} or a hostname), so non-literals are rejected before it is called.
   *
   * @throws UnknownHostException if {@code text} is not an IP literal
   */
  private static InetAddress parseLiteral(String text) throws UnknownHostException {
    boolean ipv4 = IPV4_LITERAL.matcher(text).matches();
    boolean ipv6 = !ipv4 && text.indexOf(':') >= 0 && IPV6_LITERAL.matcher(text).matches();
    if (!ipv4 && !ipv6) {
      throw new UnknownHostException("not an IP literal: '" + text + "'");
    }
    // a literal never triggers a name lookup
    return InetAddress.getByName(text);
  }

  /** Returns {@code true} if this set has no entries. */
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  // ===== normalisation =====

  /**
   * Converts an IPv4-mapped IPv6 address (::ffff:x.x.x.x) to its plain IPv4 form so that a single
   * IPv4 CIDR entry matches both representations.
   */
  private static InetAddress normalise(InetAddress addr) {
    if (!(addr instanceof Inet6Address)) {
      return addr;
    }
    byte[] raw = addr.getAddress();
    // IPv4-mapped prefix: 10 zero bytes + 2 0xff bytes + 4 IPv4 bytes
    if (isIpv4Mapped(raw)) {
      byte[] v4 = new byte[4];
      System.arraycopy(raw, 12, v4, 0, 4);
      try {
        return InetAddress.getByAddress(v4);
      } catch (UnknownHostException e) {
        return addr; // cannot happen for 4-byte array
      }
    }
    return addr;
  }

  private static boolean isIpv4Mapped(byte[] raw) {
    if (raw.length != 16) {
      return false;
    }
    for (int i = 0; i < 10; i++) {
      if (raw[i] != 0) return false;
    }
    return (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF;
  }

  // ===== inner Entry =====

  private static final class Entry {
    private final BigInteger networkAddress;
    private final BigInteger mask;
    private final int addressLength; // 4 or 16 bytes

    private Entry(BigInteger networkAddress, BigInteger mask, int addressLength) {
      this.networkAddress = networkAddress;
      this.mask = mask;
      this.addressLength = addressLength;
    }

    static Entry parse(String cidr) throws UnknownHostException {
      int slashIdx = cidr.indexOf('/');
      String hostPart;
      int prefixLen;
      if (slashIdx < 0) {
        // single address — exact match
        hostPart = cidr;
        InetAddress parsed = parseLiteral(hostPart);
        prefixLen = parsed.getAddress().length * 8;
      } else {
        hostPart = cidr.substring(0, slashIdx);
        try {
          prefixLen = Integer.parseInt(cidr.substring(slashIdx + 1));
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Invalid prefix length in '" + cidr + "'");
        }
      }
      InetAddress addr = parseLiteral(hostPart);
      byte[] raw = addr.getAddress();
      int maxPrefix = raw.length * 8;
      if (prefixLen < 0 || prefixLen > maxPrefix) {
        throw new IllegalArgumentException(
            "Prefix length " + prefixLen + " out of range for '" + cidr + "'");
      }
      BigInteger mask = buildMask(prefixLen, raw.length);
      BigInteger network = new BigInteger(1, raw).and(mask);
      return new Entry(network, mask, raw.length);
    }

    private static BigInteger buildMask(int prefixLen, int byteLen) {
      int totalBits = byteLen * 8;
      if (prefixLen == 0) {
        return BigInteger.ZERO;
      }
      // shift 1 left by (totalBits - prefixLen), subtract 1, then invert in the address space
      BigInteger allOnes = BigInteger.ONE.shiftLeft(totalBits).subtract(BigInteger.ONE);
      BigInteger hostBits =
          BigInteger.ONE.shiftLeft(totalBits - prefixLen).subtract(BigInteger.ONE);
      return allOnes.xor(hostBits);
    }

    boolean contains(InetAddress candidate) {
      byte[] raw = candidate.getAddress();
      if (raw.length != addressLength) {
        return false; // address family mismatch
      }
      BigInteger candidateBig = new BigInteger(1, raw);
      return candidateBig.and(mask).equals(networkAddress);
    }
  }
}
