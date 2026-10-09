package org.fluxgate.spring.util;

import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.WebUtils;

/**
 * Resolves the path that include/exclude patterns are matched against.
 *
 * <p>{@code request.getRequestURI()} is the raw, still encoded request line and must never be
 * matched directly: {@code /api/login;jsessionid=1}, {@code /api%2Flogin}, {@code //api/login} and
 * {@code /api/login/} all reach the same Spring MVC handler as {@code /api/login} but none of them
 * match the pattern {@code /api/login}. A context path also makes every {@code /api/**} pattern
 * miss silently. This resolver removes those discrepancies:
 *
 * <ul>
 *   <li>matrix parameters ({@code ;jsessionid=...}) are stripped
 *   <li>the path is URL decoded, so an encoded slash becomes a real segment separator
 *   <li>the servlet context path is removed, so patterns are context-path independent
 *   <li>repeated slashes are collapsed and a trailing slash is dropped (except for the root)
 *   <li>{@code .} and {@code ..} segments are removed, so {@code /api/%2e%2e/login} is matched as
 *       the {@code /login} the container routes it to
 * </ul>
 *
 * <p>Normalization is deliberately fail closed. A path that cannot be decoded at all resolves to
 * {@link #UNRESOLVABLE_PATH}, which the filter treats as included and never excluded: silently
 * skipping the rate limiter for a request nobody can normalize is the worse outcome.
 *
 * <p>Container mapping and library normalization can never be made identical in principle, so a
 * reverse proxy should still reject requests containing {@code %2e}, {@code %2f} or {@code %5c}.
 */
public final class RequestPathResolver {

  private static final Logger log = LoggerFactory.getLogger(RequestPathResolver.class);

  /**
   * Path substituted for a request whose URI cannot be decoded.
   *
   * <p>It is not a legal request target, so it matches no operator pattern by accident, and the
   * filter special cases it to "rate limit this".
   */
  public static final String UNRESOLVABLE_PATH = "/__fluxgate_unresolvable__";

  /** Minimum gap between two warnings about unresolvable paths, in nanoseconds. */
  private static final long WARN_INTERVAL_NANOS = 60_000_000_000L;

  private static final UrlPathHelper PATH_HELPER = createPathHelper();

  /**
   * Timestamp of the last unresolvable path warning.
   *
   * <p>Anyone can send a malformed escape sequence, so the warning is rate limited to one per
   * {@link #WARN_INTERVAL_NANOS} and the per-request detail goes to DEBUG. A log line per request
   * would otherwise be a denial of service against the log pipeline.
   */
  private static final AtomicLong lastWarnNanos = new AtomicLong(Long.MIN_VALUE);

  private RequestPathResolver() {
    // Utility class
  }

  /**
   * Resolves the normalized, context-path independent lookup path for a request.
   *
   * @param request the HTTP request
   * @return the normalized path, never null and always starting with {@code /}
   */
  public static String resolve(HttpServletRequest request) {
    if (request == null || request.getRequestURI() == null) {
      return "/";
    }
    try {
      return normalize(PATH_HELPER.getPathWithinApplication(request));
    } catch (RuntimeException e) {
      // N-2: an undecodable path cannot be normalized. Falling back to the raw URI would prepend
      // the context path, miss every include pattern and switch rate limiting off silently, so
      // report a path that is treated as "limit this" instead.
      warnAboutUnresolvablePath(e);
      return UNRESOLVABLE_PATH;
    }
  }

  /**
   * Normalizes an already decoded path: collapses repeated slashes, removes dot segments and drops
   * a trailing slash.
   *
   * @param path the path to normalize, may be null
   * @return the normalized path, never null and always starting with {@code /}
   */
  public static String normalize(String path) {
    if (path == null || path.isEmpty()) {
      return "/";
    }
    String normalized = path;
    if (normalized.indexOf("//") >= 0) {
      normalized = normalized.replaceAll("/{2,}", "/");
    }
    if (normalized.charAt(0) != '/') {
      normalized = "/" + normalized;
    }
    // N-1: remove "." and ".." segments. Without this, /api/%2e%2e/login is seen here as
    // /api/../login while the container routes it to /login, so it misses the include pattern
    // /login and matches the exclude pattern /actuator/** - a rate limit bypass either way.
    normalized = StringUtils.cleanPath(normalized);
    if (normalized.length() > 1 && normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    // cleanPath keeps leading ".." segments that escape the root. Such a path has no normal form,
    // so fold it to the root and let it be rate limited (fail closed).
    if (normalized.startsWith("/..")) {
      return "/";
    }
    return normalized;
  }

  /** Warns at most once per interval; the per-request detail stays at DEBUG. */
  private static void warnAboutUnresolvablePath(RuntimeException cause) {
    log.debug("Unresolvable request path, rate limiting it as an unmatched path", cause);
    long now = System.nanoTime();
    long last = lastWarnNanos.get();
    if (now - last >= WARN_INTERVAL_NANOS && lastWarnNanos.compareAndSet(last, now)) {
      log.warn(
          "Request path could not be decoded ({}); such requests are rate limited as unmatched"
              + " paths. Enable DEBUG on {} for per-request detail.",
          cause.getClass().getSimpleName(),
          RequestPathResolver.class.getName());
    }
  }

  private static UrlPathHelper createPathHelper() {
    UrlPathHelper helper =
        new UrlPathHelper() {
          @Override
          public String getContextPath(HttpServletRequest request) {
            // Minimal or mocked requests may report a null context path; treat it as the root
            // instead of failing while decoding null.
            if (request.getAttribute(WebUtils.INCLUDE_CONTEXT_PATH_ATTRIBUTE) == null
                && request.getContextPath() == null) {
              return "";
            }
            return super.getContextPath(request);
          }
        };
    helper.setUrlDecode(true);
    helper.setRemoveSemicolonContent(true);
    helper.setAlwaysUseFullPath(false);
    return helper;
  }
}
