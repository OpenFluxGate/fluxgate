package org.fluxgate.spring.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An immutable set of trusted reverse proxy addresses, expressed as plain IP literals or CIDR
 * blocks.
 *
 * <p>Forwarding headers such as {@code X-Forwarded-For} are only meaningful when the request
 * actually arrived through a proxy the application controls. This type answers the single question
 * the client IP extraction needs: is this address one of ours?
 *
 * <p>Both IPv4 and IPv6 entries are supported, for example {@code 10.0.0.0/8}, {@code 192.168.1.7}
 * or {@code 2001:db8::/32}. Entries that cannot be parsed are logged once at startup and ignored;
 * they never widen the trusted set.
 */
public final class TrustedProxies {

  private static final Logger log = LoggerFactory.getLogger(TrustedProxies.class);

  /** Maximum length of a textual IP address, matching the longest IPv4-mapped IPv6 form. */
  public static final int MAX_IP_LENGTH = 45;

  private static final TrustedProxies EMPTY = new TrustedProxies(Collections.emptyList());

  private final List<Entry> entries;

  private TrustedProxies(List<Entry> entries) {
    this.entries = entries;
  }

  /**
   * Returns the empty set, which trusts no proxy at all.
   *
   * @return an empty trusted proxy set
   */
  public static TrustedProxies none() {
    return EMPTY;
  }

  /**
   * Parses a collection of IP literals and CIDR blocks.
   *
   * @param definitions the entries to parse, may be null or empty
   * @return the parsed trusted proxy set, never null
   */
  public static TrustedProxies of(Collection<String> definitions) {
    if (definitions == null || definitions.isEmpty()) {
      return EMPTY;
    }
    List<Entry> parsed = new ArrayList<>(definitions.size());
    for (String definition : definitions) {
      if (definition == null || definition.trim().isEmpty()) {
        continue;
      }
      Entry entry = Entry.parse(definition.trim());
      if (entry == null) {
        log.warn("Ignoring unparseable fluxgate.ratelimit.trusted-proxies entry: {}", definition);
        continue;
      }
      parsed.add(entry);
    }
    return parsed.isEmpty() ? EMPTY : new TrustedProxies(Collections.unmodifiableList(parsed));
  }

