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
   * comma inside a password does not tear a node in two. A list whose nodes omit the scheme is
   * split on plain commas, which is the best that can be done with such input.
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
    String[] parts =
        countSchemes(normalised) > 1
            ? normalised.split(",(?=\\s*[A-Za-z][A-Za-z0-9+.\\-]*" + SCHEME_SEPARATOR + ")")
            : normalised.split(",");

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
   */
  private static int authorityStart(String uri) {
    int credentialsEnd = uri.lastIndexOf('@');
    if (credentialsEnd >= 0) {
      return credentialsEnd + 1;
    }
    int scheme = uri.indexOf(SCHEME_SEPARATOR);
    return scheme >= 0 ? scheme + SCHEME_SEPARATOR.length() : 0;
  }
}
