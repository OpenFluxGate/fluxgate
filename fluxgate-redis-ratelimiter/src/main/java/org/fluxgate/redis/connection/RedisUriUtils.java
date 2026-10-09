package org.fluxgate.redis.connection;

import io.lettuce.core.RedisURI;
import java.time.Duration;
import java.util.List;
import org.fluxgate.core.util.RedisUris;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;

/**
 * Helpers shared by everything in this module that has to look at a Redis URI.
 *
 * <p>Two things used to be copy-pasted across the module and both went wrong in their own way: the
 * five second default timeout (four separate literals) and "does this URI describe a cluster?" (a
 * plain {@code contains(",")}, which misreads a password containing a comma). Both live here now.
 */
public final class RedisUriUtils {

  /**
   * Default connection and command timeout used whenever the caller does not supply one.
   *
   * <p>Single source of truth for this module; the Spring starters expose it as a property.
   */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

  private RedisUriUtils() {
    // Utility class
  }

  /**
   * Detects whether a URI string describes a single node or a cluster; delegates to {@link
   * RedisUris#isCluster(String)}.
   *
   * <p>A cluster is recognised either by more than one URI scheme or by a comma that sits outside
   * the credentials. That second rule is what makes {@code redis://:pa,ss@host:6379} a standalone
   * URI rather than two malformed nodes.
   *
   * @param uri single URI or comma-separated URIs
   * @return {@link RedisMode#CLUSTER} if the URI lists several nodes, otherwise {@link
   *     RedisMode#STANDALONE}
   */
  public static RedisMode detectMode(String uri) {
    return RedisUris.isCluster(uri) ? RedisMode.CLUSTER : RedisMode.STANDALONE;
  }

  /**
   * Splits a comma-separated list of node URIs.
   *
   * <p>When every node carries its own scheme the split happens only in front of a scheme, so a
   * comma inside a password does not tear a node in two. A list whose nodes omit the scheme is
   * split on plain commas, which is the best that can be done with such input.
   *
   * @param uri single URI or comma-separated URIs
   * @return the individual URIs, trimmed, without empty entries
   */
  public static List<String> splitNodes(String uri) {
    return RedisUris.splitNodes(uri);
  }

  /**
   * Renders a URI for logging with everything but the endpoint removed.
   *
   * <p>The URI is parsed with Lettuce rather than pattern-matched, so a password supplied as {@code
   * redis://:secret@host}, as {@code redis://user:secret@host} or as a {@code ?password=} query
   * parameter is dropped either way - only {@code host:port} and a non-default database survive. A
   * URI that cannot be parsed is replaced entirely instead of being echoed.
   *
   * @param uri single URI or comma-separated URIs, may be null
   * @return a log-safe rendering such as {@code localhost:6379/1}
   */
  public static String mask(String uri) {
    if (uri == null) {
      return "null";
    }

    List<String> nodes = splitNodes(uri);
    if (nodes.isEmpty()) {
      return "<empty redis uri>";
    }

    StringBuilder masked = new StringBuilder();
    for (String node : nodes) {
      if (masked.length() > 0) {
        masked.append(',');
      }
      masked.append(maskNode(node));
    }
    return masked.toString();
  }

  private static String maskNode(String node) {
    try {
      RedisURI parsed = RedisURI.create(node);
      String endpoint =
          parsed.getSocket() != null
              ? "unix:" + parsed.getSocket()
              : parsed.getHost() + ":" + parsed.getPort();
      return parsed.getDatabase() != 0 ? endpoint + "/" + parsed.getDatabase() : endpoint;
    } catch (RuntimeException e) {
      return "<unparseable redis uri>";
    }
  }
}
