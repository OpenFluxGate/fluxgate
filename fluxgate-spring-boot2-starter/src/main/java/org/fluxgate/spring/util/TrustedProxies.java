package org.fluxgate.spring.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
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
    if (entries.isEmpty() || !isIpLiteral(ip)) {
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
   * <p>Only literals are accepted: a value that would require a DNS lookup is rejected, so an
   * attacker cannot turn a forwarding header into a name resolution.
   *
   * @param value the value to test, may be null
   * @return true when the value is a usable IP literal
   */
  public static boolean isIpLiteral(String value) {
    if (value == null || value.isEmpty() || value.length() > MAX_IP_LENGTH) {
      return false;
    }
    return toAddress(value) != null;
  }

  private static byte[] toAddress(String value) {
    // InetAddress.getByName performs a DNS lookup for non-literals, so pre-screen the characters.
    if (!looksLikeLiteral(value)) {
      return null;
    }
    try {
      return InetAddress.getByName(stripZone(value)).getAddress();
    } catch (UnknownHostException e) {
      return null;
    }
  }

  private static boolean looksLikeLiteral(String value) {
    boolean hasDigitOrColon = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
      if (!hex && c != '.' && c != ':' && c != '%') {
        return false;
      }
      if (c == ':' || (c >= '0' && c <= '9')) {
        hasDigitOrColon = true;
      }
    }
    return hasDigitOrColon;
  }

  private static String stripZone(String value) {
    int zone = value.indexOf('%');
    return zone < 0 ? value : value.substring(0, zone);
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
