package org.fluxgate.core.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dependency-free string helpers for Redis connection URIs, shared by every module that has to tell
 * a standalone URI from a cluster node list.
 *
 * <p>Both helpers are credential-aware: a comma inside the user info (for example {@code
 * redis://:pa,ss@host:6379}) is part of the password and never a node separator.
 *
 * @since 0.4.0
 */
public final class RedisUris {

  private static final String SCHEME_SEPARATOR = "://";

  private RedisUris() {
    // Utility class
  }

  /**
   * Tells whether a URI string lists several nodes.
   *
   * <p>A cluster is recognised either by more than one URI scheme or by a comma that sits outside
   * the credentials.
   *
   * @param uri single URI or comma-separated URIs, may be null
   * @return {@code true} if the string describes a cluster, {@code false} for a single node or a
   *     null/blank string
   */
  public static boolean isCluster(String uri) {
    if (uri == null || uri.trim().isEmpty()) {
      return false;
    }
    if (countSchemes(uri) > 1) {
      return true;
    }
    return uri.indexOf(',', authorityStart(uri)) >= 0;
  }

  /**
   * Splits a comma-separated list of node URIs.
   *
   * <p>When every node carries its own scheme the split happens only in front of a scheme, so a
   * comma inside a password does not tear a node in two. With a single scheme only commas after the
   * first node's credentials separate nodes, so {@code redis://:pa,ss@n1:6379,n2:6380} yields
   * {@code redis://:pa,ss@n1:6379} and {@code n2:6380}. Without any scheme the list is split on
   * plain commas.
   *
   * @param uri single URI or comma-separated URIs, may be null
   * @return the individual URIs, trimmed, without empty entries
   */
  public static List<String> splitNodes(String uri) {
    if (uri == null || uri.trim().isEmpty()) {
      return Collections.emptyList();
    }

    // Leading and trailing separators are dropped first so that the scheme-aware split below
    // does not leave a stray comma glued to the last node.
    String normalised = uri.trim().replaceAll("^,+|,+$", "");
    String[] parts;
    if (countSchemes(normalised) > 1) {
      parts = normalised.split(",(?=\\s*[A-Za-z][A-Za-z0-9+.\\-]*" + SCHEME_SEPARATOR + ")");
    } else {
      // a comma inside the credentials belongs to the password, not to the node list
      int authority = authorityStart(normalised);
      parts = normalised.substring(authority).split(",");
      parts[0] = normalised.substring(0, authority) + parts[0];
    }

    List<String> nodes = new ArrayList<>(parts.length);
    for (String part : parts) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        nodes.add(trimmed);
      }
    }
    return nodes;
  }

  private static int countSchemes(String uri) {
    int count = 0;
    int from = uri.indexOf(SCHEME_SEPARATOR);
    while (from >= 0) {
      count++;
      from = uri.indexOf(SCHEME_SEPARATOR, from + SCHEME_SEPARATOR.length());
    }
    return count;
  }

  /**
   * Index just after the credentials of the first node, which is where a node separator may appear.
   *
   * <p>Only the first node's authority is searched, which ends at the first {@code /} or {@code ?}
   * after the scheme, so an {@code @} in a path or query ({@code ?clientName=a@b}) is never taken
   * for the end of the credentials. Inside the authority the credentials end at the first
   * {@code @}, extended to a later {@code @} only while no comma lies between them: a host never
   * contains {@code @}, so {@code :p@ss@host} keeps {@code p@ss} as the password, while in {@code
   * :p@n1,u@n2} the comma separates the node {@code n1} from the node {@code u@n2}. A password that
   * contains an {@code @} followed by a comma is ambiguous and must be percent-encoded.
   */
  private static int authorityStart(String uri) {
    int scheme = uri.indexOf(SCHEME_SEPARATOR);
    int start = scheme >= 0 ? scheme + SCHEME_SEPARATOR.length() : 0;
    int end = start;
    while (end < uri.length() && uri.charAt(end) != '/' && uri.charAt(end) != '?') {
      end++;
    }
    int credentialsEnd = uri.indexOf('@', start);
    if (credentialsEnd < 0 || credentialsEnd >= end) {
      return start;
    }
    while (true) {
      int next = uri.indexOf('@', credentialsEnd + 1);
      if (next < 0 || next >= end) {
        break;
      }
      int comma = uri.indexOf(',', credentialsEnd + 1);
      if (comma >= 0 && comma < next) {
        break;
      }
      credentialsEnd = next;
    }
    return credentialsEnd + 1;
  }
}
