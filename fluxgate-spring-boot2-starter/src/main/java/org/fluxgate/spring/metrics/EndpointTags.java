package org.fluxgate.spring.metrics;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The {@code endpoint} tag policy shared by {@link MicrometerMetricsRecorder} and {@link
 * FluxgateMetrics}: optional, normalized ({@code {id}} for ids) and bounded.
 *
 * <p>One instance holds one budget of distinct values. Meters that share an instance share the
 * budget, so an invented path costs one series in total and every value past the cap is folded into
 * {@code other} instead of being dropped.
 */
final class EndpointTags {

  /** Placeholder substituted for high cardinality path segments. */
  static final String ID_PLACEHOLDER = "{id}";

  /** Endpoint tag value every endpoint past the cardinality cap collapses into. */
  static final String OTHER_ENDPOINT = "other";

  private static final Pattern NUMERIC_SEGMENT = Pattern.compile("\\d+");
  private static final Pattern UUID_SEGMENT =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final Pattern HEX24_SEGMENT = Pattern.compile("[0-9a-fA-F]{24}");
  private static final Pattern UNSAFE_TAG_CHARS = Pattern.compile("[^a-zA-Z0-9_/.{}-]");

  /** Upper bound on a tag value, so a long URI cannot blow up the registry's memory. */
  private static final int MAX_TAG_LENGTH = 200;

  private final boolean includeEndpoint;
  private final boolean normalizeEndpoints;
  private final int maxEndpointTags;

  /** Endpoint tag values already handed to the registry. Only grows, and only under the lock. */
  private final Set<String> knownEndpoints = ConcurrentHashMap.newKeySet();

  /** Makes "check the budget, then admit a new value" one step. */
  private final Object admissionLock = new Object();

  /**
   * Runs after the unlocked budget check and before the admission lock is taken. A no-op outside
   * tests, which use it to interleave a competing admission deterministically.
   */
  private final Runnable beforeAdmission;

  /**
   * Creates the policy.
   *
   * @param includeEndpoint whether meters carry the endpoint tag at all
   * @param normalizeEndpoints replace numeric, UUID and 24 char hex path segments with {@code {id}}
   * @param maxEndpointTags upper bound on distinct values; 0 or negative means unbounded
   */
  EndpointTags(boolean includeEndpoint, boolean normalizeEndpoints, int maxEndpointTags) {
    this(includeEndpoint, normalizeEndpoints, maxEndpointTags, () -> {});
  }

  /** Test seam: {@code beforeAdmission} runs on the admission path only, never for known values. */
  EndpointTags(
      boolean includeEndpoint,
      boolean normalizeEndpoints,
      int maxEndpointTags,
      Runnable beforeAdmission) {
    this.includeEndpoint = includeEndpoint;
    this.normalizeEndpoints = normalizeEndpoints;
    this.maxEndpointTags = maxEndpointTags > 0 ? maxEndpointTags : Integer.MAX_VALUE;
    this.beforeAdmission = beforeAdmission;
  }

  /**
   * The tag value for an endpoint, or null when endpoints are not tagged.
   *
   * @param endpoint the raw request path, may be null
   * @return the normalized, bounded tag value, or null
   */
  String tag(String endpoint) {
    return includeEndpoint ? bounded(endpoint) : null;
  }

  boolean isIncludeEndpoint() {
    return includeEndpoint;
  }

  boolean isNormalizeEndpoints() {
    return normalizeEndpoints;
  }

  int getMaxEndpointTags() {
    return maxEndpointTags;
  }

  /**
   * Normalizes and sanitizes an endpoint, then keeps the set of distinct values bounded.
   *
   * <p>Known values and a full budget are answered without locking; only the admission of a new
   * value is serialized, so concurrent first requests can never exceed the budget.
   */
  String bounded(String endpoint) {
    String normalized = sanitize(normalize(endpoint));
    if (knownEndpoints.contains(normalized)) {
      return normalized;
    }
    if (knownEndpoints.size() >= maxEndpointTags) {
      return OTHER_ENDPOINT; // the set never shrinks, so a full budget stays full
    }
    beforeAdmission.run();
    synchronized (admissionLock) {
      if (knownEndpoints.contains(normalized)) {
        return normalized;
      }
      if (knownEndpoints.size() >= maxEndpointTags) {
        return OTHER_ENDPOINT;
      }
      knownEndpoints.add(normalized);
      return normalized;
    }
  }

  /**
   * Replaces high cardinality path segments with {@code {id}}.
   *
   * <p>C6/H-11: the endpoint arrives as the raw request path, so {@code /api/users/12345/orders}
   * would create one meter per user id. Normalizing collapses those to one series.
   */
  String normalize(String endpoint) {
    if (!normalizeEndpoints || endpoint == null || endpoint.isEmpty()) {
      return endpoint;
    }
    String[] segments = endpoint.split("/", -1);
    boolean changed = false;
    for (int i = 0; i < segments.length; i++) {
      if (isHighCardinality(segments[i])) {
        segments[i] = ID_PLACEHOLDER;
        changed = true;
      }
    }
    return changed ? String.join("/", segments) : endpoint;
  }

  private static boolean isHighCardinality(String segment) {
    if (segment.isEmpty()) {
      return false;
    }
    return NUMERIC_SEGMENT.matcher(segment).matches()
        || UUID_SEGMENT.matcher(segment).matches()
        || HEX24_SEGMENT.matcher(segment).matches();
  }

  /**
   * A tag-safe value: {@code unknown} for null or empty, bounded length, unsafe chars as {@code _}.
   */
  static String sanitize(String value) {
    if (value == null || value.isEmpty()) {
      return "unknown";
    }
    String bounded = value.length() > MAX_TAG_LENGTH ? value.substring(0, MAX_TAG_LENGTH) : value;
    return UNSAFE_TAG_CHARS.matcher(bounded).replaceAll("_");
  }
}