  /**
   * Whether no proxy is trusted.
   *
   * @return true when the set is empty
   */
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /**
   * Whether the given address belongs to a trusted proxy.
   *
   * @param ip the textual IP address to test, may be null
   * @return true when the address matches one of the configured entries
   */
  public boolean contains(String ip) {
    if (entries.isEmpty()) {
      return false;
    }
    byte[] address = toAddress(ip);
    if (address == null) {
      return false;
    }
    for (Entry entry : entries) {
      if (entry.matches(address)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the given value is a valid IPv4 or IPv6 literal of acceptable length.
   *
   * <p>Only literals are accepted, and they are recognised by a pure parser: nothing is ever handed
   * to {@link java.net.InetAddress#getByName(String)}, so a forwarding header can never trigger a
   * name resolution. IPv4 must be a strict dotted quad - exactly four decimal octets from 0 to 255,
   * without leading zeros - so the shorthand ({@code 1.2.3}), hexadecimal ({@code 0x7f.1}) and
   * octal forms that {@code inet_aton} style parsers expand are rejected. IPv6 accepts the full,
   * compressed ({@code ::}) and embedded IPv4 ({@code ::ffff:192.0.2.1}) forms with an optional
   * zone id ({@code fe80::1%eth0}); brackets and ports are not part of a literal.
   *
   * @param value the value to test, may be null
   * @return true when the value is a usable IP literal
   */
  public static boolean isIpLiteral(String value) {
    return toAddress(value) != null;
  }

  /**
   * Parses an IP literal into its address bytes without any name resolution.
   *
   * <p>An IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}) yields the four IPv4 bytes, as {@link
   * java.net.InetAddress} does, so it matches IPv4 entries. A zone id is validated and dropped.
   *
   * @param value the literal, may be null
   * @return 4 or 16 address bytes, or null when the value is not a literal of acceptable length
   */
  static byte[] toAddress(String value) {
    if (value == null || value.isEmpty() || value.length() > MAX_IP_LENGTH) {
      return null;
    }
    if (value.indexOf(':') < 0) {
      return parseIpv4(value);
    }
    String address = value;
    int zone = value.indexOf('%');
    if (zone >= 0) {
      if (!isZoneId(value.substring(zone + 1))) {
        return null;
      }
      address = value.substring(0, zone);
    }
    byte[] ipv6 = parseIpv6(address);
    if (ipv6 == null) {
      return null;
    }
    return isIpv4Mapped(ipv6) ? Arrays.copyOfRange(ipv6, 12, 16) : ipv6;
  }

  /** A strict dotted quad: four decimal octets 0-255, no leading zeros, no other characters. */
  private static byte[] parseIpv4(String value) {
    byte[] address = new byte[4];
    int octet = 0;
    int start = 0;
    for (int i = 0; i <= value.length(); i++) {
      if (i < value.length() && value.charAt(i) != '.') {
        continue;
      }
      if (octet == 4) {
        return null;
      }
      int parsed = parseOctet(value, start, i);
      if (parsed < 0) {
        return null;
      }
      address[octet++] = (byte) parsed;
      start = i + 1;
    }
    return octet == 4 ? address : null;
  }

  /** The decimal octet in {@code value[from, to)}, or -1 when it is not one. */
  private static int parseOctet(String value, int from, int to) {
    int length = to - from;
    if (length < 1 || length > 3 || (length > 1 && value.charAt(from) == '0')) {
      return -1;
    }
    int result = 0;
    for (int i = from; i < to; i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') {
        return -1;
      }
      result = result * 10 + (c - '0');
    }
    return result <= 255 ? result : -1;
  }

  /**
   * An IPv6 address in full or {@code ::} compressed form, optionally ending in an embedded dotted
   * quad. Returns the 16 address bytes, or null.
   */
  private static byte[] parseIpv6(String value) {
    int compression = value.indexOf("::");
    if (compression >= 0 && value.indexOf("::", compression + 1) >= 0) {
      return null; // at most one "::", and ":::" is not one
    }
    int[] head = new int[8];
    int[] tail = new int[8];
    int headCount;
    int tailCount = 0;
    if (compression < 0) {
      headCount = parseGroups(value, head, true);
      if (headCount != 8) {
        return null;
      }
    } else {
      // a dotted quad must end the address, so it can only be the last token of the tail
      headCount = parseGroups(value.substring(0, compression), head, false);
      tailCount = parseGroups(value.substring(compression + 2), tail, true);
      // "::" stands for at least one group of zeros
      if (headCount < 0 || tailCount < 0 || headCount + tailCount > 7) {
        return null;
      }
    }
    byte[] address = new byte[16];
    for (int i = 0; i < headCount; i++) {
      address[2 * i] = (byte) (head[i] >>> 8);
      address[2 * i + 1] = (byte) head[i];
    }
    for (int i = 0; i < tailCount; i++) {
      int group = 8 - tailCount + i;
      address[2 * group] = (byte) (tail[i] >>> 8);
      address[2 * group + 1] = (byte) tail[i];
    }
    return address;
  }

  /**
   * Parses colon separated 16 bit groups into {@code groups}. When {@code ipv4Allowed}, the last
   * token may be a dotted quad, which fills two groups; the part before a {@code ::} passes false,
   * because a dotted quad there would not end the address. Returns the number of groups, 0 for an
   * empty part, or -1 when the part is malformed (an empty token, a group of more than four hex
   * digits, a misplaced dotted quad, too many groups).
   */
  private static int parseGroups(String part, int[] groups, boolean ipv4Allowed) {
    if (part.isEmpty()) {
      return 0;
    }
    int count = 0;
    int start = 0;
    for (int i = 0; i <= part.length(); i++) {
      if (i < part.length() && part.charAt(i) != ':') {
        continue;
      }
      String token = part.substring(start, i);
      if (token.indexOf('.') >= 0) {
        byte[] ipv4 = ipv4Allowed && i == part.length() ? parseIpv4(token) : null;
        if (ipv4 == null || count > 6) {
          return -1;
        }
        groups[count++] = ((ipv4[0] & 0xFF) << 8) | (ipv4[1] & 0xFF);
        groups[count++] = ((ipv4[2] & 0xFF) << 8) | (ipv4[3] & 0xFF);
      } else {
        int group = parseHexGroup(token);
        if (group < 0 || count > 7) {
          return -1;
        }
        groups[count++] = group;
      }
      start = i + 1;
    }
    return count;
  }

  /** One to four hex digits, or -1. */
  private static int parseHexGroup(String token) {
    if (token.isEmpty() || token.length() > 4) {
      return -1;
    }
    int result = 0;
    for (int i = 0; i < token.length(); i++) {
      int digit = Character.digit(token.charAt(i), 16);
      if (digit < 0) {
        return -1;
      }
      result = (result << 4) | digit;
    }
    return result;
  }

  /** A non-empty zone id made of ASCII letters, digits and {@code . _ -}. */
  private static boolean isZoneId(String zone) {
    if (zone.isEmpty()) {
      return false;
    }
    for (int i = 0; i < zone.length(); i++) {
      char c = zone.charAt(i);
      boolean allowed =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '.'
              || c == '_'
              || c == '-';
      if (!allowed) {
        return false;
      }
    }
    return true;
  }

  /** Whether the 16 bytes are {@code ::ffff:a.b.c.d}. */
  private static boolean isIpv4Mapped(byte[] address) {
    for (int i = 0; i < 10; i++) {
      if (address[i] != 0) {
        return false;
      }
    }
    return address[10] == (byte) 0xFF && address[11] == (byte) 0xFF;
  }

  /** A single trusted proxy entry: an address plus the number of significant leading bits. */
  private static final class Entry {

    private final byte[] address;
    private final int prefixBits;

    private Entry(byte[] address, int prefixBits) {
      this.address = address;
      this.prefixBits = prefixBits;
    }

    static Entry parse(String definition) {
      int slash = definition.indexOf('/');
      String host = slash < 0 ? definition : definition.substring(0, slash);
      byte[] address = toAddress(host);
      if (address == null) {
        return null;
      }
      int maxBits = address.length * 8;
      if (slash < 0) {
        return new Entry(address, maxBits);
      }
      int prefixBits;
      try {
        prefixBits = Integer.parseInt(definition.substring(slash + 1).trim());
      } catch (NumberFormatException e) {
        return null;
      }
      if (prefixBits < 0 || prefixBits > maxBits) {
        return null;
      }
      return new Entry(address, prefixBits);
    }

    boolean matches(byte[] candidate) {
      if (candidate.length != address.length) {
        return false;
      }
      int fullBytes = prefixBits / 8;
      for (int i = 0; i < fullBytes; i++) {
        if (candidate[i] != address[i]) {
          return false;
        }
      }
      int remainingBits = prefixBits % 8;
      if (remainingBits == 0) {
        return true;
      }
      int mask = 0xFF << (8 - remainingBits);
      return (candidate[fullBytes] & mask) == (address[fullBytes] & mask);
    }
  }

  @Override
  public String toString() {
    return "TrustedProxies{entries=" + entries.size() + '}';
  }

  /**
   * Convenience factory for varargs use in tests and programmatic configuration.
   *
   * @param definitions the entries to parse
   * @return the parsed trusted proxy set, never null
   */
  public static TrustedProxies of(String... definitions) {
    return definitions == null ? EMPTY : of(Arrays.asList(definitions));
  }
}
